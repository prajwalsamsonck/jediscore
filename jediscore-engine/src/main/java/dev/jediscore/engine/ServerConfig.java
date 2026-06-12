package dev.jediscore.engine;

import java.util.Optional;

/**
 * Immutable server configuration.
 *
 * @param host        the bind address (e.g. {@code 127.0.0.1} or {@code 0.0.0.0})
 * @param port        the listen port; {@code 0} selects an ephemeral port (useful in tests)
 * @param backlog     the accept backlog passed to the listening socket
 * @param requirepass an optional password; when present, clients must {@code AUTH} before other commands
 * @param version     the Redis version string reported by {@code HELLO}/{@code INFO}
 * @param runId       a 40-char hex run identifier, as Redis exposes
 * @param databases   the number of logical databases (Redis default 16)
 * @param hashMaxListpackEntries the field-count threshold above which a hash converts to a hashtable
 * @param hashMaxListpackValue   the field/value byte-length threshold above which a hash converts
 */
public record ServerConfig(
        String host,
        int port,
        int backlog,
        Optional<String> requirepass,
        String version,
        String runId,
        int databases,
        int hashMaxListpackEntries,
        int hashMaxListpackValue) {

    /** The Redis version JediCore reports itself wire-compatible with. */
    public static final String DEFAULT_VERSION = "7.4.0";

    /** Default number of logical databases. */
    public static final int DEFAULT_DATABASES = 16;

    /** Default {@code hash-max-listpack-entries}. */
    public static final int DEFAULT_HASH_MAX_LISTPACK_ENTRIES = 128;

    /** Default {@code hash-max-listpack-value}. */
    public static final int DEFAULT_HASH_MAX_LISTPACK_VALUE = 64;

    /**
     * Returns a config with sensible defaults for the given host/port: backlog
     * 511 (Redis's default), no password, 16 databases, and Redis's default
     * hash encoding thresholds.
     *
     * @param host the bind address
     * @param port the listen port ({@code 0} for ephemeral)
     * @return the default configuration
     */
    public static ServerConfig defaults(String host, int port) {
        return new ServerConfig(host, port, 511, Optional.empty(), DEFAULT_VERSION, RunId.generate(),
                DEFAULT_DATABASES, DEFAULT_HASH_MAX_LISTPACK_ENTRIES, DEFAULT_HASH_MAX_LISTPACK_VALUE);
    }

    /** @return whether a password is configured and therefore AUTH is required */
    public boolean requiresAuth() {
        return requirepass.isPresent();
    }
}
