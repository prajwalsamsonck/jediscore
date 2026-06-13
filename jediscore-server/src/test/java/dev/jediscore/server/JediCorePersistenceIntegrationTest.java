package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end coverage of SAVE/BGSAVE/LASTSAVE/DEBUG RELOAD and restart durability. */
class JediCorePersistenceIntegrationTest {

    private static String bulk(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static long integer(RespValue v) {
        return ((RespValue.Integer) v).value();
    }

    private static String simple(RespValue v) {
        return ((RespValue.SimpleString) v).value();
    }

    private static List<String> arr(RespValue v) {
        return ((RespValue.Array) v).items().stream().map(JediCorePersistenceIntegrationTest::bulk).toList();
    }

    private static JediCore start(Path dir) throws InterruptedException {
        return JediCore.start(ServerConfig.defaults("127.0.0.1", 0),
                PersistenceConfig.defaults().withDir(dir.toString()));
    }

    @Test
    void debugReloadPreservesAllTypes(@TempDir Path dir) throws Exception {
        JediCore server = start(dir);
        try (RespTestClient c = new RespTestClient(server.port())) {
            c.call("SET", "s", "hello");
            c.call("RPUSH", "l", "a", "b", "c");
            c.call("SADD", "set", "1", "2", "3");
            c.call("HSET", "h", "f1", "v1", "f2", "v2");
            c.call("ZADD", "z", "1", "x", "2", "y");

            assertThat(simple(c.call("DEBUG", "RELOAD"))).isEqualTo("OK");

            assertThat(bulk(c.call("GET", "s"))).isEqualTo("hello");
            assertThat(arr(c.call("LRANGE", "l", "0", "-1"))).containsExactly("a", "b", "c");
            assertThat(arr(c.call("SMEMBERS", "set"))).containsExactlyInAnyOrder("1", "2", "3");
            assertThat(arr(c.call("ZRANGE", "z", "0", "-1", "WITHSCORES"))).containsExactly("x", "1", "y", "2");
        } finally {
            server.close();
        }
    }

    @Test
    void saveBgsaveAndLastsave(@TempDir Path dir) throws Exception {
        JediCore server = start(dir);
        try (RespTestClient c = new RespTestClient(server.port())) {
            c.call("SET", "k", "v");
            assertThat(simple(c.call("SAVE"))).isEqualTo("OK");
            assertThat(integer(c.call("LASTSAVE"))).isGreaterThan(0);
            assertThat(simple(c.call("BGSAVE"))).isEqualTo("Background saving started");
        } finally {
            server.close();
        }
    }

    @Test
    void dataPersistsAcrossRestart(@TempDir Path dir) throws Exception {
        JediCore first = start(dir);
        try (RespTestClient c = new RespTestClient(first.port())) {
            c.call("SET", "foo", "bar");
            c.call("RPUSH", "nums", "10", "20", "30");
            c.call("SAVE");
        } finally {
            first.close();
        }

        // A fresh server pointed at the same dir must load the dataset on startup.
        JediCore second = start(dir);
        try (RespTestClient c = new RespTestClient(second.port())) {
            assertThat(bulk(c.call("GET", "foo"))).isEqualTo("bar");
            assertThat(arr(c.call("LRANGE", "nums", "0", "-1"))).containsExactly("10", "20", "30");
        } finally {
            second.close();
        }
    }
}
