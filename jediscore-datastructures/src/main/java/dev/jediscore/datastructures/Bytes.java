package dev.jediscore.datastructures;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An immutable, binary-safe wrapper around a {@code byte[]} that provides value
 * equality and a cached hash code, so byte strings can be used as map keys.
 *
 * <p>Redis keys, hash fields, and set members are all arbitrary binary data, not
 * {@code String}s; this wrapper lets them key Java collections without lossy
 * charset conversion. The wrapped array is treated as immutable — callers must
 * not mutate an array after handing it to {@code Bytes}.
 */
public final class Bytes {

    private final byte[] data;
    private final int hash;

    /**
     * Wraps the given array. Ownership is transferred; do not mutate {@code data}
     * afterwards.
     *
     * @param data the bytes to wrap
     */
    public Bytes(byte[] data) {
        this.data = data;
        this.hash = Arrays.hashCode(data);
    }

    /** @return the backing array (must not be mutated) */
    public byte[] array() {
        return data;
    }

    /** @return the length in bytes */
    public int length() {
        return data.length;
    }

    /**
     * Returns a defensive copy of the bytes, safe to mutate.
     *
     * @return a fresh copy of the contents
     */
    public byte[] copy() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes other && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** @return the contents decoded as UTF-8, for logging/debugging only */
    @Override
    public String toString() {
        return new String(data, StandardCharsets.UTF_8);
    }
}
