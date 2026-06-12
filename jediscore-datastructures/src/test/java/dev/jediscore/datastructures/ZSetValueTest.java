package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link ZSetValue}, across both the listpack and skiplist encodings. */
class ZSetValueTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> members(List<ScoredMember> list) {
        return list.stream().map(m -> new String(m.member())).toList();
    }

    @Test
    void putScoreAndOrder() {
        ZSetValue z = new ZSetValue(128, 64);
        assertThat(z.put(b("b"), 2.0)).isNull();
        assertThat(z.put(b("a"), 1.0)).isNull();
        assertThat(z.put(b("c"), 3.0)).isNull();
        assertThat(z.put(b("b"), 2.5)).isEqualTo(2.0); // update returns old score
        assertThat(z.score(b("b"))).isEqualTo(2.5);
        assertThat(members(z.ascending())).containsExactly("a", "b", "c");
        assertThat(z.encoding()).isEqualTo("listpack");
    }

    @Test
    void rankRespectsScoreThenMember() {
        ZSetValue z = new ZSetValue(128, 64);
        z.put(b("a"), 1.0);
        z.put(b("b"), 1.0); // same score, member tiebreak
        z.put(b("c"), 2.0);
        assertThat(z.rank(b("a"), false)).isEqualTo(0);
        assertThat(z.rank(b("b"), false)).isEqualTo(1);
        assertThat(z.rank(b("c"), false)).isEqualTo(2);
        assertThat(z.rank(b("c"), true)).isEqualTo(0);  // reverse
        assertThat(z.rank(b("missing"), false)).isEqualTo(-1);
    }

    @Test
    void rangeByIndexForwardAndReverse() {
        ZSetValue z = new ZSetValue(128, 64);
        for (int i = 0; i < 5; i++) {
            z.put(b("m" + i), i);
        }
        assertThat(members(z.rangeByIndex(0, 2, false))).containsExactly("m0", "m1", "m2");
        assertThat(members(z.rangeByIndex(0, 2, true))).containsExactly("m4", "m3", "m2");
        assertThat(members(z.rangeByIndex(-2, -1, false))).containsExactly("m3", "m4");
    }

    @Test
    void popMinMax() {
        ZSetValue z = new ZSetValue(128, 64);
        z.put(b("a"), 1.0);
        z.put(b("b"), 2.0);
        z.put(b("c"), 3.0);
        assertThat(new String(z.popMin().member())).isEqualTo("a");
        assertThat(new String(z.popMax().member())).isEqualTo("c");
        assertThat(z.size()).isEqualTo(1);
    }

    @Test
    void convertsToSkiplistAndBehavesIdentically() {
        ZSetValue z = new ZSetValue(8, 64);
        for (int i = 0; i < 8; i++) {
            z.put(b("m" + i), i);
        }
        assertThat(z.encoding()).isEqualTo("listpack");
        z.put(b("m8"), 8); // 9th member > threshold
        assertThat(z.encoding()).isEqualTo("skiplist");
        // Behaviour unchanged after conversion.
        assertThat(z.score(b("m3"))).isEqualTo(3.0);
        assertThat(z.rank(b("m8"), false)).isEqualTo(8);
        assertThat(members(z.rangeByIndex(0, -1, false)))
                .containsExactly("m0", "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8");
        assertThat(z.put(b("m3"), 100.0)).isEqualTo(3.0); // re-score moves it to the end
        assertThat(z.rank(b("m3"), false)).isEqualTo(8);
    }

    @Test
    void convertsOnLongMember() {
        ZSetValue z = new ZSetValue(128, 8);
        z.put(b("0123456789"), 1.0); // member length 10 > 8
        assertThat(z.encoding()).isEqualTo("skiplist");
    }

    @Test
    void deepCopyIsIndependent() {
        ZSetValue z = new ZSetValue(128, 64);
        z.put(b("a"), 1.0);
        ZSetValue copy = z.deepCopy();
        copy.put(b("a"), 5.0);
        copy.put(b("b"), 2.0);
        assertThat(z.score(b("a"))).isEqualTo(1.0);
        assertThat(z.score(b("b"))).isNull();
    }
}
