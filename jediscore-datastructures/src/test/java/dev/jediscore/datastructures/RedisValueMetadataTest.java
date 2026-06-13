package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for the per-object LRU idle and LFU frequency metadata on {@link RedisValue}. */
class RedisValueMetadataTest {

    private static StringValue value() {
        return new StringValue("v".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void lfuCounterStartsAtInitValueAndFirstAccessIncrements() {
        StringValue v = value();
        assertThat(v.frequency()).isEqualTo(5); // LFU_INIT_VAL
        // At the init value baseval is 0, so the increment probability is 1.0:
        // the first access deterministically bumps the counter to 6.
        v.recordAccess(System.currentTimeMillis());
        assertThat(v.frequency()).isEqualTo(6);
    }

    @Test
    void frequencyClimbsWithRepeatedAccess() {
        StringValue v = value();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 100_000; i++) {
            v.recordAccess(now); // same minute: no decay, only probabilistic increments
        }
        assertThat(v.frequency()).isGreaterThan(6).isLessThanOrEqualTo(255);
    }

    @Test
    void idleTimeReflectsLastAccess() {
        StringValue v = value();
        v.recordAccess(1_000_000);
        assertThat(v.idleMillis(1_000_500)).isEqualTo(500);
        assertThat(v.idleMillis(999_000)).isZero(); // never negative
    }

    @Test
    void estimateBytesGrowsWithContent() {
        StringValue small = new StringValue("x".getBytes(StandardCharsets.UTF_8));
        StringValue large = new StringValue("x".repeat(1000).getBytes(StandardCharsets.UTF_8));
        assertThat(large.estimateBytes()).isGreaterThan(small.estimateBytes());
    }
}
