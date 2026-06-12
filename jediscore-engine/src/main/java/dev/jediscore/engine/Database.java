package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * A single logical Redis database (keyspace): the key → value dictionary plus a
 * parallel table of per-key expiration times.
 *
 * <p><strong>Threading.</strong> A {@code Database} is only ever touched by the
 * single command thread, so it uses plain {@link HashMap}s with no locking.
 *
 * <p><strong>Expiration.</strong> Phase 2 implements <em>lazy</em> expiration:
 * an expired key is detected and removed the next time it is accessed, exactly
 * the cheap half of Redis's strategy. (Active/background sampling is added later;
 * it is a performance optimisation, not a correctness requirement — lazy
 * expiration alone already makes reads and writes observe correct values.)
 */
public final class Database {

    private final int index;
    private final LongSupplier clock;
    private final Map<Bytes, RedisValue> dict = new HashMap<>();
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
     * Looks up a key, honouring expiration. If the key has expired it is removed
     * and {@code null} is returned. Records an access on the value for idle-time
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
     * Like {@link #lookup} but does not update the access time — used by commands
     * such as {@code OBJECT} that must observe, not touch, the value.
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
     * Stores a value, clearing any existing TTL (a plain {@code SET}-style write
     * removes the previous expiration, matching Redis).
     *
     * @param key   the key
     * @param value the value
     */
    public void put(Bytes key, RedisValue value) {
        dict.put(key, value);
        expires.remove(key);
    }

    /**
     * Stores a value while preserving any existing TTL (for in-place mutations
     * such as {@code APPEND} or {@code HSET} that must keep the key's TTL).
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

    /** @return the number of live keys (lazily; expired keys may still be counted until accessed) */
    public int size() {
        return dict.size();
    }

    /** Removes all keys and TTLs. */
    public void clear() {
        dict.clear();
        expires.clear();
    }

    // ---- Expiration ---------------------------------------------------------

    /**
     * Sets an absolute expiration time.
     *
     * @param key            the key (must exist)
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
     * Clears a key's TTL, making it persistent.
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
        for (Bytes key : dict.keySet()) {
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
     * Returns a uniformly random live key, or {@code null} if the database is
     * empty.
     *
     * @return a random key, or {@code null}
     */
    public Bytes randomKey() {
        int size = dict.size();
        if (size == 0) {
            return null;
        }
        int target = ThreadLocalRandom.current().nextInt(size);
        int i = 0;
        for (Bytes key : dict.keySet()) {
            if (i++ == target) {
                if (expireIfNeeded(key)) {
                    // Rare: hit an expired key; fall back to a fresh scan.
                    return randomKey();
                }
                return key;
            }
        }
        return null;
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
