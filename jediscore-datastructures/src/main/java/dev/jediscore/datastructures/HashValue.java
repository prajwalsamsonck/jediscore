package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A Redis hash: a map of binary field → binary value.
 *
 * <p><strong>Encoding.</strong> It starts {@code listpack}-encoded (field/value
 * pairs packed into one {@link Listpack}) and converts to a {@code hashtable}
 * (a {@link Dict}) once it exceeds {@code hash-max-listpack-entries} pairs or any
 * field/value exceeds {@code hash-max-listpack-value} bytes. Conversion is
 * one-way. The hashtable form uses {@link Dict} so {@code HSCAN} can iterate it
 * with the bucket cursor; iteration order in that encoding is unspecified, as in
 * Redis.
 */
public final class HashValue extends RedisValue {

    private final int maxEntries;
    private final int maxValue;

    // Exactly one of these is non-null at any time.
    private Listpack listpack;
    private Dict<byte[]> table;

    /**
     * Creates an empty, listpack-encoded hash.
     *
     * @param maxEntries the pair-count threshold for converting to a hashtable
     * @param maxValue   the field/value byte-length threshold for converting
     */
    public HashValue(int maxEntries, int maxValue) {
        this.maxEntries = maxEntries;
        this.maxValue = maxValue;
        this.listpack = new Listpack();
    }

    @Override
    public RedisType type() {
        return RedisType.HASH;
    }

    @Override
    public String encoding() {
        return listpack != null ? "listpack" : "hashtable";
    }

    /** @return the number of field/value pairs */
    public int size() {
        return listpack != null ? listpack.count() / 2 : table.size();
    }

    /**
     * Returns the value for a field.
     *
     * @param field the field
     * @return the value bytes, or {@code null} if the field is absent
     */
    public byte[] get(byte[] field) {
        if (listpack != null) {
            int idx = listpack.indexOf(field, 0, 2);
            return idx < 0 ? null : listpack.get(idx + 1);
        }
        return table.get(new Bytes(field));
    }

    /**
     * Sets a field to a value.
     *
     * @param field the field
     * @param value the value
     * @return {@code true} if the field was newly created
     */
    public boolean put(byte[] field, byte[] value) {
        if (listpack != null) {
            int idx = listpack.indexOf(field, 0, 2);
            if (idx >= 0) {
                listpack.set(idx + 1, value);
                return false;
            }
            if (shouldConvert(field.length, value.length, 1)) {
                convertToTable();
                return table.put(new Bytes(field.clone()), value.clone()) == null;
            }
            listpack.add(field);
            listpack.add(value);
            return true;
        }
        return table.put(new Bytes(field.clone()), value.clone()) == null;
    }

    /**
     * Removes a field.
     *
     * @param field the field
     * @return {@code true} if a field was removed
     */
    public boolean remove(byte[] field) {
        if (listpack != null) {
            int idx = listpack.indexOf(field, 0, 2);
            if (idx < 0) {
                return false;
            }
            listpack.removeAt(idx + 1);
            listpack.removeAt(idx);
            return true;
        }
        return table.remove(new Bytes(field)) != null;
    }

    /**
     * Tests whether a field exists.
     *
     * @param field the field
     * @return {@code true} if present
     */
    public boolean contains(byte[] field) {
        if (listpack != null) {
            return listpack.indexOf(field, 0, 2) >= 0;
        }
        return table.containsKey(new Bytes(field));
    }

    /** @return the fields as fresh byte arrays */
    public List<byte[]> fields() {
        List<byte[]> out = new ArrayList<>(size());
        if (listpack != null) {
            List<byte[]> all = listpack.toList();
            for (int i = 0; i < all.size(); i += 2) {
                out.add(all.get(i));
            }
        } else {
            for (Bytes k : table.keys()) {
                out.add(k.copy());
            }
        }
        return out;
    }

    /** @return the values as fresh byte arrays */
    public List<byte[]> values() {
        List<byte[]> out = new ArrayList<>(size());
        if (listpack != null) {
            List<byte[]> all = listpack.toList();
            for (int i = 1; i < all.size(); i += 2) {
                out.add(all.get(i));
            }
        } else {
            for (byte[] v : table.values()) {
                out.add(v.clone());
            }
        }
        return out;
    }

    /** @return all field/value pairs flattened in order {@code [f0,v0,f1,v1,…]} */
    public List<byte[]> entriesFlattened() {
        if (listpack != null) {
            return listpack.toList();
        }
        List<byte[]> out = new ArrayList<>(table.size() * 2);
        table.forEach((k, v) -> {
            out.add(k.copy());
            out.add(v.clone());
        });
        return out;
    }

    /**
     * Returns the byte length of a field's value.
     *
     * @param field the field
     * @return the value length, or {@code 0} if the field is absent
     */
    public int valueLength(byte[] field) {
        byte[] v = get(field);
        return v == null ? 0 : v.length;
    }

    /**
     * Advances an {@code HSCAN} cursor. For the listpack encoding all pairs are
     * emitted at once and 0 is returned; for the hashtable encoding the bucket
     * cursor is used.
     *
     * @param cursor   the cursor
     * @param count    buckets to visit this call
     * @param consumer receives each field and value
     * @return the next cursor (0 when complete)
     */
    public long scan(long cursor, int count, BiConsumer<byte[], byte[]> consumer) {
        if (listpack != null) {
            List<byte[]> flat = listpack.toList();
            for (int i = 0; i < flat.size(); i += 2) {
                consumer.accept(flat.get(i), flat.get(i + 1));
            }
            return 0;
        }
        return table.scan(cursor, count, (k, v) -> consumer.accept(k.array(), v));
    }

    @Override
    public HashValue deepCopy() {
        HashValue copy = new HashValue(maxEntries, maxValue);
        List<byte[]> flat = entriesFlattened();
        for (int i = 0; i < flat.size(); i += 2) {
            copy.put(flat.get(i), flat.get(i + 1));
        }
        return copy;
    }

    private boolean shouldConvert(int fieldLen, int valueLen, int addedPairs) {
        return (size() + addedPairs) > maxEntries
                || fieldLen > maxValue
                || valueLen > maxValue;
    }

    private void convertToTable() {
        Dict<byte[]> t = new Dict<>();
        List<byte[]> flat = listpack.toList();
        for (int i = 0; i < flat.size(); i += 2) {
            t.put(new Bytes(flat.get(i)), flat.get(i + 1));
        }
        this.table = t;
        this.listpack = null;
    }
}
