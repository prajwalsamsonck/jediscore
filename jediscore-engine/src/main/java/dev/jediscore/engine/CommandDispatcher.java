package dev.jediscore.engine;

import dev.jediscore.protocol.RespValue;
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

        if (spec == null) {
            return RespValue.error(unknownCommandMessage(ctx));
        }
        if (!spec.acceptsArgCount(ctx.argCount())) {
            return RespValue.error(
                    "ERR wrong number of arguments for '" + spec.name() + "' command");
        }
        if (server.requiresAuth()
                && !ctx.connection().isAuthenticated()
                && !NO_AUTH_COMMANDS.contains(upperName)) {
            return RespValue.error("NOAUTH Authentication required.");
        }

        // maxmemory: free memory before a data-adding command; refuse if we can't.
        if (server.config().maxMemory() > 0 && Eviction.isDenyOom(upperName)) {
            if (!Eviction.evictToFit(server)) {
                return RespValue.error("OOM command not allowed when used memory > 'maxmemory'.");
            }
        }

        ctx.connection().setLastCommand(spec.name());
        try {
            RespValue reply = spec.handler().execute(ctx);
            // After a successful write: count it toward RDB save points and feed
            // the AOF. (Errors thrown above never reach here, so failed writes
            // are neither counted nor propagated.)
            if (WriteCommands.isWrite(upperName)) {
                server.markDirty(1);
                Persistence persistence = server.persistence();
                if (persistence != null && persistence.appendOnlyEnabled()) {
                    persistence.feedAppendOnly(ctx.connection().db(), ctx.args());
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
