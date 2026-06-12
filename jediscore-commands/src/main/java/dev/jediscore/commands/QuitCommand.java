package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.protocol.RespValue;

/**
 * {@code QUIT} — replies {@code +OK} and asks the network layer to close the
 * connection once that reply has been flushed.
 */
public final class QuitCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        ctx.connection().requestClose();
        return RespValue.OK;
    }
}
