package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** End-to-end coverage of multi-part AOF: round-trip, BGREWRITEAOF, fsync policies. */
class JediCoreAofIntegrationTest {

    private static String bulk(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static List<String> arr(RespValue v) {
        return ((RespValue.Array) v).items().stream().map(JediCoreAofIntegrationTest::bulk).toList();
    }

    private static JediCore startAof(Path dir, String fsync) throws InterruptedException {
        return JediCore.start(ServerConfig.defaults("127.0.0.1", 0),
                PersistenceConfig.defaults().withDir(dir.toString()).withAppendOnly(fsync));
    }

    @Test
    void aofRoundTripsAcrossRestart(@TempDir Path dir) throws Exception {
        JediCore first = startAof(dir, "everysec");
        try (RespTestClient c = new RespTestClient(first.port())) {
            c.call("SET", "s", "hello");
            c.call("RPUSH", "l", "a", "b", "c");
            c.call("SADD", "set", "x", "y", "z");
            c.call("HSET", "h", "f1", "v1", "f2", "v2");
            c.call("ZADD", "z", "1", "alpha", "2", "beta");
            c.call("INCR", "counter");
            c.call("INCR", "counter");
            c.call("DEL", "s"); // a deletion must also replay
        } finally {
            first.close(); // flushes + closes the AOF cleanly
        }

        // The appendonlydir must hold the multi-part files.
        Path aofDir = dir.resolve("appendonlydir");
        assertThat(Files.exists(aofDir.resolve("appendonly.aof.manifest"))).isTrue();
        try (Stream<Path> files = Files.list(aofDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .anyMatch(n -> n.endsWith(".base.rdb"))
                    .anyMatch(n -> n.endsWith(".incr.aof"));
        }

        JediCore second = startAof(dir, "everysec");
        try (RespTestClient c = new RespTestClient(second.port())) {
            assertThat(c.call("GET", "s")).isInstanceOf(RespValue.Null.class); // DEL replayed
            assertThat(arr(c.call("LRANGE", "l", "0", "-1"))).containsExactly("a", "b", "c");
            assertThat(arr(c.call("SMEMBERS", "set"))).containsExactlyInAnyOrder("x", "y", "z");
            assertThat(bulk(c.call("HGET", "h", "f2"))).isEqualTo("v2");
            assertThat(bulk(c.call("ZSCORE", "z", "beta"))).isEqualTo("2");
            assertThat(bulk(c.call("GET", "counter"))).isEqualTo("2"); // two INCRs replayed
        } finally {
            second.close();
        }
    }

    @Test
    void bgrewriteaofCompactsAndPreservesData(@TempDir Path dir) throws Exception {
        JediCore first = startAof(dir, "everysec");
        try (RespTestClient c = new RespTestClient(first.port())) {
            for (int i = 0; i < 100; i++) {
                c.call("SET", "k" + i, "v" + i);
            }
            assertThat(((RespValue.SimpleString) c.call("BGREWRITEAOF")).value())
                    .isEqualTo("Background append only file rewriting started");
            // Writes after the rewrite must land in the new incr file.
            for (int i = 100; i < 110; i++) {
                c.call("SET", "k" + i, "v" + i);
            }
        } finally {
            first.close(); // close() awaits the rewrite, flushing base + manifest
        }

        // After a rewrite the live base is seq 2 and the seq-1 files are gone.
        Path aofDir = dir.resolve("appendonlydir");
        assertThat(Files.exists(aofDir.resolve("appendonly.aof.2.base.rdb"))).isTrue();
        assertThat(Files.exists(aofDir.resolve("appendonly.aof.1.base.rdb"))).isFalse();

        JediCore second = startAof(dir, "everysec");
        try (RespTestClient c = new RespTestClient(second.port())) {
            assertThat(c.call("DBSIZE")).isEqualTo(RespValue.integer(110));
            assertThat(bulk(c.call("GET", "k0"))).isEqualTo("v0");     // from the rewritten base
            assertThat(bulk(c.call("GET", "k105"))).isEqualTo("v105"); // from the new incr
        } finally {
            second.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"always", "everysec", "no"})
    void everyFsyncPolicyRoundTrips(String policy, @TempDir Path dir) throws Exception {
        JediCore first = startAof(dir, policy);
        try (RespTestClient c = new RespTestClient(first.port())) {
            c.call("SET", "k", "value-" + policy);
        } finally {
            first.close();
        }
        JediCore second = startAof(dir, policy);
        try (RespTestClient c = new RespTestClient(second.port())) {
            assertThat(bulk(c.call("GET", "k"))).isEqualTo("value-" + policy);
        } finally {
            second.close();
        }
    }
}
