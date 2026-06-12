package dev.jediscore.datastructures;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A Redis set: an unordered collection of unique binary members.
 *
 * <p><strong>Encoding (three-way, like Redis).</strong>
 * <ul>
 *   <li>{@code intset} — every member is an integer and the count is within
 *       {@code set-max-intset-entries}; stored as a sorted {@link IntSet}.</li>
 *   <li>{@code listpack} — small and not all-integer; stored in a {@link Listpack}.</li>
 *   <li>{@code hashtable} — anything larger; stored in a {@link LinkedHashSet}.</li>
 * </ul>
 * A set begins as an (empty) intset and migrates as members are added: an intset
 * that gains a non-integer member, or grows past its limit, becomes a listpack
 * or hashtable; a listpack that grows past its limit becomes a hashtable.
 * Conversions are one-way.
 */
public final class SetValue extends RedisValue {

    private final int maxIntset;
    private final int maxListpack;
    private final int maxValue;

    private IntSet intset = new IntSet();
    private Listpack listpack;
    private LinkedHashSet<Bytes> table;

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
            // A non-integer member forces the intset to a richer encoding.
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
        return table.remove(new Bytes(member));
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
        return table.contains(new Bytes(member));
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
            for (Bytes b : table) {
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
        int idx = ThreadLocalRandom.current().nextInt(n);
        byte[] member = memberAt(idx);
        remove(member);
        return member;
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
        for (Bytes b : table) {
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
                return table.add(new Bytes(member.clone()));
            }
            listpack.add(member);
            return true;
        }
        return table.add(new Bytes(member.clone()));
    }

    /**
     * Migrates an intset to a listpack (if the result still fits) or a hashtable,
     * carrying the existing integer members across as their decimal text.
     */
    private void migrateFromIntset(int newMemberLength) {
        boolean toListpack = (intset.size() + 1 <= maxListpack) && (newMemberLength <= maxValue);
        if (toListpack) {
            listpack = new Listpack();
            for (int i = 0; i < intset.size(); i++) {
                listpack.add(Long.toString(intset.get(i)).getBytes(StandardCharsets.US_ASCII));
            }
        } else {
            table = new LinkedHashSet<>();
            for (int i = 0; i < intset.size(); i++) {
                table.add(new Bytes(Long.toString(intset.get(i)).getBytes(StandardCharsets.US_ASCII)));
            }
        }
        intset = null;
    }

    private void convertListpackToTable() {
        table = new LinkedHashSet<>();
        for (byte[] e : listpack.toList()) {
            table.add(new Bytes(e));
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
