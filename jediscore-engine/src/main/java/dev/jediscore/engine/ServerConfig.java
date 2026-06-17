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
 * @param maxMemory              the memory limit in bytes ({@code 0} = unlimited)
 * @param maxMemoryPolicy        the eviction policy applied at the limit
 * @param maxMemorySamples       the number of keys sampled when choosing an eviction victim
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
        int zsetMaxListpackValue,
        long maxMemory,
        MaxmemoryPolicy maxMemoryPolicy,
        int maxMemorySamples) {

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

    /** Default {@code maxmemory-samples}. */
    public static final int DEFAULT_MAXMEMORY_SAMPLES = 5;

    /**
     * Returns a config with sensible defaults for the given host/port: backlog
     * 511, no password, 16 databases, Redis's default collection encoding
     * thresholds, and no memory limit ({@code noeviction}).
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
                DEFAULT_ZSET_MAX_LISTPACK_ENTRIES, DEFAULT_ZSET_MAX_LISTPACK_VALUE,
                0, MaxmemoryPolicy.NOEVICTION, DEFAULT_MAXMEMORY_SAMPLES);
    }

    /**
     * Returns the default configuration with a password set (test/convenience
     * factory; keeps the long canonical constructor out of callers).
     *
     * @param host     the bind address
     * @param port     the listen port
     * @param password the {@code requirepass} value
     * @return the configuration
     */
    public static ServerConfig secured(String host, int port, String password) {
        ServerConfig base = defaults(host, port);
        return new ServerConfig(base.host(), base.port(), base.backlog(), Optional.of(password),
                base.version(), base.runId(), base.databases(),
                base.hashMaxListpackEntries(), base.hashMaxListpackValue(),
                base.listMaxListpackSize(), base.listMaxListpackValue(),
                base.setMaxIntsetEntries(), base.setMaxListpackEntries(), base.setMaxListpackValue(),
                base.zsetMaxListpackEntries(), base.zsetMaxListpackValue(),
                base.maxMemory(), base.maxMemoryPolicy(), base.maxMemorySamples());
    }

    /**
     * Returns a copy of this config with memory-limit settings applied.
     *
     * @param maxMemory the byte limit
     * @param policy    the eviction policy
     * @return the updated configuration
     */
    public ServerConfig withMaxMemory(long maxMemory, MaxmemoryPolicy policy) {
        return new ServerConfig(host, port, backlog, requirepass, version, runId, databases,
                hashMaxListpackEntries, hashMaxListpackValue, listMaxListpackSize, listMaxListpackValue,
                setMaxIntsetEntries, setMaxListpackEntries, setMaxListpackValue,
                zsetMaxListpackEntries, zsetMaxListpackValue, maxMemory, policy, maxMemorySamples);
    }

    /** @return whether a password is configured and therefore AUTH is required */
    public boolean requiresAuth() {
        return requirepass.isPresent();
    }

    /** @return a mutable builder seeded from this configuration (for {@code CONFIG SET}) */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * A mutable builder for {@link ServerConfig}, so runtime {@code CONFIG SET}
     * can produce an updated immutable config by changing one field at a time.
     */
    public static final class Builder {
        private String host;
        private int port;
        private int backlog;
        private Optional<String> requirepass;
        private String version;
        private String runId;
        private int databases;
        private int hashMaxListpackEntries;
        private int hashMaxListpackValue;
        private int listMaxListpackSize;
        private int listMaxListpackValue;
        private int setMaxIntsetEntries;
        private int setMaxListpackEntries;
        private int setMaxListpackValue;
        private int zsetMaxListpackEntries;
        private int zsetMaxListpackValue;
        private long maxMemory;
        private MaxmemoryPolicy maxMemoryPolicy;
        private int maxMemorySamples;

        private Builder(ServerConfig c) {
            this.host = c.host;
            this.port = c.port;
            this.backlog = c.backlog;
            this.requirepass = c.requirepass;
            this.version = c.version;
            this.runId = c.runId;
            this.databases = c.databases;
            this.hashMaxListpackEntries = c.hashMaxListpackEntries;
            this.hashMaxListpackValue = c.hashMaxListpackValue;
            this.listMaxListpackSize = c.listMaxListpackSize;
            this.listMaxListpackValue = c.listMaxListpackValue;
            this.setMaxIntsetEntries = c.setMaxIntsetEntries;
            this.setMaxListpackEntries = c.setMaxListpackEntries;
            this.setMaxListpackValue = c.setMaxListpackValue;
            this.zsetMaxListpackEntries = c.zsetMaxListpackEntries;
            this.zsetMaxListpackValue = c.zsetMaxListpackValue;
            this.maxMemory = c.maxMemory;
            this.maxMemoryPolicy = c.maxMemoryPolicy;
            this.maxMemorySamples = c.maxMemorySamples;
        }

        public Builder host(String v) { this.host = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder backlog(int v) { this.backlog = v; return this; }
        public Builder requirepass(Optional<String> v) { this.requirepass = v; return this; }
        public Builder databases(int v) { this.databases = v; return this; }
        public Builder hashMaxListpackEntries(int v) { this.hashMaxListpackEntries = v; return this; }
        public Builder hashMaxListpackValue(int v) { this.hashMaxListpackValue = v; return this; }
        public Builder listMaxListpackSize(int v) { this.listMaxListpackSize = v; return this; }
        public Builder listMaxListpackValue(int v) { this.listMaxListpackValue = v; return this; }
        public Builder setMaxIntsetEntries(int v) { this.setMaxIntsetEntries = v; return this; }
        public Builder setMaxListpackEntries(int v) { this.setMaxListpackEntries = v; return this; }
        public Builder setMaxListpackValue(int v) { this.setMaxListpackValue = v; return this; }
        public Builder zsetMaxListpackEntries(int v) { this.zsetMaxListpackEntries = v; return this; }
        public Builder zsetMaxListpackValue(int v) { this.zsetMaxListpackValue = v; return this; }
        public Builder maxMemory(long v) { this.maxMemory = v; return this; }
        public Builder maxMemoryPolicy(MaxmemoryPolicy v) { this.maxMemoryPolicy = v; return this; }
        public Builder maxMemorySamples(int v) { this.maxMemorySamples = v; return this; }

        /** @return the immutable configuration */
        public ServerConfig build() {
            return new ServerConfig(host, port, backlog, requirepass, version, runId, databases,
                    hashMaxListpackEntries, hashMaxListpackValue, listMaxListpackSize, listMaxListpackValue,
                    setMaxIntsetEntries, setMaxListpackEntries, setMaxListpackValue,
                    zsetMaxListpackEntries, zsetMaxListpackValue, maxMemory, maxMemoryPolicy, maxMemorySamples);
        }
    }
}
