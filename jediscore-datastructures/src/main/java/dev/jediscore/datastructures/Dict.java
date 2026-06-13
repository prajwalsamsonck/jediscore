package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A chained hash table keyed by {@link Bytes}, with a power-of-two bucket count
 * and a {@code SCAN} cursor that tolerates table growth between calls.
 *
 * <p>This exists because Redis's {@code SCAN} family relies on the dict's bucket
 * layout, which {@code java.util.HashMap} does not expose. The cursor uses
 * Redis's <em>reverse-binary increment</em>: a bucket index is iterated, then the
 * cursor's high bits (above the current mask) are set, the whole value is
 * bit-reversed, incremented, and reversed back. The effect is that when the table
 * doubles between two {@code SCAN} calls, every bucket that the old cursor would
 * still have visited maps to buckets the new cursor will visit — so a full
 * iteration returns every element present for the whole scan, never missing one,
 * even as keys are added or removed in between.
 *
 * <p>Resizing is <em>synchronous</em> (the whole table is rehashed at once when it
 * fills), so a scan only ever sees a single table; that keeps the cursor math the
 * simple single-table case while preserving the guarantee. The table only grows
 * (it is never shrunk), which costs some memory after large deletions but never
 * affects correctness.
 *
 * <p>Single-threaded, like everything reached from the command loop.
 *
 * @param <V> the value type
 */
public final class Dict<V> {

    private static final class Node<V> {
        final Bytes key;
        V value;
        Node<V> next;

        Node(Bytes key, V value, Node<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Node<V>[] table;
    private int used;

    /** Creates an empty dict. */
    @SuppressWarnings("unchecked")
    public Dict() {
        this.table = (Node<V>[]) new Node[4];
    }

    /** @return the number of entries */
    public int size() {
        return used;
    }

    /** @return whether the dict is empty */
    public boolean isEmpty() {
        return used == 0;
    }

    /**
     * Returns the value for a key.
     *
     * @param key the key
     * @return the value, or {@code null} if absent
     */
    public V get(Bytes key) {
        Node<V> node = table[index(key, table.length)];
        while (node != null) {
            if (node.key.equals(key)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    /**
     * @param key the key
     * @return whether the key is present
     */
    public boolean containsKey(Bytes key) {
        return nodeFor(key) != null;
    }

    /**
     * Inserts or updates a mapping.
     *
     * @param key   the key
     * @param value the value
     * @return the previous value, or {@code null} if the key is new
     */
    public V put(Bytes key, V value) {
        int idx = index(key, table.length);
        for (Node<V> node = table[idx]; node != null; node = node.next) {
            if (node.key.equals(key)) {
                V old = node.value;
                node.value = value;
                return old;
            }
        }
        table[idx] = new Node<>(key, value, table[idx]);
        used++;
        if (used >= table.length) {
            resize(table.length * 2);
        }
        return null;
    }

    /**
     * Removes a mapping.
     *
     * @param key the key
     * @return the removed value, or {@code null} if absent
     */
    public V remove(Bytes key) {
        int idx = index(key, table.length);
        Node<V> prev = null;
        for (Node<V> node = table[idx]; node != null; node = node.next) {
            if (node.key.equals(key)) {
                if (prev == null) {
                    table[idx] = node.next;
                } else {
                    prev.next = node.next;
                }
                used--;
                return node.value;
            }
            prev = node;
        }
        return null;
    }

    /** Removes all entries (and resets the table to its initial size). */
    @SuppressWarnings("unchecked")
    public void clear() {
        table = (Node<V>[]) new Node[4];
        used = 0;
    }

    /** @return a snapshot of all keys */
    public List<Bytes> keys() {
        List<Bytes> out = new ArrayList<>(used);
        for (Node<V> head : table) {
            for (Node<V> node = head; node != null; node = node.next) {
                out.add(node.key);
            }
        }
        return out;
    }

    /** @return a snapshot of all values */
    public List<V> values() {
        List<V> out = new ArrayList<>(used);
        for (Node<V> head : table) {
            for (Node<V> node = head; node != null; node = node.next) {
                out.add(node.value);
            }
        }
        return out;
    }

    /**
     * Visits every entry.
     *
     * @param consumer the visitor
     */
    public void forEach(BiConsumer<Bytes, V> consumer) {
        for (Node<V> head : table) {
            for (Node<V> node = head; node != null; node = node.next) {
                consumer.accept(node.key, node.value);
            }
        }
    }

    /**
     * Advances a {@code SCAN} cursor, visiting up to {@code count} buckets and
     * emitting all entries in them.
     *
     * @param cursor   the cursor (0 to start a new iteration)
     * @param count    the number of buckets to visit this call (a hint; ≥ 1)
     * @param consumer receives each visited entry
     * @return the next cursor, or 0 when the iteration is complete
     */
    public long scan(long cursor, int count, BiConsumer<Bytes, V> consumer) {
        if (used == 0) {
            return 0;
        }
        long v = cursor;
        int visited = 0;
        do {
            long mask = table.length - 1L;
            Node<V> node = table[(int) (v & mask)];
            while (node != null) {
                consumer.accept(node.key, node.value);
                node = node.next;
            }
            // Reverse-binary increment of the cursor (Redis dictScan).
            v |= ~mask;
            v = Long.reverse(v);
            v++;
            v = Long.reverse(v);
            visited++;
        } while (v != 0 && visited < count);
        return v;
    }

    private Node<V> nodeFor(Bytes key) {
        Node<V> node = table[index(key, table.length)];
        while (node != null) {
            if (node.key.equals(key)) {
                return node;
            }
            node = node.next;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void resize(int newCapacity) {
        Node<V>[] old = table;
        Node<V>[] fresh = (Node<V>[]) new Node[newCapacity];
        for (Node<V> head : old) {
            Node<V> node = head;
            while (node != null) {
                Node<V> next = node.next;
                int idx = index(node.key, newCapacity);
                node.next = fresh[idx];
                fresh[idx] = node;
                node = next;
            }
        }
        table = fresh;
    }

    private static int index(Bytes key, int capacity) {
        int h = key.hashCode();
        h ^= (h >>> 16); // spread, as java.util.HashMap does
        return h & (capacity - 1);
    }
}
