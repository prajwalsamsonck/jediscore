package dev.jediscore.datastructures;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Base type for every value stored in the keyspace.
 *
 * <p>It carries the small per-object metadata Redis keeps for eviction: a
 * last-access time (for LRU idle) and a logarithmic access-frequency counter
 * (for LFU). The LFU counter is maintained exactly as Redis's: on each access it
 * is first <em>decayed</em> toward zero based on elapsed minutes, then
 * <em>probabilistically incremented</em> with diminishing probability as the
 * counter grows (so it approximates {@code log(frequency)} in 8 bits). The
 * decay/log-factor constants use Redis's defaults.
 *
 * <p>Confined to the single command thread.
 *
 * @see <a href="https://redis.io/docs/latest/develop/reference/eviction/">Redis eviction</a>
 */
public sealed abstract class RedisValue permits StringValue, HashValue, ListValue, SetValue, ZSetValue {

    /** New objects start here so they aren't evicted immediately (Redis LFU_INIT_VAL). */
    private static final int LFU_INIT_VAL = 5;
    /** Higher = counter saturates more slowly (Redis lfu-log-factor default). */
    private static final int LFU_LOG_FACTOR = 10;
    /** Minutes of idleness that decays the counter by one (Redis lfu-decay-time default). */
    private static final long LFU_DECAY_MINUTES = 1;

    private long lastAccessMillis = System.currentTimeMillis();
    private int freqCounter = LFU_INIT_VAL;
    private long lfuDecayMinute = System.currentTimeMillis() / 60_000;

    /** @return the logical type of this value */
    public abstract RedisType type();

    /** @return the current internal encoding name, as {@code OBJECT ENCODING} reports it */
    public abstract String encoding();

    /**
     * Returns an independent deep copy of this value (for {@code COPY}).
     *
     * @return a copy that shares no mutable state with this value
     */
    public abstract RedisValue deepCopy();

    /**
     * Returns an approximate heap footprint of this value's payload in bytes
     * (excluding the key). Used by {@code MEMORY USAGE} and the maxmemory
     * accounting. It is an estimate — the JVM gives no allocator-exact figure the
     * way Redis's {@code zmalloc} does.
     *
     * @return the estimated payload size in bytes
     */
    public abstract long estimateBytes();

    /** @return the wall-clock time of the last access, in milliseconds */
    public long lastAccessMillis() {
        return lastAccessMillis;
    }

    /**
     * Idle time in milliseconds (for LRU and {@code OBJECT IDLETIME}).
     *
     * @param nowMillis the current time
     * @return milliseconds since last access (never negative)
     */
    public long idleMillis(long nowMillis) {
        return Math.max(0, nowMillis - lastAccessMillis);
    }

    /** @return the current LFU frequency counter (0–255) */
    public int frequency() {
        return freqCounter;
    }

    /**
     * Records an access, updating both the LRU clock and the LFU counter.
     *
     * @param nowMillis the current time in milliseconds
     */
    public void recordAccess(long nowMillis) {
        this.lastAccessMillis = nowMillis;
        // LFU: decay first, then a probabilistic logarithmic increment.
        long nowMinute = nowMillis / 60_000;
        long elapsed = nowMinute - lfuDecayMinute;
        if (elapsed > 0) {
            long periods = elapsed / LFU_DECAY_MINUTES;
            if (periods > 0) {
                freqCounter = (int) Math.max(0, freqCounter - periods);
            }
            lfuDecayMinute = nowMinute;
        }
        if (freqCounter < 255) {
            int baseval = Math.max(0, freqCounter - LFU_INIT_VAL);
            double p = 1.0 / (baseval * LFU_LOG_FACTOR + 1);
            if (ThreadLocalRandom.current().nextDouble() < p) {
                freqCounter++;
            }
        }
    }
}
