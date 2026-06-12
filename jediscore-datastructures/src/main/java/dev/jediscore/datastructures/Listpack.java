package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A compact, ordered sequence of binary entries packed into a single growable
 * {@code byte[]} arena.
 *
 * <p>This is JediCore's equivalent of Redis's <em>listpack</em>: instead of one
 * heap object per element (with its 16-byte header and pointer overhead), all
 * entries live contiguously in one array, which is dramatically cheaper for the
 * many small collections a real workload holds. Small hashes, lists, sets, and
 * sorted sets use it before converting to a full structure past configurable
 * thresholds — the same memory optimisation Redis makes.
 *
 * <p>Each entry is stored as a 4-byte big-endian length followed by its bytes.
 * Lookups scan from the front in O(n); that is intentional and fine, because a
 * listpack is only ever used while the collection is small (≤ a few hundred
 * entries). This is a simplified encoding: it is not byte-for-byte Redis's
 * listpack format (which uses varints and a reverse back-length), a distinction
 * that only matters for RDB wire compatibility and is revisited in the
 * persistence phase.
 *
 * <p>It implements {@link SequenceStore} so it can serve directly as the small
 * encoding of a list.
 */
public final class Listpack implements SequenceStore {

    private static final int HEADER = 4;

    private byte[] buffer;
    private int used;
    private int count;

    /** Creates an empty listpack. */
    public Listpack() {
        this.buffer = new byte[32];
        this.used = 0;
        this.count = 0;
    }

    /** @return the number of entries */
    public int count() {
        return count;
    }

    /** @return the number of bytes currently occupied by the arena */
    public int byteSize() {
        return used;
    }

    // ---- SequenceStore adapters --------------------------------------------

    @Override
    public int size() {
        return count;
    }

    @Override
    public void addFirst(byte[] value) {
        insert(0, value);
    }

    @Override
    public void addLast(byte[] value) {
        add(value);
    }

    @Override
    public byte[] removeFirst() {
        byte[] head = get(0);
        removeAt(0);
        return head;
    }

    @Override
    public byte[] removeLast() {
        int last = count - 1;
        byte[] tail = get(last);
        removeAt(last);
        return tail;
    }

    /**
     * Appends an entry to the end.
     *
     * @param entry the bytes to append (copied in)
     */
    public void add(byte[] entry) {
        ensureCapacity(used + HEADER + entry.length);
        writeEntryAt(used, entry);
        used += HEADER + entry.length;
        count++;
    }

    /**
     * Returns the entry at the given index.
     *
     * @param index zero-based entry index
     * @return a copy of the entry's bytes
     */
    public byte[] get(int index) {
        int offset = offsetOf(index);
        int len = readLen(offset);
        return Arrays.copyOfRange(buffer, offset + HEADER, offset + HEADER + len);
    }

    /**
     * Replaces the entry at the given index.
     *
     * @param index zero-based entry index
     * @param entry the replacement bytes
     */
    public void set(int index, byte[] entry) {
        removeAt(index);
        insert(index, entry);
    }

    /**
     * Inserts an entry before the given index ({@code index == count} appends).
     *
     * @param index insertion position
     * @param entry the bytes to insert
     */
    public void insert(int index, byte[] entry) {
        if (index == count) {
            add(entry);
            return;
        }
        int offset = offsetOf(index);
        int need = HEADER + entry.length;
        ensureCapacity(used + need);
        // Shift the tail right to open a gap, then write the new entry.
        System.arraycopy(buffer, offset, buffer, offset + need, used - offset);
        writeEntryAt(offset, entry);
        used += need;
        count++;
    }

    /**
     * Removes the entry at the given index.
     *
     * @param index zero-based entry index
     */
    public void removeAt(int index) {
        int offset = offsetOf(index);
        int len = readLen(offset);
        int entryBytes = HEADER + len;
        System.arraycopy(buffer, offset + entryBytes, buffer, offset, used - (offset + entryBytes));
        used -= entryBytes;
        count--;
    }

    /**
     * Finds the index of the first entry equal to {@code needle}, scanning from
     * {@code start} in steps of {@code step}. A {@code step} of 1 scans every
     * entry; a step of 2 (with {@code start} 0) scans hash/zset "keys" only.
     *
     * @param needle the bytes to find
     * @param start  the first index to examine
     * @param step   the stride between examined indices
     * @return the matching index, or {@code -1} if not found
     */
    public int indexOf(byte[] needle, int start, int step) {
        int offset = (start == 0) ? 0 : offsetOf(start);
        int index = start;
        while (index < count) {
            int len = readLen(offset);
            int dataStart = offset + HEADER;
            if (len == needle.length && Arrays.equals(buffer, dataStart, dataStart + len, needle, 0, needle.length)) {
                return index;
            }
            // Advance `step` entries.
            for (int s = 0; s < step && index < count; s++) {
                offset += HEADER + readLen(offset);
                index++;
            }
        }
        return -1;
    }

    /** @return all entries, in order, as fresh byte arrays */
    public List<byte[]> toList() {
        List<byte[]> out = new ArrayList<>(count);
        int offset = 0;
        for (int i = 0; i < count; i++) {
            int len = readLen(offset);
            out.add(Arrays.copyOfRange(buffer, offset + HEADER, offset + HEADER + len));
            offset += HEADER + len;
        }
        return out;
    }

    private int offsetOf(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("index " + index + " of " + count);
        }
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset += HEADER + readLen(offset);
        }
        return offset;
    }

    private int readLen(int offset) {
        return ((buffer[offset] & 0xff) << 24)
                | ((buffer[offset + 1] & 0xff) << 16)
                | ((buffer[offset + 2] & 0xff) << 8)
                | (buffer[offset + 3] & 0xff);
    }

    private void writeEntryAt(int offset, byte[] entry) {
        int len = entry.length;
        buffer[offset] = (byte) (len >>> 24);
        buffer[offset + 1] = (byte) (len >>> 16);
        buffer[offset + 2] = (byte) (len >>> 8);
        buffer[offset + 3] = (byte) len;
        System.arraycopy(entry, 0, buffer, offset + HEADER, len);
    }

    private void ensureCapacity(int needed) {
        if (needed <= buffer.length) {
            return;
        }
        int newSize = Math.max(buffer.length * 2, needed);
        buffer = Arrays.copyOf(buffer, newSize);
    }
}
