package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for {@link StringValue} encoding classification. */
class StringValueTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void canonicalLongDetection() {
        assertThat(StringValue.isCanonicalLong(b("0"))).isTrue();
        assertThat(StringValue.isCanonicalLong(b("123"))).isTrue();
        assertThat(StringValue.isCanonicalLong(b("-123"))).isTrue();
        assertThat(StringValue.isCanonicalLong(b("9223372036854775807"))).isTrue();
        assertThat(StringValue.isCanonicalLong(b("-9223372036854775808"))).isTrue();

        assertThat(StringValue.isCanonicalLong(b("9223372036854775808"))).isFalse(); // overflow
        assertThat(StringValue.isCanonicalLong(b("00"))).isFalse();
        assertThat(StringValue.isCanonicalLong(b("-0"))).isFalse();
        assertThat(StringValue.isCanonicalLong(b("+1"))).isFalse();
        assertThat(StringValue.isCanonicalLong(b(" 1"))).isFalse();
        assertThat(StringValue.isCanonicalLong(b(""))).isFalse();
        assertThat(StringValue.isCanonicalLong(b("12a"))).isFalse();
    }

    @Test
    void encodingClassification() {
        assertThat(new StringValue(b("12345")).encoding()).isEqualTo("int");
        assertThat(new StringValue(b("hello")).encoding()).isEqualTo("embstr");
        assertThat(new StringValue(b("x".repeat(45))).encoding()).isEqualTo("raw");
    }

    @Test
    void mutationForcesRaw() {
        StringValue v = new StringValue(b("123"));
        assertThat(v.encoding()).isEqualTo("int");
        v.setRaw(b("123"));
        assertThat(v.encoding()).isEqualTo("raw");
    }
}
