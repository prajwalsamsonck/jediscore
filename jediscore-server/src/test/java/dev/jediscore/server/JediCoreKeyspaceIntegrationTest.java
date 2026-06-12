package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end coverage of the Phase-2A keyspace, string, and hash commands. */
class JediCoreKeyspaceIntegrationTest {

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

    // ---- assertion helpers --------------------------------------------------

    private static String bulk(RespValue v) {
        assertThat(v).isInstanceOf(RespValue.BulkString.class);
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static long integer(RespValue v) {
        assertThat(v).isInstanceOf(RespValue.Integer.class);
        return ((RespValue.Integer) v).value();
    }

    private static String simple(RespValue v) {
        assertThat(v).isInstanceOf(RespValue.SimpleString.class);
        return ((RespValue.SimpleString) v).value();
    }

    private static String error(RespValue v) {
        assertThat(v).isInstanceOf(RespValue.SimpleError.class);
        return ((RespValue.SimpleError) v).message();
    }

    private static boolean isNil(RespValue v) {
        return v instanceof RespValue.Null;
    }

    private static Map<String, String> asMap(RespValue v) {
        List<RespValue> items = ((RespValue.Array) v).items();
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < items.size(); i += 2) {
            m.put(bulk(items.get(i)), bulk(items.get(i + 1)));
        }
        return m;
    }

    // ---- strings ------------------------------------------------------------

    @Test
    void setGetAndConditionalOptions() throws IOException {
        assertThat(simple(c.call("SET", "k", "v"))).isEqualTo("OK");
        assertThat(bulk(c.call("GET", "k"))).isEqualTo("v");

        assertThat(isNil(c.call("SET", "k", "v2", "NX"))).isTrue();      // exists → not set
        assertThat(bulk(c.call("GET", "k"))).isEqualTo("v");
        assertThat(simple(c.call("SET", "k", "v3", "XX"))).isEqualTo("OK");
        assertThat(isNil(c.call("SET", "new", "x", "XX"))).isTrue();     // absent → not set

        // SET ... GET returns the previous value.
        assertThat(bulk(c.call("SET", "k", "v4", "GET"))).isEqualTo("v3");
    }

    @Test
    void setGetOnWrongTypeIsWrongType() throws IOException {
        c.call("HSET", "h", "f", "v");
        assertThat(error(c.call("GET", "h"))).startsWith("WRONGTYPE");
        assertThat(error(c.call("SET", "h", "x", "GET"))).startsWith("WRONGTYPE");
    }

    @Test
    void expiryViaPxIsObserved() throws IOException, InterruptedException {
        c.call("SET", "k", "v", "PX", "40");
        assertThat(bulk(c.call("GET", "k"))).isEqualTo("v");
        Thread.sleep(80);
        assertThat(isNil(c.call("GET", "k"))).isTrue();
    }

    @Test
    void incrDecrFamily() throws IOException {
        c.call("SET", "n", "10");
        assertThat(integer(c.call("INCR", "n"))).isEqualTo(11);
        assertThat(integer(c.call("INCRBY", "n", "5"))).isEqualTo(16);
        assertThat(integer(c.call("DECR", "n"))).isEqualTo(15);
        assertThat(integer(c.call("DECRBY", "n", "5"))).isEqualTo(10);
        assertThat(bulk(c.call("INCRBYFLOAT", "n", "1.5"))).isEqualTo("11.5");

        c.call("SET", "bad", "notanumber");
        assertThat(error(c.call("INCR", "bad"))).isEqualTo("ERR value is not an integer or out of range");
    }

    @Test
    void appendStrlenAndRanges() throws IOException {
        assertThat(integer(c.call("APPEND", "a", "Hello"))).isEqualTo(5);
        assertThat(integer(c.call("APPEND", "a", " World"))).isEqualTo(11);
        assertThat(bulk(c.call("GET", "a"))).isEqualTo("Hello World");
        assertThat(integer(c.call("STRLEN", "a"))).isEqualTo(11);

        assertThat(bulk(c.call("GETRANGE", "a", "0", "4"))).isEqualTo("Hello");
        assertThat(bulk(c.call("GETRANGE", "a", "-5", "-1"))).isEqualTo("World");

        assertThat(integer(c.call("SETRANGE", "b", "5", "World"))).isEqualTo(10);
    }

    @Test
    void msetMget() throws IOException {
        assertThat(simple(c.call("MSET", "k1", "a", "k2", "b"))).isEqualTo("OK");
        RespValue reply = c.call("MGET", "k1", "k2", "missing");
        List<RespValue> items = ((RespValue.Array) reply).items();
        assertThat(bulk(items.get(0))).isEqualTo("a");
        assertThat(bulk(items.get(1))).isEqualTo("b");
        assertThat(isNil(items.get(2))).isTrue();
    }

