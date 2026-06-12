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

/** End-to-end coverage of Phase-2C sorted sets, expiration, and SWAPDB. */
class JediCoreZSetIntegrationTest {

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

    private static boolean isNil(RespValue v) {
        return v instanceof RespValue.Null;
    }

    private static List<String> arr(RespValue v) {
        return ((RespValue.Array) v).items().stream().map(JediCoreZSetIntegrationTest::bulk).toList();
    }

    // ---- sorted sets --------------------------------------------------------

    @Test
    void zaddScoreRankIncr() throws IOException {
        assertThat(integer(c.call("ZADD", "z", "1", "a", "2", "b", "3", "c"))).isEqualTo(3);
        assertThat(bulk(c.call("ZSCORE", "z", "b"))).isEqualTo("2");
        assertThat(integer(c.call("ZCARD", "z"))).isEqualTo(3);
        assertThat(integer(c.call("ZRANK", "z", "c"))).isEqualTo(2);
        assertThat(integer(c.call("ZREVRANK", "z", "c"))).isEqualTo(0);
        assertThat(bulk(c.call("ZINCRBY", "z", "5", "a"))).isEqualTo("6");
        assertThat(integer(c.call("ZREM", "z", "a", "x"))).isEqualTo(1);
    }

    @Test
    void zaddFlags() throws IOException {
        assertThat(integer(c.call("ZADD", "z", "NX", "1", "a"))).isEqualTo(1);
        assertThat(integer(c.call("ZADD", "z", "NX", "9", "a"))).isEqualTo(0); // exists -> not updated
        assertThat(bulk(c.call("ZSCORE", "z", "a"))).isEqualTo("1");
        assertThat(integer(c.call("ZADD", "z", "XX", "CH", "5", "a"))).isEqualTo(1); // changed
        assertThat(integer(c.call("ZADD", "z", "GT", "CH", "3", "a"))).isEqualTo(0); // 3<5, GT blocks
        assertThat(integer(c.call("ZADD", "z", "GT", "CH", "9", "a"))).isEqualTo(1);
        assertThat(bulk(c.call("ZADD", "z", "INCR", "1", "a"))).isEqualTo("10");
    }

    @Test
    void zrangeVariants() throws IOException {
        c.call("ZADD", "z", "1", "a", "2", "b", "3", "c", "4", "d");
        assertThat(arr(c.call("ZRANGE", "z", "0", "-1"))).containsExactly("a", "b", "c", "d");
        assertThat(arr(c.call("ZRANGE", "z", "0", "1", "REV"))).containsExactly("d", "c");
        assertThat(arr(c.call("ZRANGE", "z", "0", "-1", "WITHSCORES")))
                .containsExactly("a", "1", "b", "2", "c", "3", "d", "4");
        assertThat(arr(c.call("ZRANGEBYSCORE", "z", "2", "3"))).containsExactly("b", "c");
        assertThat(arr(c.call("ZRANGEBYSCORE", "z", "(1", "3"))).containsExactly("b", "c");
        assertThat(arr(c.call("ZRANGEBYSCORE", "z", "-inf", "+inf", "LIMIT", "1", "2")))
                .containsExactly("b", "c");
        assertThat(integer(c.call("ZCOUNT", "z", "2", "3"))).isEqualTo(2);
    }

    @Test
    void zrangeByLex() throws IOException {
        c.call("ZADD", "z", "0", "a", "0", "b", "0", "c", "0", "d");
        assertThat(arr(c.call("ZRANGEBYLEX", "z", "[b", "[c"))).containsExactly("b", "c");
        assertThat(arr(c.call("ZRANGEBYLEX", "z", "-", "+"))).containsExactly("a", "b", "c", "d");
        assertThat(arr(c.call("ZRANGEBYLEX", "z", "(a", "(d"))).containsExactly("b", "c");
        assertThat(integer(c.call("ZLEXCOUNT", "z", "[b", "+"))).isEqualTo(3);
    }

