package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end coverage of the Phase-2B list and set commands and their encodings. */
class JediCoreListSetIntegrationTest {

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

    private static List<String> arr(RespValue v) {
        return ((RespValue.Array) v).items().stream().map(JediCoreListSetIntegrationTest::bulk).toList();
    }

    // ---- lists --------------------------------------------------------------

    @Test
    void pushRangePopOrder() throws IOException {
        assertThat(integer(c.call("RPUSH", "l", "a", "b", "c"))).isEqualTo(3);
        assertThat(integer(c.call("LPUSH", "l", "z"))).isEqualTo(4);
        assertThat(arr(c.call("LRANGE", "l", "0", "-1"))).containsExactly("z", "a", "b", "c");
        assertThat(bulk(c.call("LPOP", "l"))).isEqualTo("z");
        assertThat(bulk(c.call("RPOP", "l"))).isEqualTo("c");
        assertThat(arr(c.call("LPOP", "l", "5"))).containsExactly("a", "b");
        assertThat(integer(c.call("EXISTS", "l"))).isEqualTo(0); // emptied list is deleted
    }

    @Test
    void indexSetInsertRemoveTrimMove() throws IOException {
        c.call("RPUSH", "l", "a", "b", "c", "d");
        assertThat(bulk(c.call("LINDEX", "l", "1"))).isEqualTo("b");
        assertThat(simple(c.call("LSET", "l", "1", "B"))).isEqualTo("OK");
        assertThat(integer(c.call("LINSERT", "l", "BEFORE", "c", "bc"))).isEqualTo(5);
        assertThat(arr(c.call("LRANGE", "l", "0", "-1"))).containsExactly("a", "B", "bc", "c", "d");
        assertThat(integer(c.call("LREM", "l", "0", "bc"))).isEqualTo(1);
        assertThat(simple(c.call("LTRIM", "l", "1", "2"))).isEqualTo("OK");
        assertThat(arr(c.call("LRANGE", "l", "0", "-1"))).containsExactly("B", "c");

        c.call("RPUSH", "src", "x", "y");
        assertThat(bulk(c.call("RPOPLPUSH", "src", "dst"))).isEqualTo("y");
        assertThat(bulk(c.call("LMOVE", "src", "dst", "LEFT", "RIGHT"))).isEqualTo("x");
        assertThat(arr(c.call("LRANGE", "dst", "0", "-1"))).containsExactly("y", "x");

        assertThat(error(c.call("LSET", "missing", "0", "v"))).isEqualTo("ERR no such key");
    }

    @Test
    void lposVariants() throws IOException {
        c.call("RPUSH", "l", "a", "b", "c", "b", "b");
        assertThat(integer(c.call("LPOS", "l", "b"))).isEqualTo(1);
        assertThat(arr2longs(c.call("LPOS", "l", "b", "COUNT", "0"))).containsExactly(1L, 3L, 4L);
        assertThat(integer(c.call("LPOS", "l", "b", "RANK", "-1"))).isEqualTo(4);
    }

    @Test
    void listEncodingConversion() throws IOException {
        c.call("RPUSH", "small", "a", "b");
        assertThat(bulk(c.call("OBJECT", "ENCODING", "small"))).isEqualTo("listpack");
        String[] big = new String[2 + 200];
        big[0] = "RPUSH";
        big[1] = "big";
        for (int i = 0; i < 200; i++) {
            big[2 + i] = "e" + i;
        }
        c.call(big);
        assertThat(bulk(c.call("OBJECT", "ENCODING", "big"))).isEqualTo("quicklist");
    }

    // ---- sets ---------------------------------------------------------------

    @Test
    void setBasicsAndMembership() throws IOException {
        assertThat(integer(c.call("SADD", "s", "a", "b", "c", "a"))).isEqualTo(3);
        assertThat(integer(c.call("SCARD", "s"))).isEqualTo(3);
        assertThat(integer(c.call("SISMEMBER", "s", "b"))).isEqualTo(1);
        assertThat(integer(c.call("SISMEMBER", "s", "x"))).isEqualTo(0);
        assertThat(arr(c.call("SMEMBERS", "s"))).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(arr2longs(c.call("SMISMEMBER", "s", "a", "x"))).containsExactly(1L, 0L);
        assertThat(integer(c.call("SREM", "s", "a", "x"))).isEqualTo(1);
    }

    @Test
    void setAlgebra() throws IOException {
        c.call("SADD", "s1", "a", "b", "c", "d");
        c.call("SADD", "s2", "c", "d", "e");
        assertThat(arr(c.call("SUNION", "s1", "s2"))).containsExactlyInAnyOrder("a", "b", "c", "d", "e");
        assertThat(arr(c.call("SINTER", "s1", "s2"))).containsExactlyInAnyOrder("c", "d");
        assertThat(arr(c.call("SDIFF", "s1", "s2"))).containsExactlyInAnyOrder("a", "b");
        assertThat(integer(c.call("SINTERCARD", "2", "s1", "s2"))).isEqualTo(2);
        assertThat(integer(c.call("SINTERCARD", "2", "s1", "s2", "LIMIT", "1"))).isEqualTo(1);

        assertThat(integer(c.call("SINTERSTORE", "dst", "s1", "s2"))).isEqualTo(2);
        assertThat(arr(c.call("SMEMBERS", "dst"))).containsExactlyInAnyOrder("c", "d");
    }

    @Test
    void setEncodings() throws IOException {
        c.call("SADD", "ints", "1", "2", "3");
        assertThat(bulk(c.call("OBJECT", "ENCODING", "ints"))).isEqualTo("intset");
        c.call("SADD", "ints", "hello");
        assertThat(bulk(c.call("OBJECT", "ENCODING", "ints"))).isEqualTo("listpack");

        String[] big = new String[2 + 200];
        big[0] = "SADD";
        big[1] = "big";
        for (int i = 0; i < 200; i++) {
            big[2 + i] = "m" + i;
        }
        c.call(big);
        assertThat(bulk(c.call("OBJECT", "ENCODING", "big"))).isEqualTo("hashtable");
    }

    @Test
    void spopAndMove() throws IOException {
        c.call("SADD", "s", "a", "b", "c");
        String popped = bulk(c.call("SPOP", "s"));
        assertThat(popped).isIn("a", "b", "c");
        assertThat(integer(c.call("SCARD", "s"))).isEqualTo(2);

        c.call("SADD", "src", "m");
        assertThat(integer(c.call("SMOVE", "src", "dst", "m"))).isEqualTo(1);
        assertThat(integer(c.call("SISMEMBER", "dst", "m"))).isEqualTo(1);
        assertThat(integer(c.call("EXISTS", "src"))).isEqualTo(0);
    }

    @Test
    void wrongTypeAcrossCollections() throws IOException {
        c.call("SET", "str", "v");
        assertThat(error(c.call("LPUSH", "str", "x"))).startsWith("WRONGTYPE");
        assertThat(error(c.call("SADD", "str", "x"))).startsWith("WRONGTYPE");
    }

    private static List<Long> arr2longs(RespValue v) {
        return ((RespValue.Array) v).items().stream()
                .map(i -> ((RespValue.Integer) i).value())
                .toList();
    }
}
