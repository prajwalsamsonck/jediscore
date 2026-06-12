package dev.jediscore.datastructures;

import java.util.Arrays;

/**
 * A set of 64-bit integers kept sorted in a primitive {@code long[]}, with
 * binary-search membership.
 *
 * <p>This is the backing for a {@link SetValue}'s {@code intset} encoding — the
 * most compact representation Redis uses for sets all of whose members are
 * integers. Keeping it sorted gives O(log n) membership and ordered iteration
 * (which is why {@code SMEMBERS} on an intset comes back in numeric order).
 */
final class IntSet {

    private long[] values = new long[8];
    private int size;

    /** @return the number of integers */
    int size() {
        return size;
    }

    /**
     * @param value the integer
     * @return whether the value is present
     */
    boolean contains(long value) {
        return Arrays.binarySearch(values, 0, size, value) >= 0;
    }

    /**
     * Adds a value, keeping the array sorted.
     *
     * @param value the integer to add
     * @return {@code true} if added, {@code false} if already present
     */
    boolean add(long value) {
        int idx = Arrays.binarySearch(values, 0, size, value);
        if (idx >= 0) {
            return false;
        }
        int insertion = -idx - 1;
        if (size == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        System.arraycopy(values, insertion, values, insertion + 1, size - insertion);
        values[insertion] = value;
        size++;
        return true;
    }

    /**
     * Removes a value.
     *
     * @param value the integer to remove
     * @return {@code true} if it was present
     */
    boolean remove(long value) {
        int idx = Arrays.binarySearch(values, 0, size, value);
        if (idx < 0) {
            return false;
        }
        System.arraycopy(values, idx + 1, values, idx, size - idx - 1);
        size--;
        return true;
    }

    /**
     * @param index the position
     * @return the value at {@code index} (in ascending order)
     */
    long get(int index) {
        return values[index];
    }
}
