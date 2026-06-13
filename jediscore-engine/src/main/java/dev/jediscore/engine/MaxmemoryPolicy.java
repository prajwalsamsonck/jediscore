package dev.jediscore.engine;

import java.util.Locale;

/**
 * The eviction policy applied when {@code maxmemory} is reached, mirroring
 * Redis's {@code maxmemory-policy} values.
 *
 * <p>The {@code allkeys-*} variants consider every key; the {@code volatile-*}
 * variants only keys with a TTL. {@code *-lru} evict the least recently used,
 * {@code *-lfu} the least frequently used, {@code *-random} a random victim, and
 * {@code volatile-ttl} the key closest to expiring.
 */
public enum MaxmemoryPolicy {

    /** Never evict; write commands fail with an OOM error once at the limit. */
    NOEVICTION("noeviction"),
    ALLKEYS_LRU("allkeys-lru"),
    ALLKEYS_LFU("allkeys-lfu"),
    VOLATILE_LRU("volatile-lru"),
    VOLATILE_LFU("volatile-lfu"),
    ALLKEYS_RANDOM("allkeys-random"),
    VOLATILE_RANDOM("volatile-random"),
    VOLATILE_TTL("volatile-ttl");

    private final String configName;

    MaxmemoryPolicy(String configName) {
        this.configName = configName;
    }

    /** @return the {@code maxmemory-policy} config string */
    public String configName() {
        return configName;
    }

    /** @return whether this policy only considers keys that have a TTL */
    public boolean isVolatileOnly() {
        return this == VOLATILE_LRU || this == VOLATILE_LFU
                || this == VOLATILE_RANDOM || this == VOLATILE_TTL;
    }

    /** @return whether this policy uses the LFU frequency counter */
    public boolean isLfu() {
        return this == ALLKEYS_LFU || this == VOLATILE_LFU;
    }

    /** @return whether this policy uses the LRU idle time */
    public boolean isLru() {
        return this == ALLKEYS_LRU || this == VOLATILE_LRU;
    }

    /**
     * Parses a {@code maxmemory-policy} config string.
     *
     * @param name the policy name (e.g. {@code allkeys-lru})
     * @return the policy, or {@code null} if unrecognised
     */
    public static MaxmemoryPolicy fromConfig(String name) {
        String normalised = name.toLowerCase(Locale.ROOT);
        for (MaxmemoryPolicy policy : values()) {
            if (policy.configName.equals(normalised)) {
                return policy;
            }
        }
        return null;
    }
}
