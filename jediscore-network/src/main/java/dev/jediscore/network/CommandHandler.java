package dev.jediscore.network;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandDispatcher;
import dev.jediscore.engine.RespRequest;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tail handler: turns decoded {@link RespRequest}s into command executions
 * and writes the replies back.
 *
 * <p>It does not run commands itself. Instead it hands each request to the
 * single-threaded {@link dev.jediscore.engine.CommandExecutor command loop} and
 * writes the reply from there via {@link ChannelHandlerContext#writeAndFlush}
 * (which is thread-safe and hops back to the I/O thread). Because the executor is
 * a single FIFO thread and requests from a connection are submitted in read
 * order, replies are emitted in request order — making pipelining correct.
 */
public final class CommandHandler extends SimpleChannelInboundHandler<RespRequest> {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final ServerContext server;
    private final CommandDispatcher dispatcher;

    /**
     * Creates a handler bound to the shared server context and dispatcher.
     *
     * @param server     the server context
     * @param dispatcher the command dispatcher
     */
    public CommandHandler(ServerContext server, CommandDispatcher dispatcher) {
        this.server = server;
        this.dispatcher = dispatcher;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        long id = server.nextClientId();
        boolean authenticated = !server.requiresAuth();
        ClientConnection conn = new ClientConnection(
                id,
                format(ctx.channel().remoteAddress()),
                format(ctx.channel().localAddress()),
                authenticated);
        // Out-of-band sink for pub/sub (and later, key-ready) pushes. writeAndFlush
        // is thread-safe and is always invoked from the command thread, so it keeps
        // per-channel ordering with this connection's own replies.
        conn.attachOutbox(message -> {
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(message);
            }
        });
        ctx.channel().attr(ConnectionAttributes.CONNECTION).set(conn);
        server.register(conn);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ClientConnection conn = ctx.channel().attr(ConnectionAttributes.CONNECTION).get();
        if (conn != null) {
            server.unregister(conn);
            // Tear down pub/sub and WATCH state on the command thread, where those
            // registries live, so they need no locking.
            server.executor().submit(() -> {
                server.pubsub().removeAll(conn);
                server.watchTable().unwatchAll(conn);
            });
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RespRequest request) {
        ClientConnection conn = ctx.channel().attr(ConnectionAttributes.CONNECTION).get();
        // Execute on the single command thread; never on this I/O thread.
        server.executor().submit(() -> {
            RespValue reply = dispatcher.dispatch(new CommandContext(server, conn, request.args()));
            if (reply == null) {
                return; // empty/no-op request
            }
            ChannelFuture future = ctx.writeAndFlush(reply);
            if (conn.isCloseAfterReply()) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // A read/IO error on this connection: log at debug (clients disconnect
        // routinely) and drop the connection.
        log.debug("Closing connection after pipeline error", cause);
        ctx.close();
    }

    private static String format(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress() + ":" + inet.getPort();
        }
        return String.valueOf(address);
    }
}
