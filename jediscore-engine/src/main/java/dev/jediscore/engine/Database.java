package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.Dict;
import dev.jediscore.datastructures.RedisValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * A single logical Redis database (keyspace): the key → value dictionary plus a
 * parallel table of per-key expiration times.
 *
 * <p>The key dictionary is a {@link Dict} (not a {@code HashMap}) specifically so
 * that {@code SCAN} can iterate it with Redis's bucket cursor; the expiry table,
 * which is never scanned, remains a plain {@link HashMap}.
 *
 * <p><strong>Threading.</strong> Only ever touched by the single command thread,
 * so no locking. <strong>Expiration</strong> is lazy: an expired key is removed
 * the next time it is accessed (and {@code KEYS}/{@code DBSIZE} purge as they scan).
 */
public final class Database {

    private final int index;
    private final LongSupplier clock;
    private final Dict<RedisValue> dict = new Dict<>();
    private final Map<Bytes, Long> expires = new HashMap<>();

    /**
     * Creates a database.
     *
     * @param index the database index (0-based)
     * @param clock supplies the current time in milliseconds (injectable for tests)
     */
    public Database(int index, LongSupplier clock) {
        this.index = index;
        this.clock = clock;
    }

    /** @return the database index */
    public int index() {
        return index;
    }

    /**
     * Looks up a key, honouring expiration, and records an access for idle-time
     * tracking.
     *
     * @param key the key
     * @return the live value, or {@code null} if absent or expired
     */
    public RedisValue lookup(Bytes key) {
        if (expireIfNeeded(key)) {
            return null;
        }
        RedisValue value = dict.get(key);
        if (value != null) {
            value.recordAccess(clock.getAsLong());
        }
        return value;
    }

    /**
     * Like {@link #lookup} but does not update the access time.
     *
     * @param key the key
     * @return the live value, or {@code null}
     */
    public RedisValue peek(Bytes key) {
        if (expireIfNeeded(key)) {
            return null;
        }
        return dict.get(key);
    }

    /**
     * Stores a value, clearing any existing TTL.
     *
     * @param key   the key
     * @param value the value
     */
    public void put(Bytes key, RedisValue value) {
        dict.put(key, value);
        expires.remove(key);
    }

    /**
     * Stores a value while preserving any existing TTL.
     *
     * @param key   the key
     * @param value the value
     */
    public void putKeepTtl(Bytes key, RedisValue value) {
        dict.put(key, value);
    }

    /**
     * Removes a key and any TTL.
     *
     * @param key the key
     * @return {@code true} if the key existed
     */
    public boolean remove(Bytes key) {
        expires.remove(key);
        return dict.remove(key) != null;
    }

    /**
     * Tests for a live key.
     *
     * @param key the key
     * @return {@code true} if present and not expired
     */
    public boolean containsKey(Bytes key) {
        return peek(key) != null;
    }

    /** @return the number of live keys (lazily; expired-but-unaccessed keys may be counted) */
    public int size() {
        return dict.size();
    }

    /** Removes all keys and TTLs. */
    public void clear() {
        dict.clear();
        expires.clear();
    }

    /**
     * Advances a {@code SCAN} cursor over the keyspace.
     *
     * @param cursor   the cursor (0 to start)
     * @param count    buckets to visit this call
     * @param consumer receives each visited key and its value
     * @return the next cursor (0 when complete)
     */
    public long scan(long cursor, int count, BiConsumer<Bytes, RedisValue> consumer) {
        return dict.scan(cursor, count, consumer);
    }

    // ---- Expiration ---------------------------------------------------------

    /**
     * Sets an absolute expiration time.
     *
     * @param key        the key (must exist)
     * @param whenMillis the absolute expiry time in epoch milliseconds
     */
    public void setExpireAt(Bytes key, long whenMillis) {
        expires.put(key, whenMillis);
    }

    /**
     * Returns the absolute expiry time of a key.
     *
     * @param key the key
     * @return the epoch-millis expiry, or {@code null} if the key has no TTL
     */
    public Long getExpireAt(Bytes key) {
        return expires.get(key);
    }

    /**
     * Clears a key's TTL.
     *
     * @param key the key
     * @return {@code true} if a TTL was removed
     */
    public boolean persist(Bytes key) {
        return expires.remove(key) != null;
    }

    /** @return whether the key currently has an associated TTL */
    public boolean hasExpire(Bytes key) {
        return expires.containsKey(key);
    }

    // ---- Iteration / introspection ------------------------------------------

    /** @return a snapshot of all live keys (expired keys are purged as a side effect) */
    public List<Bytes> liveKeys() {
        long now = clock.getAsLong();
        List<Bytes> keys = new ArrayList<>(dict.size());
        List<Bytes> expired = null;
        for (Bytes key : dict.keys()) {
            Long when = expires.get(key);
            if (when != null && when <= now) {
                if (expired == null) {
                    expired = new ArrayList<>();
                }
                expired.add(key);
            } else {
                keys.add(key);
            }
        }
        if (expired != null) {
            for (Bytes key : expired) {
                dict.remove(key);
                expires.remove(key);
            }
        }
        return keys;
    }

    /**
     * Returns a uniformly random live key, or {@code null} if empty.
     *
     * @return a random key, or {@code null}
     */
    public Bytes randomKey() {
        List<Bytes> keys = dict.keys();
        if (keys.isEmpty()) {
            return null;
        }
        Bytes key = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
        if (expireIfNeeded(key)) {
            return randomKey();
        }
        return key;
    }

    private boolean expireIfNeeded(Bytes key) {
        Long when = expires.get(key);
        if (when == null) {
            return false;
        }
        if (when <= clock.getAsLong()) {
            dict.remove(key);
            expires.remove(key);
            return true;
        }
        return false;
    }
}
