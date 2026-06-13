package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A Redis sorted set: members ordered by score, with O(1) member→score lookup.
 *
 * <p><strong>Encoding.</strong> Small sorted sets use a single {@link Listpack}
 * of {@code [member, score]} pairs kept in sorted order ({@code listpack}
 * encoding). Once the set exceeds {@code zset-max-listpack-entries} members or a
 * member exceeds the value-size limit, it converts to the {@code skiplist}
 * encoding: a {@link HashMap} member→score (for {@code ZSCORE}) alongside a
 * {@link SkipList} (for ordered rank/range queries). Conversion is one-way.
 *
 * <p>This class exposes the efficient primitives (score, put, remove, rank by
 * member, element by rank, ascending iteration); score- and lex-range filtering
 * is layered on top in the command implementations.
 */
public final class ZSetValue extends RedisValue {

    private final int maxListpackEntries;
    private final int maxValue;

    // listpack encoding: pairs [member0, score0, member1, score1, …] sorted by (score, member).
    private Listpack listpack;
    // skiplist encoding:
    private Dict<Double> dict;
    private SkipList index;

    /**
     * Creates an empty, listpack-encoded sorted set.
     *
     * @param maxListpackEntries member-count threshold for converting to a skiplist
     * @param maxValue           member byte-length threshold for converting
     */
    public ZSetValue(int maxListpackEntries, int maxValue) {
        this.maxListpackEntries = maxListpackEntries;
        this.maxValue = maxValue;
        this.listpack = new Listpack();
    }

    @Override
    public RedisType type() {
        return RedisType.ZSET;
    }

    @Override
    public String encoding() {
        return listpack != null ? "listpack" : "skiplist";
    }

    /** @return the number of members */
    public int size() {
        return listpack != null ? listpack.count() / 2 : dict.size();
    }

    /**
     * Returns a member's score.
     *
     * @param member the member
     * @return the score, or {@code null} if absent
     */
    public Double score(byte[] member) {
        if (listpack != null) {
            int idx = listpack.indexOf(member, 0, 2);
            return idx < 0 ? null : bytesToScore(listpack.get(idx + 1));
        }
        return dict.get(new Bytes(member));
    }

    /**
     * Adds a member or updates its score.
     *
     * @param member the member
     * @param score  the score
     * @return the previous score, or {@code null} if the member is new
     */
    public Double put(byte[] member, double score) {
        Double old = score(member);
        if (old == null) {
            if (listpack != null && (size() + 1 > maxListpackEntries || member.length > maxValue)) {
                convertToSkiplist();
            }
            addNew(member, score);
            return null;
        }
        if (old != score) {
            updateScore(member, old, score);
        }
        return old;
    }

    /**
     * Removes a member.
     *
     * @param member the member
     * @return {@code true} if it was present
     */
    public boolean remove(byte[] member) {
        if (listpack != null) {
            int idx = listpack.indexOf(member, 0, 2);
            if (idx < 0) {
                return false;
            }
            listpack.removeAt(idx + 1);
            listpack.removeAt(idx);
            return true;
        }
        Double old = dict.remove(new Bytes(member));
        if (old == null) {
            return false;
        }
        index.delete(old, member);
        return true;
    }

    /**
     * Returns a member's 0-based rank.
     *
     * @param member  the member
     * @param reverse {@code true} for rank from the high-score end
     * @return the rank, or {@code -1} if absent
     */
    public long rank(byte[] member, boolean reverse) {
        Double s = score(member);
        if (s == null) {
            return -1;
        }
        long asc;
        if (listpack != null) {
            asc = listpack.indexOf(member, 0, 2) / 2;
        } else {
            asc = index.rank(s, member);
        }
        return reverse ? size() - 1 - asc : asc;
    }

    /**
     * Returns members by index range (inclusive), with Redis index normalisation.
     *
     * @param start   the start index
     * @param stop    the stop index
     * @param reverse {@code true} to interpret indices from the high-score end and
     *                return results in descending order
     * @return the elements in the requested order
     */
    public List<ScoredMember> rangeByIndex(long start, long stop, boolean reverse) {
        int n = size();
        if (n == 0) {
            return List.of();
        }
        if (start < 0) {
            start += n;
        }
        if (stop < 0) {
            stop += n;
        }
        if (start < 0) {
            start = 0;
        }
        if (stop >= n) {
            stop = n - 1;
        }
        if (start > stop) {
            return List.of();
        }
        if (!reverse) {
            return rangeByRankAsc(start, stop);
        }
        List<ScoredMember> asc = rangeByRankAsc(n - 1 - stop, n - 1 - start);
        List<ScoredMember> out = new ArrayList<>(asc);
        java.util.Collections.reverse(out);
        return out;
    }

    /** @return all members in ascending {@code (score, member)} order */
    public List<ScoredMember> ascending() {
        if (listpack != null) {
            List<ScoredMember> out = new ArrayList<>(size());
            List<byte[]> flat = listpack.toList();
            for (int i = 0; i < flat.size(); i += 2) {
                out.add(new ScoredMember(flat.get(i), bytesToScore(flat.get(i + 1))));
            }
            return out;
        }
        return index.ascending();
    }

