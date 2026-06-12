package dev.jediscore.network;

import dev.jediscore.engine.CommandDispatcher;
import dev.jediscore.engine.ServerContext;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Netty TCP server that fronts the engine.
 *
 * <p>Two event-loop groups follow the standard Netty split: a one-thread boss
 * group accepts connections, and a worker group runs per-connection I/O (decode
 * + encode). Command <em>execution</em> happens on the engine's command thread,
 * not here, so the worker threads never block on application logic.
 *
 * <p>Each accepted channel gets a fresh pipeline of
 * {@code [request-decoder, response-encoder, command-handler]}. The handlers are
 * created per channel (cheap, and avoids any shared mutable state).
 */
public final class RespServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RespServer.class);

    private final ServerContext context;
    private final CommandDispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int boundPort = -1;

    /**
     * Creates a server for the given engine context.
     *
     * @param context the shared server context (config, registry, executor)
     */
    public RespServer(ServerContext context) {
        this.context = context;
        this.dispatcher = new CommandDispatcher(context);
    }

    /**
     * Binds the listening socket and starts accepting connections.
     *
     * @return the actually-bound port (useful when the configured port is 0)
     * @throws InterruptedException if interrupted while binding
     */
    public int start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("jedicore-boss"));
        workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("jedicore-io"));

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, context.config().backlog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("decoder", new RespRequestDecoder())
                                .addLast("encoder", new RespResponseEncoder())
                                .addLast("handler", new CommandHandler(context, dispatcher));
                    }
                });

        serverChannel = bootstrap
                .bind(context.config().host(), context.config().port())
                .sync()
                .channel();
        boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("Listening on {}:{}", context.config().host(), boundPort);
        return boundPort;
    }

    /** @return the bound port, or {@code -1} if not started */
    public int port() {
        return boundPort;
    }

    /**
     * Blocks the calling thread until the server channel is closed. Netty's
     * future type is kept internal so callers (and downstream modules) don't take
     * a compile dependency on Netty.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitShutdown() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }

    /**
     * Gracefully stops accepting connections and shuts down both event-loop
     * groups, allowing in-flight I/O to drain. The engine's command executor is
     * owned and closed by the caller, not here.
     */
    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        // Zero quiet period so tests (and Ctrl-C) shut down promptly.
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
        }
        log.info("Network server stopped");
    }
}
