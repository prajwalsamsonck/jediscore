package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A skip list ordered by {@code (score, member)}, ported from Redis's
 * {@code zskiplist}.
 *
 * <p>This is the real ordered structure behind a sorted set's {@code skiplist}
 * encoding. Each node carries, per level, a forward pointer and a <em>span</em>
 * (the number of nodes that pointer skips); accumulating spans during a search
 * yields a node's rank in O(log n), which is what makes {@code ZRANK} and
 * index-based {@code ZRANGE} fast. A {@code TreeMap}/{@code TreeSet} has no
 * order-statistics, so it cannot do this in better than O(n) — see
 * {@link TreeMapSortedIndex}, kept only to benchmark against.
 *
 * <p>Ordering is by score ascending, ties broken by unsigned lexicographic
 * comparison of the member bytes — exactly Redis's rule.
 */
public final class SkipList implements SortedIndex {

    private static final int MAX_LEVEL = 32;
    private static final double P = 0.25;

    private static final class Node {
        final byte[] member;
        double score;
        Node backward;
        final Node[] forward;
        final long[] span;

        Node(int level, double score, byte[] member) {
            this.score = score;
            this.member = member;
            this.forward = new Node[level];
            this.span = new long[level];
        }
    }

    private final Node head = new Node(MAX_LEVEL, 0, null);
    private Node tail;
    private int level = 1;
    private long length;

    @Override
    public int size() {
        return (int) length;
    }

    @Override
    public void insert(double score, byte[] member) {
        Node[] update = new Node[MAX_LEVEL];
        long[] rank = new long[MAX_LEVEL];
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null && precedes(x.forward[i], score, member)) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                rank[i] = 0;
                update[i] = head;
                update[i].span[i] = length;
            }
            level = newLevel;
        }
        x = new Node(newLevel, score, member);
        for (int i = 0; i < newLevel; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        for (int i = newLevel; i < level; i++) {
            update[i].span[i]++;
        }
        x.backward = (update[0] == head) ? null : update[0];
        if (x.forward[0] != null) {
            x.forward[0].backward = x;
        } else {
            tail = x;
        }
        length++;
    }

    @Override
    public boolean delete(double score, byte[] member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && precedes(x.forward[i], score, member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        if (x != null && x.score == score && Arrays.equals(x.member, member)) {
            deleteNode(x, update);
            return true;
        }
        return false;
    }

    private void deleteNode(Node x, Node[] update) {
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == x) {
                update[i].span[i] += x.span[i] - 1;
                update[i].forward[i] = x.forward[i];
            } else {
                update[i].span[i]--;
            }
        }
        if (x.forward[0] != null) {
            x.forward[0].backward = x.backward;
        } else {
            tail = x.backward;
        }
        while (level > 1 && head.forward[level - 1] == null) {
            level--;
        }
        length--;
    }

    @Override
    public long rank(double score, byte[] member) {
        long rank = 0;
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && precedesOrEquals(x.forward[i], score, member)) {
                rank += x.span[i];
                x = x.forward[i];
            }
            if (x.member != null && x.score == score && Arrays.equals(x.member, member)) {
                return rank - 1; // accumulated rank is 1-based; expose 0-based
            }
        }
        return -1;
    }

    @Override
    public ScoredMember getByRank(long rank) {
        Node node = nodeByRank(rank);
        return node == null ? null : new ScoredMember(node.member, node.score);
    }

    @Override
    public List<ScoredMember> rangeByRank(long start, long stop) {
        List<ScoredMember> out = new ArrayList<>();
        Node node = nodeByRank(start);
        long remaining = stop - start + 1;
        while (node != null && remaining-- > 0) {
            out.add(new ScoredMember(node.member, node.score));
            node = node.forward[0];
        }
        return out;
    }

    @Override
    public List<ScoredMember> ascending() {
        List<ScoredMember> out = new ArrayList<>((int) length);
        Node node = head.forward[0];
        while (node != null) {
            out.add(new ScoredMember(node.member, node.score));
            node = node.forward[0];
        }
        return out;
    }

    /** Finds the node at a 0-based rank, or {@code null} if out of range. */
    private Node nodeByRank(long rank) {
        if (rank < 0 || rank >= length) {
            return null;
        }
        long target = rank + 1; // internal ranks are 1-based
        long traversed = 0;
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= target) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == target) {
                return x;
            }
        }
        return null;
    }

    /** Whether {@code node} sorts strictly before {@code (score, member)}. */
    private static boolean precedes(Node node, double score, byte[] member) {
        return node.score < score || (node.score == score && compare(node.member, member) < 0);
    }

    /** Whether {@code node} sorts before or equal to {@code (score, member)}. */
    private static boolean precedesOrEquals(Node node, double score, byte[] member) {
        return node.score < score || (node.score == score && compare(node.member, member) <= 0);
    }

    /** Unsigned lexicographic comparison of member bytes. */
    static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    private static int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && ThreadLocalRandom.current().nextDouble() < P) {
            lvl++;
        }
        return lvl;
    }
}
