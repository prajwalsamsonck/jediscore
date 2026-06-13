package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link Dict}, including the SCAN completeness guarantee. */
class DictTest {

    private static Bytes k(String s) {
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void putGetRemoveResize() {
        Dict<Integer> d = new Dict<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(d.put(k("k" + i), i)).isNull();
        }
        assertThat(d.size()).isEqualTo(1000);
        assertThat(d.get(k("k500"))).isEqualTo(500);
        assertThat(d.put(k("k500"), 999)).isEqualTo(500); // update returns old
        assertThat(d.get(k("k500"))).isEqualTo(999);
        assertThat(d.remove(k("k500"))).isEqualTo(999);
        assertThat(d.get(k("k500"))).isNull();
        assertThat(d.containsKey(k("k0"))).isTrue();
        assertThat(d.size()).isEqualTo(999);
    }

    @Test
    void fullScanVisitsEveryElement() {
        Dict<Integer> d = new Dict<>();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            d.put(k("k" + i), i);
            expected.add("k" + i);
        }
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        int guard = 0;
        do {
            cursor = d.scan(cursor, 10, (key, value) -> seen.add(key.toString()));
            assertThat(guard++).as("scan must terminate").isLessThan(100_000);
        } while (cursor != 0);
        assertThat(seen).isEqualTo(expected);
    }

    @Test
    void fullScanReturnsAllKeysPresentThroughoutDespiteGrowth() {
        // The core guarantee: keys present for the whole scan are all returned,
        // even though the table doubles several times mid-iteration.
        Dict<Integer> d = new Dict<>();
        Set<String> stable = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            d.put(k("stable" + i), i);
            stable.add("stable" + i);
        }
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        int added = 0;
        do {
            cursor = d.scan(cursor, 5, (key, value) -> seen.add(key.toString()));
            // Insert new keys between scan steps to force resizes mid-iteration.
            for (int j = 0; j < 50 && added < 5000; j++) {
                d.put(k("new" + (added++)), added);
            }
        } while (cursor != 0);
        assertThat(seen).as("every stable key must be seen at least once").containsAll(stable);
    }

    @Test
    void scanOnEmptyDictReturnsZero() {
        Dict<Integer> d = new Dict<>();
        assertThat(d.scan(0, 10, (key, value) -> { })).isZero();
    }
}
