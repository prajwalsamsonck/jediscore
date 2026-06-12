package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for Redis-compatible glob matching. */
class GlobTest {

    private static boolean match(String pattern, String subject) {
        return Glob.match(pattern.getBytes(StandardCharsets.UTF_8), subject.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void literalAndStar() {
        assertThat(match("hello", "hello")).isTrue();
        assertThat(match("hello", "world")).isFalse();
        assertThat(match("*", "anything")).isTrue();
        assertThat(match("h*o", "hello")).isTrue();
        assertThat(match("h*o", "halo")).isTrue();
        assertThat(match("h*o", "hey")).isFalse();
        assertThat(match("user:*", "user:42")).isTrue();
        assertThat(match("user:*", "admin:42")).isFalse();
    }

    @Test
    void questionMark() {
        assertThat(match("h?llo", "hello")).isTrue();
        assertThat(match("h?llo", "hllo")).isFalse();
    }

    @Test
    void characterClass() {
        assertThat(match("h[ae]llo", "hello")).isTrue();
        assertThat(match("h[ae]llo", "hallo")).isTrue();
        assertThat(match("h[ae]llo", "hillo")).isFalse();
        assertThat(match("h[^e]llo", "hallo")).isTrue();
        assertThat(match("h[^e]llo", "hello")).isFalse();
        assertThat(match("key[0-9]", "key5")).isTrue();
        assertThat(match("key[0-9]", "keyx")).isFalse();
    }

    @Test
    void escaping() {
        assertThat(match("h\\*llo", "h*llo")).isTrue();
        assertThat(match("h\\*llo", "hello")).isFalse();
    }

    @Test
    void emptyAndTrailingStar() {
        assertThat(match("", "")).isTrue();
        assertThat(match("", "x")).isFalse();
        assertThat(match("abc*", "abc")).isTrue();
    }
}