    /** @return the lowest-scoring element removed, or {@code null} if empty */
    public ScoredMember popMin() {
        return pop(0);
    }

    /** @return the highest-scoring element removed, or {@code null} if empty */
    public ScoredMember popMax() {
        return pop(size() - 1);
    }

    /**
     * Advances a {@code ZSCAN} cursor. The listpack encoding emits all members at
     * once (returning 0); the skiplist encoding scans the member→score dict with
     * the bucket cursor.
     *
     * @param cursor   the cursor
     * @param count    buckets to visit this call
     * @param consumer receives each member and its score
     * @return the next cursor (0 when complete)
     */
    public long scan(long cursor, int count, BiConsumer<byte[], Double> consumer) {
        if (listpack != null) {
            List<byte[]> flat = listpack.toList();
            for (int i = 0; i < flat.size(); i += 2) {
                consumer.accept(flat.get(i), bytesToScore(flat.get(i + 1)));
            }
            return 0;
        }
        return dict.scan(cursor, count, (k, v) -> consumer.accept(k.array(), v));
    }

    @Override
    public long estimateBytes() {
        long total = 64;
        for (ScoredMember m : ascending()) {
            total += m.member().length + 16 + 8;
        }
        return total;
    }

    @Override
    public ZSetValue deepCopy() {
        ZSetValue copy = new ZSetValue(maxListpackEntries, maxValue);
        for (ScoredMember m : ascending()) {
            copy.put(m.member(), m.score());
        }
        return copy;
    }

    // ---- internals ----------------------------------------------------------

    private ScoredMember pop(int rank) {
        if (size() == 0) {
            return null;
        }
        ScoredMember m = getByRank(rank);
        remove(m.member());
        return m;
    }

    private ScoredMember getByRank(int rank) {
        if (listpack != null) {
            return new ScoredMember(listpack.get(rank * 2), bytesToScore(listpack.get(rank * 2 + 1)));
        }
        return index.getByRank(rank);
    }

    private List<ScoredMember> rangeByRankAsc(long start, long stop) {
        if (listpack != null) {
            List<ScoredMember> out = new ArrayList<>();
            for (long r = start; r <= stop; r++) {
                out.add(getByRank((int) r));
            }
            return out;
        }
        return index.rangeByRank(start, stop);
    }

    private void addNew(byte[] member, double score) {
        if (listpack != null) {
            int pos = listpackInsertPosition(score, member);
            listpack.insert(pos * 2, member);
            listpack.insert(pos * 2 + 1, scoreToBytes(score));
        } else {
            dict.put(new Bytes(member.clone()), score);
            index.insert(score, member);
        }
    }

    private void updateScore(byte[] member, double oldScore, double newScore) {
        if (listpack != null) {
            int idx = listpack.indexOf(member, 0, 2);
            listpack.removeAt(idx + 1);
            listpack.removeAt(idx);
            int pos = listpackInsertPosition(newScore, member);
            listpack.insert(pos * 2, member);
            listpack.insert(pos * 2 + 1, scoreToBytes(newScore));
        } else {
            index.delete(oldScore, member);
            index.insert(newScore, member);
            dict.put(new Bytes(member), newScore);
        }
    }

    /** Finds the pair position at which {@code (score, member)} sorts. */
    private int listpackInsertPosition(double score, byte[] member) {
        int pairs = listpack.count() / 2;
        for (int p = 0; p < pairs; p++) {
            double s = bytesToScore(listpack.get(p * 2 + 1));
            if (s > score || (s == score && SkipList.compare(listpack.get(p * 2), member) > 0)) {
                return p;
            }
        }
        return pairs;
    }

    private void convertToSkiplist() {
        dict = new Dict<>();
        index = new SkipList();
        List<byte[]> flat = listpack.toList();
        for (int i = 0; i < flat.size(); i += 2) {
            byte[] member = flat.get(i);
            double score = bytesToScore(flat.get(i + 1));
            dict.put(new Bytes(member), score);
            index.insert(score, member);
        }
        listpack = null;
    }

    private static byte[] scoreToBytes(double score) {
        long bits = Double.doubleToRawLongBits(score);
        return new byte[] {
                (byte) (bits >>> 56), (byte) (bits >>> 48), (byte) (bits >>> 40), (byte) (bits >>> 32),
                (byte) (bits >>> 24), (byte) (bits >>> 16), (byte) (bits >>> 8), (byte) bits
        };
    }

    private static double bytesToScore(byte[] b) {
        long bits = ((long) (b[0] & 0xff) << 56) | ((long) (b[1] & 0xff) << 48)
                | ((long) (b[2] & 0xff) << 40) | ((long) (b[3] & 0xff) << 32)
                | ((long) (b[4] & 0xff) << 24) | ((long) (b[5] & 0xff) << 16)
                | ((long) (b[6] & 0xff) << 8) | (b[7] & 0xff);
        return Double.longBitsToDouble(bits);
    }
}