    @Test
    void zpopAndStore() throws IOException {
        c.call("ZADD", "z", "1", "a", "2", "b", "3", "c");
        assertThat(arr(c.call("ZPOPMIN", "z"))).containsExactly("a", "1");
        assertThat(arr(c.call("ZPOPMAX", "z"))).containsExactly("c", "3");

        c.call("ZADD", "src", "1", "a", "2", "b", "3", "c");
        assertThat(integer(c.call("ZRANGESTORE", "dst", "src", "0", "1"))).isEqualTo(2);
        assertThat(arr(c.call("ZRANGE", "dst", "0", "-1"))).containsExactly("a", "b");
    }

    @Test
    void zsetAlgebra() throws IOException {
        c.call("ZADD", "z1", "1", "a", "2", "b");
        c.call("ZADD", "z2", "10", "b", "20", "c");
        // sorted by score ascending: a(1), b(2+10=12), c(20)
        assertThat(arr(c.call("ZUNION", "2", "z1", "z2", "WITHSCORES")))
                .containsExactly("a", "1", "b", "12", "c", "20");
        assertThat(arr(c.call("ZINTER", "2", "z1", "z2", "WITHSCORES"))).containsExactly("b", "12");
        assertThat(arr(c.call("ZDIFF", "2", "z1", "z2"))).containsExactly("a");
        assertThat(integer(c.call("ZUNIONSTORE", "out", "2", "z1", "z2", "WEIGHTS", "1", "2")))
                .isEqualTo(3);
        // b = 2*1 + 10*2 = 22; ordering by score: a(1), c(40), b(22) -> a,b,c
        assertThat(arr(c.call("ZRANGE", "out", "0", "-1", "WITHSCORES")))
                .containsExactly("a", "1", "b", "22", "c", "40");
        assertThat(integer(c.call("ZINTERCARD", "2", "z1", "z2"))).isEqualTo(1);
    }

    @Test
    void zsetEncodingConversion() throws IOException {
        c.call("ZADD", "small", "1", "a");
        assertThat(bulk(c.call("OBJECT", "ENCODING", "small"))).isEqualTo("listpack");
        String[] args = new String[2 + 2 * 200];
        args[0] = "ZADD";
        args[1] = "big";
        for (int i = 0; i < 200; i++) {
            args[2 + i * 2] = Integer.toString(i);
            args[3 + i * 2] = "m" + i;
        }
        c.call(args);
        assertThat(bulk(c.call("OBJECT", "ENCODING", "big"))).isEqualTo("skiplist");
    }

    // ---- expiration & SWAPDB ------------------------------------------------

    @Test
    void expireTtlPersist() throws IOException {
        c.call("SET", "k", "v");
        assertThat(integer(c.call("EXPIRE", "k", "100"))).isEqualTo(1);
        long ttl = integer(c.call("TTL", "k"));
        assertThat(ttl).isBetween(90L, 100L);
        assertThat(integer(c.call("PERSIST", "k"))).isEqualTo(1);
        assertThat(integer(c.call("TTL", "k"))).isEqualTo(-1);
        assertThat(integer(c.call("TTL", "missing"))).isEqualTo(-2);
        assertThat(integer(c.call("EXPIRE", "missing", "100"))).isEqualTo(0);
    }

    @Test
    void pexpireActuallyExpires() throws IOException, InterruptedException {
        c.call("SET", "k", "v");
        c.call("PEXPIRE", "k", "40");
        Thread.sleep(80);
        assertThat(integer(c.call("EXISTS", "k"))).isEqualTo(0);
    }

    @Test
    void swapdb() throws IOException {
        c.call("SET", "k", "in-db0");
        assertThat(simple(c.call("SWAPDB", "0", "1"))).isEqualTo("OK");
        assertThat(isNil(c.call("GET", "k"))).isTrue(); // db0 is now the (empty) old db1
        c.call("SELECT", "1");
        assertThat(bulk(c.call("GET", "k"))).isEqualTo("in-db0");
    }
}
