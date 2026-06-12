package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for {@link HashValue}, focusing on the listpack→hashtable conversion. */
class HashValueTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void smallHashStaysListpack() {
        HashValue h = new HashValue(128, 64);
        assertThat(h.put(b("f1"), b("v1"))).isTrue();
        assertThat(h.put(b("f1"), b("v1b"))).isFalse(); // overwrite, not new
        assertThat(h.encoding()).isEqualTo("listpack");
        assertThat(h.get(b("f1"))).isEqualTo(b("v1b"));
        assertThat(h.size()).isEqualTo(1);
    }

    @Test
    void convertsToHashtableWhenEntriesExceedThreshold() {
        HashValue h = new HashValue(4, 64);
        for (int i = 0; i < 4; i++) {
            h.put(b("f" + i), b("v" + i));
        }
        assertThat(h.encoding()).isEqualTo("listpack");
        h.put(b("f4"), b("v4")); // 5th pair > maxEntries=4
        assertThat(h.encoding()).isEqualTo("hashtable");
        assertThat(h.size()).isEqualTo(5);
        assertThat(h.get(b("f2"))).isEqualTo(b("v2"));
    }

    @Test
    void convertsToHashtableWhenValueTooLong() {
        HashValue h = new HashValue(128, 8);
        h.put(b("field"), b("0123456789")); // value length 10 > maxValue 8
        assertThat(h.encoding()).isEqualTo("hashtable");
    }

    @Test
    void removeAndContains() {
        HashValue h = new HashValue(128, 64);
        h.put(b("a"), b("1"));
        h.put(b("b"), b("2"));
        assertThat(h.contains(b("a"))).isTrue();
        assertThat(h.remove(b("a"))).isTrue();
        assertThat(h.remove(b("a"))).isFalse();
        assertThat(h.contains(b("a"))).isFalse();
        assertThat(h.size()).isEqualTo(1);
    }

    @Test
    void fieldsAndValuesPreserveOrder() {
        HashValue h = new HashValue(128, 64);
        h.put(b("x"), b("1"));
        h.put(b("y"), b("2"));
        h.put(b("z"), b("3"));
        assertThat(h.fields()).map(String::new).containsExactly("x", "y", "z");
        assertThat(h.values()).map(String::new).containsExactly("1", "2", "3");
    }

    @Test
    void deepCopyIsIndependent() {
        HashValue h = new HashValue(128, 64);
        h.put(b("a"), b("1"));
        HashValue copy = h.deepCopy();
        copy.put(b("a"), b("changed"));
        copy.put(b("b"), b("2"));
        assertThat(h.get(b("a"))).isEqualTo(b("1"));
        assertThat(h.contains(b("b"))).isFalse();
    }
}
