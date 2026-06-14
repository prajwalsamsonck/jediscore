package dev.jediscore.engine;

import dev.jediscore.protocol.RespValue;
import dev.jediscore.protocol.RespVersion;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and runs a command, producing the reply value.
 *
 * <p>Runs on the command thread. It enforces, in order: arity, authentication
 * gating, then execution — and produces Redis-compatible error replies for
 * unknown commands, wrong arity, and the auth gate. Unexpected handler
 * exceptions are logged and turned into a generic {@code -ERR} so one bad
 * command cannot take down the command loop.
 */
public final class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    /** Commands permitted before authentication when a password is configured. */
    private static final Set<String> NO_AUTH_COMMANDS = Set.of("AUTH", "HELLO", "QUIT", "RESET");

    /**
     * Commands permitted while a RESP2 connection is in subscribe mode. In RESP3
     * the restriction is lifted (pushes are out-of-band, so normal commands can
     * interleave safely).
     */
    private static final Set<String> SUBSCRIBE_MODE_COMMANDS = Set.of(
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE",
            "SSUBSCRIBE", "SUNSUBSCRIBE", "PING", "QUIT", "RESET");

    /**
     * Commands executed immediately even inside {@code MULTI} (everything else is
     * queued). {@code WATCH} is here so it can reject being called inside a
     * transaction; {@code UNWATCH} is intentionally absent, so it queues like any
     * normal command — matching Redis.
     */
    private static final Set<String> TRANSACTION_CONTROL =
            Set.of("MULTI", "EXEC", "DISCARD", "WATCH", "RESET", "QUIT");

    private static final RespValue QUEUED = RespValue.simple("QUEUED");

    private final ServerContext server;

    /**
     * Creates a dispatcher.
     *
     * @param server the server context
     */
    public CommandDispatcher(ServerContext server) {
        this.server = server;
    }

    /**
     * Dispatches a single request.
     *
     * @param ctx the command context
     * @return the reply, or {@code null} if there was nothing to do (empty request)
     */
    public RespValue dispatch(CommandContext ctx) {
        if (ctx.argCount() == 0) {
            return null;
        }
        String upperName = ctx.argText(0).toUpperCase(Locale.ROOT);
        CommandSpec spec = server.registry().lookup(upperName);
        ClientConnection conn = ctx.connection();
        boolean queueing = conn.inMulti() && !TRANSACTION_CONTROL.contains(upperName);

        if (spec == null) {
            if (queueing) {
                conn.markTransactionError();
            }
            return RespValue.error(unknownCommandMessage(ctx));
        }
        if (server.requiresAuth()
                && !conn.isAuthenticated()
                && !NO_AUTH_COMMANDS.contains(upperName)) {
            if (queueing) {
                conn.markTransactionError();
            }
            return RespValue.error("NOAUTH Authentication required.");
        }

        // Inside MULTI: validate arity, then queue (or flag the transaction so EXEC
        // aborts). Control commands fall through to immediate execution.
        if (queueing) {
            if (!spec.acceptsArgCount(ctx.argCount())) {
                conn.markTransactionError();
                return RespValue.error(
                        "ERR wrong number of arguments for '" + spec.name() + "' command");
            }
            conn.queueCommand(ctx.args());
            return QUEUED;
        }

        if (!spec.acceptsArgCount(ctx.argCount())) {
            return RespValue.error(
                    "ERR wrong number of arguments for '" + spec.name() + "' command");
        }

        // RESP2 subscriber mode: only the pub/sub control commands (plus PING/
        // QUIT/RESET) may run. RESP3 carries pushes out-of-band, so it is exempt.
        if (conn.inSubscribeMode()
                && conn.protocol() == RespVersion.RESP2
                && !SUBSCRIBE_MODE_COMMANDS.contains(upperName)) {
            return RespValue.error("ERR Can't execute '" + spec.name()
                    + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context");
        }

        // maxmemory: free memory before a data-adding command; refuse if we can't.
        if (server.config().maxMemory() > 0 && Eviction.isDenyOom(upperName)) {
            if (!Eviction.evictToFit(server)) {
                return RespValue.error("OOM command not allowed when used memory > 'maxmemory'.");
            }
        }

        conn.setLastCommand(spec.name());
        try {
            RespValue reply = spec.handler().execute(ctx);
            // After a successful write: count it toward RDB save points, invalidate
            // any WATCH on the touched keys, and feed the AOF. (Errors thrown above
            // never reach here, so failed writes are neither counted nor propagated.)
            if (WriteCommands.isWrite(upperName)) {
                server.markDirty(1);
                server.watchTable().touchByArguments(conn.db(), ctx.args());
                Persistence persistence = server.persistence();
                if (persistence != null && persistence.appendOnlyEnabled()) {
                    persistence.feedAppendOnly(conn.db(), ctx.args());
                }
                // A write (e.g. RPUSH) may make a key ready for a blocked BLPOP.
                if (server.blocking().hasBlockedClients()) {
                    server.blocking().signalKeys(conn.db(), ctx.args());
                }
            }
            return reply;
        } catch (CommandException e) {
            // A deliberate, client-facing error (WRONGTYPE, bad integer, syntax, …).
            return RespValue.error(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unhandled error executing command '{}'", spec.name(), e);
            return RespValue.error("ERR internal error");
        }
    }

    /**
     * Builds Redis's "unknown command" message, e.g.
     * {@code unknown command 'FOO', with args beginning with: 'a', 'b', }.
     */
    private static String unknownCommandMessage(CommandContext ctx) {
        StringBuilder sb = new StringBuilder("ERR unknown command '");
        sb.append(ctx.argText(0)).append("', with args beginning with: ");
        int maxArgs = Math.min(ctx.argCount(), 21); // command name + up to 20 args
        for (int i = 1; i < maxArgs; i++) {
            String arg = new String(ctx.arg(i), StandardCharsets.UTF_8);
            if (arg.length() > 128) {
                arg = arg.substring(0, 128);
            }
            sb.append('\'').append(arg).append("', ");
        }
        return sb.toString();
    }
}
