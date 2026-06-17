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

    /** Commands not echoed to MONITOR clients (avoid leaking credentials). */
    private static final Set<String> MONITOR_HIDDEN = Set.of("AUTH", "HELLO", "RESET");

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
        server.stats().recordCommand();
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

        // Protected mode: a non-loopback client cannot run commands when no password
        // is set, unless protected mode has been disabled. (AUTH/HELLO are allowed
        // so a client can still negotiate; CONFIG is allowed to turn it off locally.)
        if (server.protectedMode()
                && !server.requiresAuth()
                && !conn.isLoopback()
                && !conn.isMasterLink()
                && !NO_AUTH_COMMANDS.contains(upperName)
                && !upperName.equals("CONFIG")) {
            return RespValue.error("DENIED JediCore is running in protected mode because protected mode "
                    + "is enabled and no password is set. Connect from the loopback interface, set a "
                    + "password (requirepass), or disable protected mode (protected-mode no).");
        }

        // ACL: enforce the authenticated user's command and key permissions.
        if (!NO_AUTH_COMMANDS.contains(upperName)) {
            AclUser user = server.acl().user(conn.user());
            if (user != null) {
                if (!user.canRun(upperName)) {
                    if (queueing) {
                        conn.markTransactionError();
                    }
                    return RespValue.error("NOPERM User " + conn.user()
                            + " has no permissions to run the '" + spec.name() + "' command");
                }
                if (!user.allKeys()) {
                    for (byte[] key : CommandKeys.extractKeys(ctx.args())) {
                        if (!user.canAccessKey(key)) {
                            if (queueing) {
                                conn.markTransactionError();
                            }
                            return RespValue.error("NOPERM No permissions to access a key");
                        }
                    }
                }
            }
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

        // Read-only replica: reject client writes (the master-link applying the
        // replication stream is exempt).
        if (server.replication().isReplica()
                && !conn.isMasterLink()
                && WriteCommands.isWrite(upperName)) {
            return RespValue.error("READONLY You can't write against a read only replica.");
        }

        // maxmemory: free memory before a data-adding command; refuse if we can't.
        if (server.config().maxMemory() > 0 && Eviction.isDenyOom(upperName)) {
            if (!Eviction.evictToFit(server)) {
                return RespValue.error("OOM command not allowed when used memory > 'maxmemory'.");
            }
        }

        // Feed MONITOR clients before executing (as Redis does), excluding the
        // monitors' own commands, the replication link, and AUTH (avoid leaking it).
        if (server.monitors().hasMonitors() && !conn.isMonitor() && !conn.isMasterLink()
                && !MONITOR_HIDDEN.contains(upperName)) {
            server.monitors().feed(conn.db(), conn.remoteAddress(), ctx.args());
        }

        conn.setLastCommand(spec.name());
        long startNanos = System.nanoTime();
        RespValue reply;
        try {
            reply = spec.handler().execute(ctx);
            // After a successful write: count it toward RDB save points, invalidate
            // any WATCH on the touched keys, and feed the AOF. (Errors thrown above
            // never reach here, so failed writes are neither counted nor propagated.)
            if (WriteCommands.isWrite(upperName)) {
                server.markDirty(1);
                server.watchTable().touchByArguments(conn.db(), ctx.args());
                // Propagate to the AOF and replicas: the command's deterministic
                // rewrite if it set one, otherwise the verbatim command.
                java.util.List<byte[][]> override = ctx.propagationOverride();
                if (override == null) {
                    server.propagateEffect(conn.db(), ctx.args());
                } else {
                    for (byte[][] cmd : override) {
                        server.propagateEffect(conn.db(), cmd);
                    }
                }
                // A write (e.g. RPUSH) may make a key ready for a blocked BLPOP.
                if (server.blocking().hasBlockedClients()) {
                    server.blocking().signalKeys(conn.db(), ctx.args());
                }
            }
        } catch (CommandException e) {
            // A deliberate, client-facing error (WRONGTYPE, bad integer, syntax, …).
            reply = RespValue.error(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unhandled error executing command '{}'", spec.name(), e);
            reply = RespValue.error("ERR internal error");
        }
        long durationMicros = (System.nanoTime() - startNanos) / 1000;
        server.slowLog().maybeRecord(durationMicros, ctx);
        server.latencyMonitor().maybeRecordCommand(durationMicros);
        return reply;
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
