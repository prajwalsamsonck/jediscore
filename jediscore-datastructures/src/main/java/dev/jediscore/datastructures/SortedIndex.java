package dev.jediscore.datastructures;

import java.util.List;

/**
 * An ordered index over {@code (score, member)} pairs, ordered by score
 * ascending and then by member bytes ascending.
 *
 * <p>Two implementations exist: {@link SkipList} (the real one, with O(log n)
 * rank via skip-pointer spans — this is what a sorted set actually uses) and
 * {@link TreeMapSortedIndex} (a simpler {@code TreeSet}-backed alternative kept
 * for benchmarking the skiplist against). Membership/score lookup is <em>not</em>
 * part of this interface — the sorted set keeps a separate member→score map for
 * that — so the index only has to maintain order and answer rank/range queries.
 */
public interface SortedIndex {

    /**
     * Inserts a pair. The caller guarantees the {@code (score, member)} is not
     * already present.
     *
     * @param score  the score
     * @param member the member
     */
    void insert(double score, byte[] member);

    /**
     * Removes a pair.
     *
     * @param score  the score
     * @param member the member
     * @return {@code true} if it was present
     */
    boolean delete(double score, byte[] member);

    /**
     * Returns the 0-based rank (position in ascending order) of a pair.
     *
     * @param score  the score
     * @param member the member
     * @return the rank, or {@code -1} if absent
     */
    long rank(double score, byte[] member);

    /**
     * Returns the element at a 0-based rank.
     *
     * @param rank the rank
     * @return the element, or {@code null} if out of range
     */
    ScoredMember getByRank(long rank);

    /**
     * Returns the inclusive ascending slice {@code [start, stop]} (already
     * clamped to valid bounds by the caller).
     *
     * @param start the start rank (0-based, inclusive)
     * @param stop  the stop rank (0-based, inclusive)
     * @return the elements in ascending order
     */
    List<ScoredMember> rangeByRank(long start, long stop);

    /** @return all elements in ascending order */
    List<ScoredMember> ascending();

    /** @return the number of elements */
    int size();
}
