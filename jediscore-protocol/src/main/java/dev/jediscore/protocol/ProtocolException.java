package dev.jediscore.protocol;

/**
 * Thrown when an incoming byte stream violates the RESP grammar (a bad length,
 * a missing type marker, unbalanced quotes in an inline command, …).
 *
 * <p>The {@linkplain #getMessage() message} is the bare reason (for example
 * {@code "invalid bulk length"}); the network layer prefixes it to form the
 * Redis-compatible reply {@code -ERR Protocol error: <reason>} and then closes
 * the connection, exactly as Redis does. It is unchecked because it signals a
 * client/protocol fault on the I/O path, not a recoverable condition the codec
 * itself can handle.
 */
public final class ProtocolException extends RuntimeException {

    /**
     * Creates a protocol exception.
     *
     * @param reason the bare reason, matching Redis's wording where practical
     */
    public ProtocolException(String reason) {
        super(reason);
    }
}
