package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.protocol.RespValue;

/**
 * {@code ECHO message} — returns its single argument unchanged as a bulk string.
 */
public final class EchoCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        return RespValue.bulk(ctx.arg(1));
    }
}
