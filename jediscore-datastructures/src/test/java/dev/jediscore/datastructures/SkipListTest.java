package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SkipList}, including a differential check against the
 * {@link TreeMapSortedIndex} alternative: both must agree on ordering and ranks.
 */
class SkipListTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void ordersByScoreThenMember() {
        SkipList sl = new SkipList();
        sl.insert(2.0, b("b"));
        sl.insert(1.0, b("a"));
        sl.insert(2.0, b("a")); // same score, member tiebreak
        List<String> order = sl.ascending().stream().map(m -> new String(m.member())).toList();
        assertThat(order).containsExactly("a", "a", "b");
        assertThat(sl.size()).isEqualTo(3);
    }

    @Test
    void rankAndGetByRank() {
        SkipList sl = new SkipList();
        sl.insert(10, b("x"));
        sl.insert(20, b("y"));
        sl.insert(30, b("z"));
        assertThat(sl.rank(20, b("y"))).isEqualTo(1);
        assertThat(sl.rank(10, b("x"))).isEqualTo(0);
        assertThat(sl.rank(99, b("absent"))).isEqualTo(-1);
        assertThat(new String(sl.getByRank(2).member())).isEqualTo("z");
        assertThat(sl.getByRank(5)).isNull();
    }

    @Test
    void deleteMaintainsOrderAndRank() {
        SkipList sl = new SkipList();
        for (int i = 0; i < 10; i++) {
            sl.insert(i, b("m" + i));
        }
        assertThat(sl.delete(5, b("m5"))).isTrue();
        assertThat(sl.delete(5, b("m5"))).isFalse();
        assertThat(sl.size()).isEqualTo(9);
        assertThat(new String(sl.getByRank(5).member())).isEqualTo("m6");
        assertThat(sl.rank(6, b("m6"))).isEqualTo(5);
    }

    @Test
    void rangeByRankSlices() {
        SkipList sl = new SkipList();
        for (int i = 0; i < 10; i++) {
            sl.insert(i, b("m" + i));
        }
        List<String> slice = sl.rangeByRank(2, 4).stream().map(m -> new String(m.member())).toList();
        assertThat(slice).containsExactly("m2", "m3", "m4");
    }

    @Test
    void matchesTreeMapAlternativeUnderRandomOps() {
        // The skiplist and the TreeMap-backed index must be observationally
        // identical: same ascending order and same ranks for every member.
        SkipList skip = new SkipList();
        TreeMapSortedIndex tree = new TreeMapSortedIndex();
        Random rnd = new Random(42);
        List<ScoredMember> present = new ArrayList<>();
        for (int op = 0; op < 2000; op++) {
            double score = rnd.nextInt(50);
            byte[] member = b("m" + rnd.nextInt(200));
            boolean exists = present.stream().anyMatch(m -> java.util.Arrays.equals(m.member(), member));
            if (!exists) {
                skip.insert(score, member);
                tree.insert(score, member);
                present.add(new ScoredMember(member, score));
            } else if (rnd.nextBoolean()) {
                ScoredMember toRemove = present.stream()
                        .filter(m -> java.util.Arrays.equals(m.member(), member)).findFirst().get();
                skip.delete(toRemove.score(), toRemove.member());
                tree.delete(toRemove.score(), toRemove.member());
                present.remove(toRemove);
            }
        }
        assertThat(skip.size()).isEqualTo(tree.size());
        List<ScoredMember> a = skip.ascending();
        List<ScoredMember> b = tree.ascending();
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).member()).isEqualTo(b.get(i).member());
            assertThat(a.get(i).score()).isEqualTo(b.get(i).score());
            assertThat(skip.rank(a.get(i).score(), a.get(i).member()))
                    .isEqualTo(tree.rank(b.get(i).score(), b.get(i).member()));
        }
    }
}
