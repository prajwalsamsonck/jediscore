package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the SCAN family. Cursors are implementation-specific, so
 * these tests assert the contract that matters — a full iteration returns every
 * element — rather than comparing cursor values to Redis.
 */
class JediCoreScanIntegrationTest {

    private static JediCore server;
    private static int port;
    private RespTestClient c;

    @BeforeAll
    static void start() throws InterruptedException {
        server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
        port = server.port();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void freshClient() throws IOException {
        c = new RespTestClient(port);
        c.call("FLUSHALL");
    }

    private static String bulk(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static List<RespValue> array(RespValue v) {
        return ((RespValue.Array) v).items();
    }

    /** Runs a scan command to completion, returning all emitted elements. */
    private List<String> scanAll(String command, String key, boolean stride2, String... opts) throws IOException {
        List<String> all = new ArrayList<>();
        String cursor = "0";
        int guard = 0;
        do {
            List<String> args = new ArrayList<>();
            args.add(command);
            if (key != null) {
                args.add(key);
            }
            args.add(cursor);
            for (String o : opts) {
                args.add(o);
            }
            RespValue reply = c.call(args.toArray(new String[0]));
            List<RespValue> items = array(reply);
            cursor = bulk(items.get(0));
            List<RespValue> elements = array(items.get(1));
            for (int i = 0; i < elements.size(); i += (stride2 ? 2 : 1)) {
                all.add(bulk(elements.get(i)));
            }
            assertThat(guard++).as("scan must terminate").isLessThan(1_000_000);
        } while (!cursor.equals("0"));
        return all;
    }

    @Test
    void fullScanReturnsEveryKey() throws IOException {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            c.call("SET", "k" + i, "v");
            expected.add("k" + i);
        }
        assertThat(scanAll("SCAN", null, false)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void scanMatchFilters() throws IOException {
        for (int i = 0; i < 100; i++) {
            c.call("SET", "user:" + i, "v");
            c.call("SET", "other:" + i, "v");
        }
        List<String> users = scanAll("SCAN", null, false, "MATCH", "user:*");
        assertThat(users).hasSize(100).allMatch(k -> k.startsWith("user:"));
    }

    @Test
    void scanTypeFilters() throws IOException {
        for (int i = 0; i < 50; i++) {
            c.call("SET", "s" + i, "v");
            c.call("RPUSH", "l" + i, "a");
        }
        List<String> lists = scanAll("SCAN", null, false, "TYPE", "list");
        assertThat(lists).hasSize(50).allMatch(k -> k.startsWith("l"));
    }

    @Test
    void scanCountStillCompletes() throws IOException {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            c.call("SET", "k" + i, "v");
            expected.add("k" + i);
        }
        // A larger COUNT visits more buckets per call but the full iteration is identical.
        assertThat(scanAll("SCAN", null, false, "COUNT", "100"))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void hscanLargeAndSmall() throws IOException {
        // Small hash (listpack): single call.
        c.call("HSET", "small", "f1", "v1", "f2", "v2");
        assertThat(scanAll("HSCAN", "small", true)).containsExactlyInAnyOrder("f1", "f2");

        // Large hash (hashtable): cursor iteration returns all fields.
        List<String> expected = new ArrayList<>();
        String[] args = new String[2 + 2 * 500];
        args[0] = "HSET";
        args[1] = "big";
        for (int i = 0; i < 500; i++) {
            args[2 + i * 2] = "field" + i;
            args[3 + i * 2] = "value" + i;
            expected.add("field" + i);
        }
        c.call(args);
        assertThat(bulk(c.call("OBJECT", "ENCODING", "big"))).isEqualTo("hashtable");
        assertThat(scanAll("HSCAN", "big", true)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void hscanNoValues() throws IOException {
        c.call("HSET", "h", "f1", "v1", "f2", "v2");
        // With NOVALUES the reply has no value elements, so stride 1 collects fields.
        assertThat(scanAll("HSCAN", "h", false, "NOVALUES")).containsExactlyInAnyOrder("f1", "f2");
    }

    @Test
    void sscanLargeReturnsAllMembers() throws IOException {
        List<String> expected = new ArrayList<>();
        String[] args = new String[2 + 500];
        args[0] = "SADD";
        args[1] = "s";
        for (int i = 0; i < 500; i++) {
            args[2 + i] = "member" + i;
            expected.add("member" + i);
        }
        c.call(args);
        assertThat(bulk(c.call("OBJECT", "ENCODING", "s"))).isEqualTo("hashtable");
        assertThat(scanAll("SSCAN", "s", false)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void zscanLargeReturnsAllMembers() throws IOException {
        List<String> expected = new ArrayList<>();
        String[] args = new String[2 + 2 * 200];
        args[0] = "ZADD";
        args[1] = "z";
        for (int i = 0; i < 200; i++) {
            args[2 + i * 2] = Integer.toString(i);
            args[3 + i * 2] = "m" + i;
            expected.add("m" + i);
        }
        c.call(args);
        assertThat(bulk(c.call("OBJECT", "ENCODING", "z"))).isEqualTo("skiplist");
        assertThat(scanAll("ZSCAN", "z", true)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void scanOnMissingCollectionIsEmpty() throws IOException {
        assertThat(scanAll("HSCAN", "nope", true)).isEmpty();
        assertThat(scanAll("SSCAN", "nope", false)).isEmpty();
        assertThat(scanAll("ZSCAN", "nope", true)).isEmpty();
    }
}
