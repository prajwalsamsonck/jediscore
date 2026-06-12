package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for {@link SetValue} and its intset→listpack→hashtable conversions. */
class SetValueTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void allIntegersUseIntset() {
        SetValue set = new SetValue(512, 128, 64);
        assertThat(set.add(b("1"))).isTrue();
        assertThat(set.add(b("2"))).isTrue();
        assertThat(set.add(b("1"))).isFalse(); // duplicate
        assertThat(set.encoding()).isEqualTo("intset");
        assertThat(set.size()).isEqualTo(2);
        assertThat(set.contains(b("2"))).isTrue();
        assertThat(set.contains(b("3"))).isFalse();
        // intset iterates in numeric order
        assertThat(set.members().stream().map(String::new).toList()).containsExactly("1", "2");
    }

    @Test
    void nonIntegerMemberConvertsIntsetToListpack() {
        SetValue set = new SetValue(512, 128, 64);
        set.add(b("1"));
        set.add(b("2"));
        assertThat(set.encoding()).isEqualTo("intset");
        set.add(b("hello"));
        assertThat(set.encoding()).isEqualTo("listpack");
        assertThat(set.contains(b("1"))).isTrue();    // migrated integer survives
        assertThat(set.contains(b("hello"))).isTrue();
        assertThat(set.size()).isEqualTo(3);
    }

    @Test
    void exceedingIntsetLimitConverts() {
        SetValue set = new SetValue(4, 128, 64); // tiny intset limit
        for (int i = 0; i < 4; i++) {
            set.add(b(Integer.toString(i)));
        }
        assertThat(set.encoding()).isEqualTo("intset");
        set.add(b("4")); // 5th integer > maxIntset=4 -> still all ints, fits listpack
        assertThat(set.encoding()).isEqualTo("listpack");
        assertThat(set.size()).isEqualTo(5);
    }

    @Test
    void largeOrLongMembersUseHashtable() {
        SetValue byCount = new SetValue(512, 4, 64);
        for (int i = 0; i < 5; i++) {
            byCount.add(b("member-" + i)); // non-int, exceeds listpack entries=4
        }
        assertThat(byCount.encoding()).isEqualTo("hashtable");

        SetValue byLength = new SetValue(512, 128, 8);
        byLength.add(b("0123456789")); // length 10 > maxValue 8
        assertThat(byLength.encoding()).isEqualTo("hashtable");
    }

    @Test
    void removeAndContainsAcrossEncodings() {
        SetValue set = new SetValue(512, 128, 64);
        set.add(b("a"));
        set.add(b("b"));
        assertThat(set.encoding()).isEqualTo("listpack");
        assertThat(set.remove(b("a"))).isTrue();
        assertThat(set.remove(b("a"))).isFalse();
        assertThat(set.contains(b("a"))).isFalse();
        assertThat(set.size()).isEqualTo(1);
    }

    @Test
    void deepCopyIsIndependent() {
        SetValue set = new SetValue(512, 128, 64);
        set.add(b("1"));
        set.add(b("2"));
        SetValue copy = set.deepCopy();
        copy.add(b("3"));
        assertThat(set.contains(b("3"))).isFalse();
        assertThat(copy.size()).isEqualTo(3);
    }
}
