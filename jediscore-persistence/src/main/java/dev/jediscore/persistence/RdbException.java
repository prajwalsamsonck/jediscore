package dev.jediscore.persistence;

/**
 * Thrown when an RDB save or load fails. Unchecked so it can propagate through
 * the {@link dev.jediscore.engine.Persistence} interface; command handlers turn
 * it into an error reply.
 */
public final class RdbException extends RuntimeException {

    /**
     * @param message the failure description
     * @param cause   the underlying cause
     */
    public RdbException(String message, Throwable cause) {
        super(message, cause);
    }
}
