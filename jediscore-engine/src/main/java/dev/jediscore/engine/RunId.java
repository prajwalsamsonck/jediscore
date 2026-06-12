package dev.jediscore.engine;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates Redis-style 40-character hexadecimal run identifiers.
 *
 * <p>Redis assigns each server instance a random {@code run_id} reported by
 * {@code INFO} and used during replication handshakes. We mirror the format so
 * the value looks right to tooling that parses it.
 */
public final class RunId {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RunId() {
        // Static utility; not instantiable.
    }

    /**
     * Generates a fresh 40-hex-character run id.
     *
     * @return the run id
     */
    public static String generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
