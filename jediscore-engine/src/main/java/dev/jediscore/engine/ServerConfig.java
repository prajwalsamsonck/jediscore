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
 * @param listMaxListpackSize    the element-count threshold above which a list converts to a quicklist
 * @param listMaxListpackValue   the element byte-length threshold above which a list converts
 * @param setMaxIntsetEntries    the member-count threshold above which an all-integer set leaves the intset encoding
 * @param setMaxListpackEntries  the member-count threshold above which a set converts to a hashtable
 * @param setMaxListpackValue    the member byte-length threshold above which a set converts to a hashtable
 * @param zsetMaxListpackEntries the member-count threshold above which a sorted set converts to a skiplist
 * @param zsetMaxListpackValue   the member byte-length threshold above which a sorted set converts
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
        int hashMaxListpackValue,
        int listMaxListpackSize,
        int listMaxListpackValue,
        int setMaxIntsetEntries,
        int setMaxListpackEntries,
        int setMaxListpackValue,
        int zsetMaxListpackEntries,
        int zsetMaxListpackValue) {

    /** The Redis version JediCore reports itself wire-compatible with. */
    public static final String DEFAULT_VERSION = "7.4.0";

    /** Default number of logical databases. */
    public static final int DEFAULT_DATABASES = 16;

    /** Default {@code hash-max-listpack-entries}. */
    public static final int DEFAULT_HASH_MAX_LISTPACK_ENTRIES = 128;

    /** Default {@code hash-max-listpack-value}. */
    public static final int DEFAULT_HASH_MAX_LISTPACK_VALUE = 64;

    /** Default {@code list-max-listpack-size}. */
    public static final int DEFAULT_LIST_MAX_LISTPACK_SIZE = 128;

    /** Default list element byte-length threshold. */
    public static final int DEFAULT_LIST_MAX_LISTPACK_VALUE = 64;

    /** Default {@code set-max-intset-entries}. */
    public static final int DEFAULT_SET_MAX_INTSET_ENTRIES = 512;

    /** Default {@code set-max-listpack-entries}. */
    public static final int DEFAULT_SET_MAX_LISTPACK_ENTRIES = 128;

    /** Default {@code set-max-listpack-value}. */
    public static final int DEFAULT_SET_MAX_LISTPACK_VALUE = 64;

    /** Default {@code zset-max-listpack-entries}. */
    public static final int DEFAULT_ZSET_MAX_LISTPACK_ENTRIES = 128;

    /** Default {@code zset-max-listpack-value}. */
    public static final int DEFAULT_ZSET_MAX_LISTPACK_VALUE = 64;

    /**
     * Returns a config with sensible defaults for the given host/port: backlog
     * 511 (Redis's default), no password, 16 databases, and Redis's default
     * collection encoding thresholds.
     *
     * @param host the bind address
     * @param port the listen port ({@code 0} for ephemeral)
     * @return the default configuration
     */
    public static ServerConfig defaults(String host, int port) {
        return new ServerConfig(host, port, 511, Optional.empty(), DEFAULT_VERSION, RunId.generate(),
                DEFAULT_DATABASES, DEFAULT_HASH_MAX_LISTPACK_ENTRIES, DEFAULT_HASH_MAX_LISTPACK_VALUE,
                DEFAULT_LIST_MAX_LISTPACK_SIZE, DEFAULT_LIST_MAX_LISTPACK_VALUE,
                DEFAULT_SET_MAX_INTSET_ENTRIES, DEFAULT_SET_MAX_LISTPACK_ENTRIES, DEFAULT_SET_MAX_LISTPACK_VALUE,
                DEFAULT_ZSET_MAX_LISTPACK_ENTRIES, DEFAULT_ZSET_MAX_LISTPACK_VALUE);
    }

    /** @return whether a password is configured and therefore AUTH is required */
    public boolean requiresAuth() {
        return requirepass.isPresent();
    }
}
