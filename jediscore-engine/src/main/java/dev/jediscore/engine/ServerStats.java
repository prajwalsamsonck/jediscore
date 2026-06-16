package dev.jediscore.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Live server counters behind the {@code INFO stats} section (and Micrometer
 * metrics later).
 *
 * <p><strong>Threading.</strong> Most counters are touched only on the command
 * thread (commands processed, keyspace hits/misses, expirations, evictions, the
 * ops/sec sample) and are plain {@code long}s read by {@code INFO} on that same
 * thread. {@link #connectionsReceived} and {@link #rejectedConnections} are bumped
 * on Netty I/O threads, so they are {@link AtomicLong}s.
 */
public final class ServerStats {

    private final long startMillis = System.currentTimeMillis();

    // Cross-thread (I/O threads bump these).
    private final AtomicLong connectionsReceived = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();

    // Command-thread-confined.
    private long commandsProcessed;
    private long keyspaceHits;
    private long keyspaceMisses;
    private long expiredKeys;
    private long evictedKeys;

    // Instantaneous ops/sec, sampled by the cron.
    private long lastSampleMillis = startMillis;
    private long lastSampleCommands;
    private long instantaneousOps;

    /** @return seconds since the server started */
    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startMillis) / 1000;
    }

    /** Records that a connection was accepted (I/O thread). */
    public void recordConnection() {
        connectionsReceived.incrementAndGet();
    }

    /** Records that a connection was rejected by a limit (I/O thread). */
    public void recordRejectedConnection() {
        rejectedConnections.incrementAndGet();
    }

    /** Records one dispatched command (command thread). */
    public void recordCommand() {
        commandsProcessed++;
    }

    /** Records a keyspace read that found a live key (command thread). */
    public void recordKeyspaceHit() {
        keyspaceHits++;
    }

    /** Records a keyspace read that missed (absent or expired) (command thread). */
    public void recordKeyspaceMiss() {
        keyspaceMisses++;
    }

    /**
     * Records expired keys (command thread): lazy expirations pass {@code 1}, an
     * active-expiry cycle passes its batch total.
     *
     * @param count the number of keys expired
     */
    public void recordExpired(long count) {
        expiredKeys += count;
    }

    /** Records one evicted key (command thread). */
    public void recordEviction() {
        evictedKeys++;
    }

    /**
     * Recomputes the instantaneous ops/sec from the commands processed since the
     * last sample. Called from the cron on the command thread.
     */
    public void sampleOps() {
        long now = System.currentTimeMillis();
        long dt = now - lastSampleMillis;
        if (dt > 0) {
            instantaneousOps = (commandsProcessed - lastSampleCommands) * 1000 / dt;
            lastSampleMillis = now;
            lastSampleCommands = commandsProcessed;
        }
    }

    /** Resets the cumulative counters (for {@code CONFIG RESETSTAT}). */
    public void reset() {
        connectionsReceived.set(0);
        rejectedConnections.set(0);
        commandsProcessed = 0;
        keyspaceHits = 0;
        keyspaceMisses = 0;
        expiredKeys = 0;
        evictedKeys = 0;
        instantaneousOps = 0;
        lastSampleCommands = 0;
        lastSampleMillis = System.currentTimeMillis();
    }

    public long connectionsReceived() {
        return connectionsReceived.get();
    }

    public long rejectedConnections() {
        return rejectedConnections.get();
    }

    public long commandsProcessed() {
        return commandsProcessed;
    }

    public long keyspaceHits() {
        return keyspaceHits;
    }

    public long keyspaceMisses() {
        return keyspaceMisses;
    }

    public long expiredKeys() {
        return expiredKeys;
    }

    public long evictedKeys() {
        return evictedKeys;
    }

    public long instantaneousOps() {
        return instantaneousOps;
    }
}