    // ---- hashes -------------------------------------------------------------

    @Test
    void hashBasics() throws IOException {
        assertThat(integer(c.call("HSET", "h", "f1", "v1", "f2", "v2"))).isEqualTo(2);
        assertThat(bulk(c.call("HGET", "h", "f1"))).isEqualTo("v1");
        assertThat(integer(c.call("HLEN", "h"))).isEqualTo(2);
        assertThat(integer(c.call("HEXISTS", "h", "f1"))).isEqualTo(1);
        assertThat(integer(c.call("HEXISTS", "h", "nope"))).isEqualTo(0);
        assertThat(integer(c.call("HSETNX", "h", "f1", "x"))).isEqualTo(0); // exists
        assertThat(integer(c.call("HSETNX", "h", "f3", "v3"))).isEqualTo(1);

        assertThat(asMap(c.call("HGETALL", "h")))
                .containsEntry("f1", "v1").containsEntry("f2", "v2").containsEntry("f3", "v3");

        assertThat(integer(c.call("HDEL", "h", "f1", "f2"))).isEqualTo(2);
        assertThat(integer(c.call("HLEN", "h"))).isEqualTo(1);
        assertThat(integer(c.call("HINCRBY", "h", "counter", "5"))).isEqualTo(5);
        assertThat(integer(c.call("HINCRBY", "h", "counter", "-2"))).isEqualTo(3);
    }

    @Test
    void hashEncodingConvertsAtThreshold() throws IOException {
        // hash-max-listpack-entries defaults to 128.
        c.call("HSET", "small", "f", "v");
        assertThat(bulk(c.call("OBJECT", "ENCODING", "small"))).isEqualTo("listpack");

        String[] args = new String[2 + 2 * 200];
        args[0] = "HSET";
        args[1] = "big";
        for (int i = 0; i < 200; i++) {
            args[2 + i * 2] = "field" + i;
            args[3 + i * 2] = "value" + i;
        }
        c.call(args);
        assertThat(bulk(c.call("OBJECT", "ENCODING", "big"))).isEqualTo("hashtable");
    }

    // ---- generic keys -------------------------------------------------------

    @Test
    void typeDelExistsAndObjectEncoding() throws IOException {
        c.call("SET", "s", "12345");
        c.call("HSET", "h", "f", "v");
        assertThat(simple(c.call("TYPE", "s"))).isEqualTo("string");
        assertThat(simple(c.call("TYPE", "h"))).isEqualTo("hash");
        assertThat(simple(c.call("TYPE", "missing"))).isEqualTo("none");
        assertThat(bulk(c.call("OBJECT", "ENCODING", "s"))).isEqualTo("int");

        assertThat(integer(c.call("EXISTS", "s", "h", "missing"))).isEqualTo(2);
        assertThat(integer(c.call("EXISTS", "s", "s"))).isEqualTo(2); // multiplicity
        assertThat(integer(c.call("DEL", "s", "h", "missing"))).isEqualTo(2);
    }

    @Test
    void renameCopyAndKeys() throws IOException {
        c.call("SET", "a", "1");
        assertThat(simple(c.call("RENAME", "a", "b"))).isEqualTo("OK");
        assertThat(integer(c.call("EXISTS", "a"))).isEqualTo(0);
        assertThat(bulk(c.call("GET", "b"))).isEqualTo("1");
        assertThat(error(c.call("RENAME", "missing", "x"))).isEqualTo("ERR no such key");

        assertThat(integer(c.call("COPY", "b", "c"))).isEqualTo(1);
        assertThat(integer(c.call("COPY", "b", "c"))).isEqualTo(0); // exists, no REPLACE
        assertThat(integer(c.call("COPY", "b", "c", "REPLACE"))).isEqualTo(1);

        c.call("SET", "user:1", "x");
        c.call("SET", "user:2", "y");
        List<RespValue> keys = ((RespValue.Array) c.call("KEYS", "user:*")).items();
        assertThat(keys).hasSize(2);
    }

    @Test
    void selectIsolatesDatabases() throws IOException {
        c.call("SET", "k", "in-db0");
        assertThat(simple(c.call("SELECT", "1"))).isEqualTo("OK");
        assertThat(isNil(c.call("GET", "k"))).isTrue();
        c.call("SET", "k", "in-db1");
        assertThat(bulk(c.call("GET", "k"))).isEqualTo("in-db1");
        c.call("SELECT", "0");
        assertThat(bulk(c.call("GET", "k"))).isEqualTo("in-db0");
        assertThat(error(c.call("SELECT", "999"))).isEqualTo("ERR DB index is out of range");
    }
}
