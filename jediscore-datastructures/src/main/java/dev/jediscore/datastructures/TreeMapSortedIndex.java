package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * A {@link SortedIndex} backed by a {@link TreeSet} (itself a {@code TreeMap}).
 *
 * <p>This is the deliberately-simpler alternative to {@link SkipList}, kept so
 * the two can be benchmarked head-to-head. A balanced tree gives O(log n)
 * insert/delete like the skiplist, but it has <em>no order-statistics</em>:
 * computing a rank or fetching the n-th element requires an O(n) walk. That gap
 * — O(n) here vs O(log n) for the skiplist on {@code ZRANK}/{@code ZRANGE} — is
 * exactly why Redis (and JediCore) use a skiplist for the real thing.
 */
public final class TreeMapSortedIndex implements SortedIndex {

    private static final Comparator<ScoredMember> ORDER =
            Comparator.comparingDouble(ScoredMember::score)
                    .thenComparing(ScoredMember::member, SkipList::compare);

    private final TreeSet<ScoredMember> tree = new TreeSet<>(ORDER);

    @Override
    public void insert(double score, byte[] member) {
        tree.add(new ScoredMember(member, score));
    }

    @Override
    public boolean delete(double score, byte[] member) {
        return tree.remove(new ScoredMember(member, score));
    }

    @Override
    public long rank(double score, byte[] member) {
        ScoredMember probe = new ScoredMember(member, score);
        if (!tree.contains(probe)) {
            return -1;
        }
        return tree.headSet(probe, false).size();
    }

    @Override
    public ScoredMember getByRank(long rank) {
        if (rank < 0 || rank >= tree.size()) {
            return null;
        }
        long i = 0;
        for (ScoredMember m : tree) {
            if (i++ == rank) {
                return m;
            }
        }
        return null;
    }

    @Override
    public List<ScoredMember> rangeByRank(long start, long stop) {
        List<ScoredMember> out = new ArrayList<>();
        long i = 0;
        for (ScoredMember m : tree) {
            if (i > stop) {
                break;
            }
            if (i >= start) {
                out.add(m);
            }
            i++;
        }
        return out;
    }

    @Override
    public List<ScoredMember> ascending() {
        return new ArrayList<>(tree);
    }

    @Override
    public int size() {
        return tree.size();
    }
}
