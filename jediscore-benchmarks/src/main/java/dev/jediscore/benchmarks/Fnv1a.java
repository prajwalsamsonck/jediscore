package dev.jediscore.benchmarks;

/**
 * Allocation-free FNV-1a 32-bit hash over a byte range.
 *
 * <p>Lives in {@code main} (not the {@code jmh} source set) so it is reachable
 * from both the JMH benchmark and an ordinary unit test. It stands in for the
 * sort of per-byte hot-path arithmetic the real engine performs when hashing
 * keys, and gives the Phase&nbsp;0 benchmark something concrete to measure.
 */
public final class Fnv1a {

    private static final int OFFSET_BASIS = 0x811c9dc5;
    private static final int PRIME = 0x01000193;

    private Fnv1a() {
        // Utility holder; not instantiable.
    }

    /**
     * Computes the FNV-1a 32-bit hash of the whole array.
     *
     * @param data the bytes to hash (must not be {@code null})
     * @return the 32-bit hash
     */
    public static int hash(byte[] data) {
        int hash = OFFSET_BASIS;
        // Indexed loop avoids the iterator allocation a for-each would add on
        // the hot path — exactly the discipline the engine will follow.
        for (int i = 0; i < data.length; i++) {
            hash ^= (data[i] & 0xff);
            hash *= PRIME;
        }
        return hash;
    }
}
