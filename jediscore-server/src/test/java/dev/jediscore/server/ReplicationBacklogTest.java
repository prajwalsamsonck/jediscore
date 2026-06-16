package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ReplicationBacklog;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Unit tests for the replication backlog ring buffer that backs partial resync. */
class ReplicationBacklogTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String s(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    void retainsAndSlicesFromAnOffset() {
        ReplicationBacklog backlog = new ReplicationBacklog(64);
        assertThat(backlog.isActive()).isFalse();

        backlog.append(b("abcde")); // offsets 0..5
        assertThat(backlog.isActive()).isTrue();
        assertThat(backlog.startOffset()).isZero();
        assertThat(backlog.endOffset()).isEqualTo(5);

        assertThat(backlog.canServe(0)).isTrue();
        assertThat(backlog.canServe(5)).isTrue(); // caught up → empty slice
        assertThat(s(backlog.since(0))).isEqualTo("abcde");
        assertThat(s(backlog.since(2))).isEqualTo("cde");
        assertThat(backlog.since(5)).isEmpty();
    }

    @Test
    void evictsOldestWhenCapacityExceeded() {
        ReplicationBacklog backlog = new ReplicationBacklog(8);
        backlog.append(b("0123456789")); // 10 bytes into an 8-byte ring
        assertThat(backlog.endOffset()).isEqualTo(10);
        assertThat(backlog.startOffset()).isEqualTo(2); // only the last 8 retained

        // An offset that has scrolled out of the window cannot be served.
        assertThat(backlog.canServe(0)).isFalse();
        assertThat(backlog.canServe(1)).isFalse();
        assertThat(backlog.canServe(2)).isTrue();
        assertThat(s(backlog.since(2))).isEqualTo("23456789");
        assertThat(s(backlog.since(6))).isEqualTo("6789");
    }

    @Test
    void spansMultipleAppendsAcrossTheRingWrap() {
        ReplicationBacklog backlog = new ReplicationBacklog(8);
        backlog.append(b("abc"));
        backlog.append(b("def"));
        backlog.append(b("ghi")); // total 9 bytes, wraps the 8-byte ring
        assertThat(backlog.endOffset()).isEqualTo(9);
        assertThat(backlog.startOffset()).isEqualTo(1);
        assertThat(s(backlog.since(1))).isEqualTo("bcdefghi");
        assertThat(s(backlog.since(6))).isEqualTo("ghi");
    }
}
