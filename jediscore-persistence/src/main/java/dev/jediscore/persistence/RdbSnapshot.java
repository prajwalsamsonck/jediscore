package dev.jediscore.persistence;

import dev.jediscore.datastructures.RedisValue;
import java.util.List;

/**
 * An immutable, point-in-time copy of the keyspace to be serialized to RDB.
 *
 * <p>Produced on the command thread (by deep-copying values), then handed to a
 * background thread for serialization — the fork-free equivalent of Redis's
 * copy-on-write {@code fork()} snapshot.
 *
 * @param databases the per-database snapshots (only non-empty databases need be present)
 */
public record RdbSnapshot(List<DatabaseSnapshot> databases) {

    /**
     * A snapshot of one database.
     *
     * @param index   the database index
     * @param entries its entries
     */
    public record DatabaseSnapshot(int index, List<Entry> entries) {
    }

    /**
     * A single key/value/expiry triple.
     *
     * @param key        the key bytes
     * @param value      the value (an independent deep copy)
     * @param expireAtMs absolute expiry in epoch millis, or {@code -1} for none
     */
    public record Entry(byte[] key, RedisValue value, long expireAtMs) {
    }
}
