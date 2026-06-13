package dev.jediscore.persistence;

import dev.jediscore.datastructures.RedisValue;

/**
 * Receives each key/value loaded from an RDB stream.
 */
@FunctionalInterface
public interface RdbLoadCallback {

    /**
     * Loads one entry.
     *
     * @param database   the database index the key belongs to
     * @param key        the key bytes
     * @param value      the reconstructed value
     * @param expireAtMs absolute expiry in epoch millis, or {@code -1} for none
     */
    void load(int database, byte[] key, RedisValue value, long expireAtMs);
}
