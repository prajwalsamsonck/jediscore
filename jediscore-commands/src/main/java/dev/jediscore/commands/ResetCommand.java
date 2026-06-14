package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.protocol.RespValue;

/**
 * {@code RESET} — returns the connection to a clean state: RESP2, no client
 * name, and (if a password is configured) unauthenticated again. Replies with
 * the simple string {@code +RESET}.
 *
 * <p>Transactions, subscriptions, and the selected DB will also be reset here
 * once those features exist; the connection state they live on is reset via
 * {@link dev.jediscore.engine.ClientConnection#reset(boolean)}.
 */
public final class ResetCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        boolean authedAfter = !ctx.server().requiresAuth();
        // Drop pub/sub and WATCH state from the server-side indexes before clearing
        // the connection's own sets, so those registries stay consistent.
        ctx.server().pubsub().removeAll(ctx.connection());
        ctx.server().watchTable().unwatchAll(ctx.connection());
        ctx.server().blocking().cancel(ctx.connection());
        ctx.connection().reset(authedAfter);
        return RespValue.simple("RESET");
    }
}
