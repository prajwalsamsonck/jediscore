package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Redis list: an ordered sequence supporting O(1) operations at both ends.
 *
 * <p><strong>Encoding.</strong> Small lists use a single {@link Listpack}
 * ({@code listpack} encoding); once the element count exceeds
 * {@code list-max-listpack-size} or any element exceeds the value-size limit, the
 * list converts to a {@link Quicklist} ({@code quicklist} encoding). Conversion
 * is one-way, matching Redis.
 *
 * <p>All higher-level list commands (range, insert, remove, trim, position) are
 * implemented here once against the {@link SequenceStore} abstraction.
 */
public final class ListValue extends RedisValue {

    private static final int QUICKLIST_NODE_CAPACITY = 128;

    private final int maxListpackSize;
    private final int maxValueSize;

    private SequenceStore store = new Listpack();
    private boolean listpackEncoded = true;

    /**
     * Creates an empty list.
     *
     * @param maxListpackSize element-count threshold for converting to a quicklist
     * @param maxValueSize    element byte-length threshold for converting
     */
    public ListValue(int maxListpackSize, int maxValueSize) {
        this.maxListpackSize = maxListpackSize;
        this.maxValueSize = maxValueSize;
    }

    @Override
    public RedisType type() {
        return RedisType.LIST;
    }

    @Override
    public String encoding() {
        return listpackEncoded ? "listpack" : "quicklist";
    }

    /** @return the number of elements */
    public int size() {
        return store.size();
    }

    /** @return whether the list is empty */
    public boolean isEmpty() {
        return store.size() == 0;
    }

    /** @param value the element to prepend */
    public void pushHead(byte[] value) {
        maybeConvert(1, value.length);
        store.addFirst(value);
    }

    /** @param value the element to append */
    public void pushTail(byte[] value) {
        maybeConvert(1, value.length);
        store.addLast(value);
    }

    /** @return the removed head element, or {@code null} if empty */
    public byte[] popHead() {
        return isEmpty() ? null : store.removeFirst();
    }

    /** @return the removed tail element, or {@code null} if empty */
    public byte[] popTail() {
        return isEmpty() ? null : store.removeLast();
    }

    /**
     * Returns the element at a (possibly negative) index.
     *
     * @param index the index ({@code -1} is the last element)
     * @return the element, or {@code null} if out of range
     */
    public byte[] index(long index) {
        long i = normalize(index);
        if (i < 0 || i >= size()) {
            return null;
        }
        return store.get((int) i);
    }

    /**
     * Sets the element at a (possibly negative) index.
     *
     * @param index the index
     * @param value the new value
     * @return {@code true} on success, {@code false} if the index is out of range
     */
    public boolean set(long index, byte[] value) {
        long i = normalize(index);
        if (i < 0 || i >= size()) {
            return false;
        }
        maybeConvert(0, value.length);
        store.set((int) i, value);
        return true;
    }

    /**
     * Returns the inclusive range {@code [start, stop]} with Redis index
     * normalisation.
     *
     * @param start the start index
     * @param stop  the stop index
     * @return the elements in range (possibly empty)
     */
    public List<byte[]> range(long start, long stop) {
        int size = size();
        long s = start < 0 ? size + start : start;
        long e = stop < 0 ? size + stop : stop;
        if (s < 0) {
            s = 0;
        }
        if (e >= size) {
            e = size - 1;
        }
        if (s > e || size == 0) {
            return List.of();
        }
        List<byte[]> all = store.toList();
        return new ArrayList<>(all.subList((int) s, (int) e + 1));
    }

    /**
     * Inserts {@code value} before or after the first occurrence of {@code pivot}.
     *
     * @param before {@code true} to insert before the pivot, {@code false} after
     * @param pivot  the reference element
     * @param value  the element to insert
     * @return the new length, or {@code -1} if the pivot was not found
     */
    public int insert(boolean before, byte[] pivot, byte[] value) {
        int idx = indexOf(pivot);
        if (idx < 0) {
            return -1;
        }
        maybeConvert(1, value.length);
        store.insert(before ? idx : idx + 1, value);
        return size();
    }

