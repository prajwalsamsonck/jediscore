package dev.jediscore.datastructures;

import java.util.List;

/**
 * The primitive operations a list backing must provide, so {@link ListValue} can
 * implement the higher-level list commands (range, insert, remove, trim, …) once
 * against either encoding ({@link Listpack} when small, {@link Quicklist} when
 * large).
 *
 * <p>Indices are zero-based and must be in {@code [0, size())}; callers normalise
 * Redis's negative indices before calling.
 */
interface SequenceStore {

    /** @return the number of elements */
    int size();

    /**
     * @param index the element index
     * @return a copy of the element at {@code index}
     */
    byte[] get(int index);

    /**
     * @param index the element index
     * @param value the replacement value
     */
    void set(int index, byte[] value);

    /**
     * Inserts before {@code index} ({@code index == size()} appends).
     *
     * @param index insertion position
     * @param value the value to insert
     */
    void insert(int index, byte[] value);

    /**
     * @param index the index to remove
     */
    void removeAt(int index);

    /** @param value the value to prepend */
    void addFirst(byte[] value);

    /** @param value the value to append */
    void addLast(byte[] value);

    /** @return the removed head element (caller ensures non-empty) */
    byte[] removeFirst();

    /** @return the removed tail element (caller ensures non-empty) */
    byte[] removeLast();

    /** @return all elements, in order, as fresh arrays */
    List<byte[]> toList();
}
