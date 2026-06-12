package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.protocol.RespValue;

/**
 * {@code PING [message]} — liveness check.
 *
 * <p>With no argument it replies {@code +PONG}; with one argument it echoes that
 * argument as a bulk string. More than one argument is an arity error, matching
 * Redis (whose declared arity of {@code -1} is too loose, so it checks inline).
 */
public final class PingCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() == 1) {
            return RespValue.PONG;
        }
        if (ctx.argCount() == 2) {
            return RespValue.bulk(ctx.arg(1));
        }
        return RespValue.error("ERR wrong number of arguments for 'ping' command");
    }
}