    /**
     * Removes elements equal to {@code value}.
     *
     * @param count {@code >0} removes that many from the head, {@code <0} from the
     *              tail, {@code 0} removes all
     * @param value the value to remove
     * @return the number of elements removed
     */
    public int remove(long count, byte[] value) {
        List<byte[]> elements = store.toList();
        List<Integer> toRemove = new ArrayList<>();
        if (count >= 0) {
            long limit = count == 0 ? Long.MAX_VALUE : count;
            for (int i = 0; i < elements.size() && toRemove.size() < limit; i++) {
                if (Arrays.equals(elements.get(i), value)) {
                    toRemove.add(i);
                }
            }
        } else {
            long limit = -count;
            for (int i = elements.size() - 1; i >= 0 && toRemove.size() < limit; i--) {
                if (Arrays.equals(elements.get(i), value)) {
                    toRemove.add(i);
                }
            }
            toRemove.sort(null); // ascending for stable descending removal below
        }
        // Remove by index, highest first, so earlier indices stay valid.
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            store.removeAt(toRemove.get(i));
        }
        return toRemove.size();
    }

    /**
     * Trims the list to the inclusive range {@code [start, stop]}.
     *
     * @param start the start index
     * @param stop  the stop index
     */
    public void trim(long start, long stop) {
        List<byte[]> kept = range(start, stop);
        rebuild(kept);
    }

    /**
     * Finds positions of {@code element}.
     *
     * @param element    the element to find
     * @param rank       which match to start from: {@code 1} = first from head,
     *                   {@code -1} = first from tail (must be non-zero)
     * @param maxMatches the maximum number of matches to return
     * @param maxLen     the maximum number of comparisons ({@code 0} = unlimited)
     * @return the matching indices, in the order found
     */
    public List<Long> positions(byte[] element, long rank, long maxMatches, long maxLen) {
        List<byte[]> elements = store.toList();
        int size = elements.size();
        boolean forward = rank > 0;
        long skip = Math.abs(rank) - 1;
        long comparisons = 0;
        List<Long> found = new ArrayList<>();
        int i = forward ? 0 : size - 1;
        while (i >= 0 && i < size) {
            if (maxLen > 0 && comparisons >= maxLen) {
                break;
            }
            comparisons++;
            if (Arrays.equals(elements.get(i), element)) {
                if (skip > 0) {
                    skip--;
                } else {
                    found.add((long) i);
                    if (found.size() >= maxMatches) {
                        break;
                    }
                }
            }
            i += forward ? 1 : -1;
        }
        return found;
    }

    @Override
    public long estimateBytes() {
        long total = 48;
        for (byte[] e : store.toList()) {
            total += e.length + 16;
        }
        return total;
    }

    @Override
    public ListValue deepCopy() {
        ListValue copy = new ListValue(maxListpackSize, maxValueSize);
        for (byte[] e : store.toList()) {
            copy.pushTail(e);
        }
        if (!listpackEncoded && copy.listpackEncoded) {
            copy.convertToQuicklist();
        }
        return copy;
    }

    private int indexOf(byte[] value) {
        List<byte[]> all = store.toList();
        for (int i = 0; i < all.size(); i++) {
            if (Arrays.equals(all.get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    private long normalize(long index) {
        return index < 0 ? size() + index : index;
    }

    private void maybeConvert(int extra, int valueLength) {
        if (listpackEncoded && (size() + extra > maxListpackSize || valueLength > maxValueSize)) {
            convertToQuicklist();
        }
    }

    private void convertToQuicklist() {
        Quicklist quicklist = new Quicklist(QUICKLIST_NODE_CAPACITY);
        for (byte[] e : store.toList()) {
            quicklist.addLast(e);
        }
        store = quicklist;
        listpackEncoded = false;
    }

    private void rebuild(List<byte[]> elements) {
        store = listpackEncoded ? new Listpack() : new Quicklist(QUICKLIST_NODE_CAPACITY);
        for (byte[] e : elements) {
            store.addLast(e);
        }
    }
}
