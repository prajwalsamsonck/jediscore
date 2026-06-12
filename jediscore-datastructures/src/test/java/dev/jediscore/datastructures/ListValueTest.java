package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link ListValue}: operations and the listpack→quicklist conversion. */
class ListValueTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> strings(List<byte[]> items) {
        return items.stream().map(String::new).toList();
    }

    @Test
    void pushPopBothEnds() {
        ListValue list = new ListValue(128, 64);
        list.pushTail(b("b"));
        list.pushHead(b("a"));
        list.pushTail(b("c"));
        assertThat(strings(list.range(0, -1))).containsExactly("a", "b", "c");
        assertThat(new String(list.popHead())).isEqualTo("a");
        assertThat(new String(list.popTail())).isEqualTo("c");
        assertThat(list.size()).isEqualTo(1);
    }

    @Test
    void rangeWithNegativeIndices() {
        ListValue list = new ListValue(128, 64);
        for (String s : new String[] {"a", "b", "c", "d", "e"}) {
            list.pushTail(b(s));
        }
        assertThat(strings(list.range(0, 2))).containsExactly("a", "b", "c");
        assertThat(strings(list.range(-2, -1))).containsExactly("d", "e");
        assertThat(strings(list.range(-100, 100))).containsExactly("a", "b", "c", "d", "e");
        assertThat(list.range(3, 1)).isEmpty();
    }

    @Test
    void indexSetInsertRemoveTrim() {
        ListValue list = new ListValue(128, 64);
        for (String s : new String[] {"a", "b", "c"}) {
            list.pushTail(b(s));
        }
        assertThat(new String(list.index(1))).isEqualTo("b");
        assertThat(new String(list.index(-1))).isEqualTo("c");
        assertThat(list.index(5)).isNull();

        assertThat(list.set(1, b("B"))).isTrue();
        assertThat(list.set(9, b("x"))).isFalse();
        assertThat(list.insert(true, b("c"), b("bc"))).isEqualTo(4); // before c
        assertThat(strings(list.range(0, -1))).containsExactly("a", "B", "bc", "c");

        list.pushTail(b("B"));
        assertThat(list.remove(0, b("B"))).isEqualTo(2); // remove all "B"
        assertThat(strings(list.range(0, -1))).containsExactly("a", "bc", "c");

        list.trim(1, -1);
        assertThat(strings(list.range(0, -1))).containsExactly("bc", "c");
    }

    @Test
    void lposRankAndCount() {
        ListValue list = new ListValue(128, 64);
        for (String s : new String[] {"a", "b", "c", "b", "b"}) {
            list.pushTail(b(s));
        }
        assertThat(list.positions(b("b"), 1, 1, 0)).containsExactly(1L);
        assertThat(list.positions(b("b"), 1, Integer.MAX_VALUE, 0)).containsExactly(1L, 3L, 4L);
        assertThat(list.positions(b("b"), -1, 1, 0)).containsExactly(4L); // first from tail
        assertThat(list.positions(b("b"), 2, 1, 0)).containsExactly(3L);  // second match
        assertThat(list.positions(b("x"), 1, 1, 0)).isEmpty();
    }

    @Test
    void convertsToQuicklistOnSize() {
        ListValue list = new ListValue(8, 64);
        for (int i = 0; i < 8; i++) {
            list.pushTail(b("e" + i));
        }
        assertThat(list.encoding()).isEqualTo("listpack");
        list.pushTail(b("e8")); // 9th element > maxListpackSize=8
        assertThat(list.encoding()).isEqualTo("quicklist");
        assertThat(list.size()).isEqualTo(9);
        assertThat(new String(list.index(0))).isEqualTo("e0");
        assertThat(new String(list.index(8))).isEqualTo("e8");
    }

    @Test
    void convertsToQuicklistOnLargeValue() {
        ListValue list = new ListValue(128, 8);
        list.pushTail(b("0123456789")); // length 10 > maxValue 8
        assertThat(list.encoding()).isEqualTo("quicklist");
    }

    @Test
    void quicklistOperationsAcrossNodes() {
        // Force quicklist and exercise mid-list operations spanning nodes.
        ListValue list = new ListValue(4, 64);
        for (int i = 0; i < 500; i++) {
            list.pushTail(b("v" + i));
        }
        assertThat(list.encoding()).isEqualTo("quicklist");
        assertThat(list.size()).isEqualTo(500);
        assertThat(new String(list.index(0))).isEqualTo("v0");
        assertThat(new String(list.index(250))).isEqualTo("v250");
        assertThat(new String(list.index(499))).isEqualTo("v499");
        assertThat(list.remove(1, b("v250"))).isEqualTo(1);
        assertThat(new String(list.index(250))).isEqualTo("v251");
        assertThat(list.size()).isEqualTo(499);
    }
}
