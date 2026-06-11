package dev.jediscore.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the benchmarks module. Verifies the hashed logic the JMH
 * benchmark measures is correct and deterministic; the statistical run itself
 * is exercised by the {@code jmh} Gradle task.
 */
class Fnv1aTest {

    @Test
    void emptyInputHashesToOffsetBasis() {
        assertThat(Fnv1a.hash(new byte[0])).isEqualTo(0x811c9dc5);
    }

    @Test
    void knownVectorMatchesReferenceValue() {
        // FNV-1a 32-bit of "a" is 0xe40c292c per the reference test vectors.
        int h = Fnv1a.hash("a".getBytes(StandardCharsets.US_ASCII));
        assertThat(h).isEqualTo(0xe40c292c);
    }

    @Test
    void isDeterministic() {
        byte[] data = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
        assertThat(Fnv1a.hash(data)).isEqualTo(Fnv1a.hash(data));
    }
}
