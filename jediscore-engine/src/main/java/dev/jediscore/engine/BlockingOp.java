package dev.jediscore.engine;

import dev.jediscore.protocol.RespValue;

/**
 * The retry-able body of a blocking command (BLPOP, BLMOVE, BZPOPMIN, …).
 *
 * <p>The same operation is run both as the immediate attempt when the command
 * first executes and again each time a watched key is signalled ready, so the
 * blocking and non-blocking semantics are guaranteed identical. Implementations
 * run on the command thread.
 */
public interface BlockingOp {

    /**
     * Attempts to satisfy the command against the current keyspace, performing the
     * mutation and propagating its effective write (to the AOF / WATCH) on success.
     *
     * @param server the server context
     * @param conn   the blocked connection
     * @return the reply to deliver, or {@code null} if the command still cannot be
     *         satisfied (the client stays blocked)
     */
    RespValue attempt(ServerContext server, ClientConnection conn);

    /**
     * @return the reply to deliver when the block times out (typically a nil array,
     *         or {@code 0} for {@code WAIT})
     */
    RespValue timeoutReply();
}
