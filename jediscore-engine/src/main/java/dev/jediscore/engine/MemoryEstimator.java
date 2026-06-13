package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;

/**
 * Approximate memory accounting for a stored entry.
 *
 * <p>The JVM gives no allocator-exact byte count the way Redis's {@code zmalloc}
 * does, so this is necessarily an estimate: a fixed per-key overhead (the key
 * string plus the dict-entry/object bookkeeping Redis would allocate) plus the
 * value's own {@linkplain RedisValue#estimateBytes() payload estimate}. The
 * absolute numbers won't match Redis, but they are stable and proportional, which
 * is what {@code MEMORY USAGE} reporting and maxmemory accounting need.
 */
public final class MemoryEstimator {

    /** Per-key overhead: dict entry + object header + key SDS bookkeeping (approx). */
    static final int KEY_OVERHEAD = 56;

    private MemoryEstimator() {
        // Static utility; not instantiable.
    }

    /**
     * Estimates the memory used by a key and its value.
     *
     * @param key   the key
     * @param value the value
     * @return the estimated bytes
     */
    public static long usage(Bytes key, RedisValue value) {
        return KEY_OVERHEAD + key.length() + value.estimateBytes();
    }
}
