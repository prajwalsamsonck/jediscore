package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for the compact {@link Listpack} arena. */
class ListpackTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void appendAndGet() {
        Listpack lp = new Listpack();
        lp.add(b("alpha"));
        lp.add(b("beta"));
        lp.add(b(""));
        assertThat(lp.count()).isEqualTo(3);
        assertThat(lp.get(0)).isEqualTo(b("alpha"));
        assertThat(lp.get(1)).isEqualTo(b("beta"));
        assertThat(lp.get(2)).isEqualTo(new byte[0]);
    }

    @Test
    void insertRemoveAndSet() {
        Listpack lp = new Listpack();
        lp.add(b("a"));
        lp.add(b("c"));
        lp.insert(1, b("b"));
        assertThat(lp.toList()).map(String::new).containsExactly("a", "b", "c");

        lp.removeAt(0);
        assertThat(lp.toList()).map(String::new).containsExactly("b", "c");

        lp.set(1, b("ccc"));
        assertThat(lp.toList()).map(String::new).containsExactly("b", "ccc");
        assertThat(lp.count()).isEqualTo(2);
    }

    @Test
    void indexOfWithStepScansEverySecondEntry() {
        // Simulate a hash listpack [f0,v0,f1,v1].
        Listpack lp = new Listpack();
        lp.add(b("name"));
        lp.add(b("redis"));
        lp.add(b("port"));
        lp.add(b("6379"));
        assertThat(lp.indexOf(b("port"), 0, 2)).isEqualTo(2);
        assertThat(lp.indexOf(b("name"), 0, 2)).isEqualTo(0);
        // A value should not be found when scanning only fields.
        assertThat(lp.indexOf(b("6379"), 0, 2)).isEqualTo(-1);
    }

    @Test
    void survivesArenaGrowth() {
        Listpack lp = new Listpack();
        for (int i = 0; i < 500; i++) {
            lp.add(b("entry-" + i));
        }
        assertThat(lp.count()).isEqualTo(500);
        assertThat(new String(lp.get(0))).isEqualTo("entry-0");
        assertThat(new String(lp.get(499))).isEqualTo("entry-499");
        assertThat(new String(lp.get(250))).isEqualTo("entry-250");
    }
}
