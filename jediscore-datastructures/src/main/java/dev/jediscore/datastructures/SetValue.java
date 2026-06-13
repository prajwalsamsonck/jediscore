package dev.jediscore.datastructures;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * A Redis set: an unordered collection of unique binary members.
 *
 * <p><strong>Encoding (three-way).</strong> {@code intset} (all-integer, sorted
 * {@link IntSet}), {@code listpack} (small, mixed), or {@code hashtable} (a
 * {@link Dict}). A set begins as an empty intset and migrates one-way as members
 * are added. The hashtable form uses {@link Dict} so {@code SSCAN} can iterate it
 * with the bucket cursor.
 */
public final class SetValue extends RedisValue {

    private final int maxIntset;
    private final int maxListpack;
    private final int maxValue;

    private IntSet intset = new IntSet();
    private Listpack listpack;
    private Dict<Boolean> table;

    /**
     * Creates an empty set.
     *
     * @param maxIntset   max members for the intset encoding
     * @param maxListpack max members for the listpack encoding
     * @param maxValue    max member byte-length for the listpack encoding
     */
    public SetValue(int maxIntset, int maxListpack, int maxValue) {
        this.maxIntset = maxIntset;
        this.maxListpack = maxListpack;
        this.maxValue = maxValue;
    }

    @Override
    public RedisType type() {
        return RedisType.SET;
    }

    @Override
    public String encoding() {
        if (intset != null) {
            return "intset";
        }
        return listpack != null ? "listpack" : "hashtable";
    }

    /** @return the number of members */
    public int size() {
        if (intset != null) {
            return intset.size();
        }
        return listpack != null ? listpack.count() : table.size();
    }

    /**
     * Adds a member.
     *
     * @param member the member
     * @return {@code true} if newly added
     */
    public boolean add(byte[] member) {
        if (intset != null) {
            Long asLong = asCanonicalLong(member);
            if (asLong != null) {
                if (intset.contains(asLong)) {
                    return false;
                }
                if (intset.size() >= maxIntset) {
                    migrateFromIntset(member.length);
                    return addToListpackOrTable(member);
                }
                return intset.add(asLong);
            }
            migrateFromIntset(member.length);
            return addToListpackOrTable(member);
        }
        return addToListpackOrTable(member);
    }

    /**
     * Removes a member.
     *
     * @param member the member
     * @return {@code true} if it was present
     */
    public boolean remove(byte[] member) {
        if (intset != null) {
            Long asLong = asCanonicalLong(member);
            return asLong != null && intset.remove(asLong);
        }
        if (listpack != null) {
            int idx = listpack.indexOf(member, 0, 1);
            if (idx < 0) {
                return false;
            }
            listpack.removeAt(idx);
            return true;
        }
        return table.remove(new Bytes(member)) != null;
    }

    /**
     * @param member the member
     * @return whether the member is present
     */
    public boolean contains(byte[] member) {
        if (intset != null) {
            Long asLong = asCanonicalLong(member);
            return asLong != null && intset.contains(asLong);
        }
        if (listpack != null) {
            return listpack.indexOf(member, 0, 1) >= 0;
        }
        return table.containsKey(new Bytes(member));
    }

    /** @return all members as fresh byte arrays */
    public List<byte[]> members() {
        List<byte[]> out = new ArrayList<>(size());
        if (intset != null) {
            for (int i = 0; i < intset.size(); i++) {
                out.add(Long.toString(intset.get(i)).getBytes(StandardCharsets.US_ASCII));
            }
        } else if (listpack != null) {
            out.addAll(listpack.toList());
        } else {
            for (Bytes b : table.keys()) {
                out.add(b.copy());
            }
        }
        return out;
    }

    /**
     * Returns a uniformly random member without removing it.
     *
     * @return a random member, or {@code null} if empty
     */
    public byte[] randomMember() {
        int n = size();
        if (n == 0) {
            return null;
        }
        return memberAt(ThreadLocalRandom.current().nextInt(n));
    }

    /**
     * Removes and returns a uniformly random member.
     *
     * @return the removed member, or {@code null} if empty
     */
    public byte[] popRandom() {
        int n = size();
        if (n == 0) {
            return null;
        }
        byte[] member = memberAt(ThreadLocalRandom.current().nextInt(n));
        remove(member);
        return member;
    }

    /**
     * Advances an {@code SSCAN} cursor. The compact encodings emit all members at
     * once (returning 0); the hashtable encoding uses the bucket cursor.
     *
     * @param cursor   the cursor
     * @param count    buckets to visit this call
     * @param consumer receives each member
     * @return the next cursor (0 when complete)
     */
    public long scan(long cursor, int count, Consumer<byte[]> consumer) {
        if (table == null) {
            for (byte[] m : members()) {
                consumer.accept(m);
            }
            return 0;
        }
        return table.scan(cursor, count, (k, v) -> consumer.accept(k.array()));
    }

    @Override
    public SetValue deepCopy() {
        SetValue copy = new SetValue(maxIntset, maxListpack, maxValue);
        for (byte[] m : members()) {
            copy.add(m);
        }
        return copy;
    }

    private byte[] memberAt(int index) {
        if (intset != null) {
            return Long.toString(intset.get(index)).getBytes(StandardCharsets.US_ASCII);
        }
        if (listpack != null) {
            return listpack.get(index);
        }
        int i = 0;
        for (Bytes b : table.keys()) {
            if (i++ == index) {
                return b.copy();
            }
        }
        return null;
    }

    private boolean addToListpackOrTable(byte[] member) {
        if (listpack != null) {
            if (listpack.indexOf(member, 0, 1) >= 0) {
                return false;
            }
            if (listpack.count() + 1 > maxListpack || member.length > maxValue) {
                convertListpackToTable();
                return table.put(new Bytes(member.clone()), Boolean.TRUE) == null;
            }
            listpack.add(member);
            return true;
        }
        return table.put(new Bytes(member.clone()), Boolean.TRUE) == null;
    }

    private void migrateFromIntset(int newMemberLength) {
        boolean toListpack = (intset.size() + 1 <= maxListpack) && (newMemberLength <= maxValue);
        if (toListpack) {
            listpack = new Listpack();
            for (int i = 0; i < intset.size(); i++) {
                listpack.add(Long.toString(intset.get(i)).getBytes(StandardCharsets.US_ASCII));
            }
        } else {
            table = new Dict<>();
            for (int i = 0; i < intset.size(); i++) {
                table.put(new Bytes(Long.toString(intset.get(i)).getBytes(StandardCharsets.US_ASCII)),
                        Boolean.TRUE);
            }
        }
        intset = null;
    }

    private void convertListpackToTable() {
        table = new Dict<>();
        for (byte[] e : listpack.toList()) {
            table.put(new Bytes(e), Boolean.TRUE);
        }
        listpack = null;
    }

    private static Long asCanonicalLong(byte[] member) {
        if (!StringValue.isCanonicalLong(member)) {
            return null;
        }
        return Long.parseLong(new String(member, StandardCharsets.US_ASCII));
    }
}
