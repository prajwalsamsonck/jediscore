package dev.jediscore.engine;

/**
 * Thrown by a command handler to abort with a specific RESP error reply.
 *
 * <p>The message is the full error text including its uppercase code, e.g.
 * {@code "WRONGTYPE Operation against a key holding the wrong kind of value"} or
 * {@code "ERR value is not an integer or out of range"}. The dispatcher catches
 * it and turns it into a {@code -ERR}-style error, distinguishing it from an
 * unexpected bug (which becomes a generic internal error).
 */
public final class CommandException extends RuntimeException {

    /**
     * Creates a command error.
     *
     * @param message the full error text (with its code prefix)
     */
    public CommandException(String message) {
        super(message);
    }

    /** The canonical wrong-type error, raised when a key holds an unexpected type. */
    public static CommandException wrongType() {
        return new CommandException(
                "WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    /** The canonical "not an integer" error. */
    public static CommandException notInteger() {
        return new CommandException("ERR value is not an integer or out of range");
    }

    /** The canonical "not a float" error. */
    public static CommandException notFloat() {
        return new CommandException("ERR value is not a valid float");
    }

    /** The canonical generic syntax error. */
    public static CommandException syntax() {
        return new CommandException("ERR syntax error");
    }
}
