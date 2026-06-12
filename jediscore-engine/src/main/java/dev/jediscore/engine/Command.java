package dev.jediscore.engine;

import dev.jediscore.protocol.RespValue;

/**
 * A command handler. Implementations run on the command thread and return the
 * reply to send to the client.
 *
 * <p>Handlers must not block the command thread; Phase-1 commands are all
 * non-blocking. Blocking commands (introduced later) park the client and return
 * control rather than sleeping here.
 */
@FunctionalInterface
public interface Command {

    /**
     * Executes the command.
     *
     * @param ctx the per-invocation context (connection, arguments, server)
     * @return the reply value; never {@code null} (use an explicit RESP value)
     */
    RespValue execute(CommandContext ctx);
}
