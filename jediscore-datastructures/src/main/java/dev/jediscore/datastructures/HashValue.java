package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Redis hash: an ordered map of binary field → binary value.
 *
 * <p><strong>Encoding.</strong> It starts {@code listpack}-encoded (field/value
 * pairs packed into one {@link Listpack}) and converts to a {@code hashtable}
 * once it exceeds {@code hash-max-listpack-entries} pairs or any field/value
 * exceeds {@code hash-max-listpack-value} bytes — the same dual encoding Redis
 * uses. Conversion is one-way (Redis never shrinks back), so {@code OBJECT
 * ENCODING} is stable once it reports {@code hashtable}.
 *
 * <p>Insertion order is preserved in both encodings (the hashtable form uses a
 * {@link LinkedHashMap}); callers that compare against real Redis must not rely
 * on field order for the {@code hashtable} encoding, where Redis's order is
 * unspecified.
 */
public final class HashValue extends RedisValue {

    private final int maxEntries;
    private final int maxValue;

    // Exactly one of these is non-null at any time.
    private Listpack listpack;
    private LinkedHashMap<Bytes, byte[]> table;

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
     * @return {@code true} if the field was newly created, {@code false} if it
     *         already existed and was overwritten
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
                table.put(new Bytes(field.clone()), value.clone());
                return true;
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
            listpack.removeAt(idx + 1); // value
            listpack.removeAt(idx);     // field
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

    /** @return the fields, in iteration order, as fresh byte arrays */
    public List<byte[]> fields() {
        List<byte[]> out = new ArrayList<>(size());
        if (listpack != null) {
            List<byte[]> all = listpack.toList();
            for (int i = 0; i < all.size(); i += 2) {
                out.add(all.get(i));
            }
        } else {
            for (Bytes k : table.keySet()) {
                out.add(k.copy());
            }
        }
        return out;
    }

    /** @return the values, in iteration order, as fresh byte arrays */
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
        for (Map.Entry<Bytes, byte[]> e : table.entrySet()) {
            out.add(e.getKey().copy());
            out.add(e.getValue().clone());
        }
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
        LinkedHashMap<Bytes, byte[]> t = new LinkedHashMap<>();
        List<byte[]> flat = listpack.toList();
        for (int i = 0; i < flat.size(); i += 2) {
            t.put(new Bytes(flat.get(i)), flat.get(i + 1));
        }
        this.table = t;
        this.listpack = null;
    }
}
