package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.protocol.RespValue;
import dev.jediscore.protocol.RespVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code PING [message]} — liveness check.
 *
 * <p>With no argument it replies {@code +PONG}; with one argument it echoes that
 * argument as a bulk string. More than one argument is an arity error, matching
 * Redis (whose declared arity of {@code -1} is too loose, so it checks inline).
 *
 * <p><strong>Subscriber mode (RESP2).</strong> While a RESP2 connection is
 * subscribed, {@code PING} is one of the few permitted commands, and Redis replies
 * with a two-element array {@code ["pong", message]} (the message defaulting to an
 * empty bulk string) so it is distinguishable from a pushed message. RESP3 keeps
 * the normal reply because pushes are already out-of-band.
 */
public final class PingCommand implements Command {

    private static final RespValue PONG_LABEL =
            RespValue.bulk("pong".getBytes(StandardCharsets.UTF_8));
    private static final RespValue EMPTY_BULK =
            RespValue.bulk(new byte[0]);

    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() > 2) {
            return RespValue.error("ERR wrong number of arguments for 'ping' command");
        }
        boolean subscriberMode = ctx.connection().inSubscribeMode()
                && ctx.connection().protocol() == RespVersion.RESP2;
        if (subscriberMode) {
            RespValue message = ctx.argCount() == 2 ? RespValue.bulk(ctx.arg(1)) : EMPTY_BULK;
            return new RespValue.Array(List.of(PONG_LABEL, message));
        }
        if (ctx.argCount() == 2) {
            return RespValue.bulk(ctx.arg(1));
        }
        return RespValue.PONG;
    }
}
