package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end Lua scripting coverage: value conversions both ways, KEYS/ARGV,
 * redis.call/pcall, error and status replies, the script cache (EVALSHA / SCRIPT),
 * and the sandbox.
 */
class JediCoreScriptingIntegrationTest {

    private JediCore server;

    @BeforeEach
    void start() throws InterruptedException {
        server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private RespTestClient client() throws IOException {
        return new RespTestClient(server.port());
    }

    private static String str(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static List<RespValue> arr(RespValue v) {
        return ((RespValue.Array) v).items();
    }

    @Test
    void numberStringAndTableConversions() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("EVAL", "return 42", "0")).isEqualTo(RespValue.integer(42));
            assertThat(str(c.call("EVAL", "return 'hello'", "0"))).isEqualTo("hello");
            // A numeric string must stay a bulk string, not become an integer.
            assertThat(c.call("EVAL", "return '123'", "0")).isInstanceOf(RespValue.BulkString.class);
            assertThat(c.call("EVAL", "return true", "0")).isEqualTo(RespValue.integer(1));
            assertThat(c.call("EVAL", "return false", "0")).isInstanceOf(RespValue.Null.class);
            assertThat(c.call("EVAL", "return", "0")).isInstanceOf(RespValue.Null.class);

            List<RespValue> table = arr(c.call("EVAL", "return {1, 2, 3, 'four'}", "0"));
            assertThat(table.get(0)).isEqualTo(RespValue.integer(1));
            assertThat(str(table.get(3))).isEqualTo("four");
            // A nil terminates the array.
            assertThat(arr(c.call("EVAL", "return {1, 2, nil, 4}", "0"))).hasSize(2);
        }
    }

    @Test
    void keysAndArgvAreBound() throws Exception {
        try (RespTestClient c = client()) {
            RespValue r = c.call("EVAL", "return {KEYS[1], KEYS[2], ARGV[1]}", "2", "k1", "k2", "a1");
            List<RespValue> items = arr(r);
            assertThat(str(items.get(0))).isEqualTo("k1");
            assertThat(str(items.get(1))).isEqualTo("k2");
            assertThat(str(items.get(2))).isEqualTo("a1");
        }
    }

    @Test
    void scriptManipulatesKeysViaRedisCall() throws Exception {
        try (RespTestClient c = client()) {
            RespValue set = c.call("EVAL",
                    "redis.call('set', KEYS[1], ARGV[1]); return redis.call('get', KEYS[1])",
                    "1", "greeting", "hi there");
            assertThat(str(set)).isEqualTo("hi there");
            // The write is visible outside the script.
            assertThat(str(c.call("GET", "greeting"))).isEqualTo("hi there");

            // A status reply from redis.call round-trips as a status reply.
            assertThat(c.call("EVAL", "return redis.call('set', KEYS[1], '1')", "1", "x"))
                    .isEqualTo(RespValue.OK);
        }
    }

    @Test
    void incrementLoopInLua() throws Exception {
        try (RespTestClient c = client()) {
            RespValue r = c.call("EVAL",
                    "for i=1,10 do redis.call('incr', KEYS[1]) end return redis.call('get', KEYS[1])",
                    "1", "counter");
            assertThat(str(r)).isEqualTo("10");
        }
    }

    @Test
    void errorAndStatusReplyHelpers() throws Exception {
        try (RespTestClient c = client()) {
            RespValue err = c.call("EVAL", "return redis.error_reply('My Error')", "0");
            assertThat(err).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) err).message()).isEqualTo("My Error");

            assertThat(c.call("EVAL", "return redis.status_reply('GOOD')", "0"))
                    .isEqualTo(RespValue.simple("GOOD"));
        }
    }

    @Test
    void redisCallErrorAbortsScript() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "str", "not-a-number");
            RespValue r = c.call("EVAL", "return redis.call('incr', KEYS[1])", "1", "str");
            assertThat(r).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) r).message()).contains("not an integer");
        }
    }

    @Test
    void redisPcallCatchesError() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "str", "not-a-number");
            RespValue r = c.call("EVAL",
                    "local res = redis.pcall('incr', KEYS[1]); if res.err then return 'caught' else return res end",
                    "1", "str");
            assertThat(str(r)).isEqualTo("caught");
        }
    }

    @Test
    void scriptLoadEvalshaAndExists() throws Exception {
        try (RespTestClient c = client()) {
            String sha = str(c.call("SCRIPT", "LOAD", "return 'cached'"));
            assertThat(sha).hasSize(40);

            assertThat(str(c.call("EVALSHA", sha, "0"))).isEqualTo("cached");

            List<RespValue> exists = arr(c.call("SCRIPT", "EXISTS", sha, "0000000000000000000000000000000000000000"));
            assertThat(exists.get(0)).isEqualTo(RespValue.integer(1));
            assertThat(exists.get(1)).isEqualTo(RespValue.integer(0));
        }
    }

    @Test
    void evalshaUnknownReturnsNoscript() throws Exception {
        try (RespTestClient c = client()) {
            RespValue r = c.call("EVALSHA", "0000000000000000000000000000000000000000", "0");
            assertThat(r).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) r).message()).startsWith("NOSCRIPT");
        }
    }

    @Test
    void evalCachesSoEvalshaFindsIt() throws Exception {
        try (RespTestClient c = client()) {
            // SHA-1 of "return 7"
            c.call("EVAL", "return 7", "0");
            String sha = str(c.call("SCRIPT", "LOAD", "return 7"));
            assertThat(c.call("EVALSHA", sha, "0")).isEqualTo(RespValue.integer(7));

            c.call("SCRIPT", "FLUSH");
            assertThat(c.call("SCRIPT", "EXISTS", sha)).isEqualTo(
                    new RespValue.Array(List.of(RespValue.integer(0))));
        }
    }

    @Test
    void sha1hexMatchesKnownVector() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(str(c.call("EVAL", "return redis.sha1hex('')", "0")))
                    .isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709");
        }
    }

    @Test
    void sandboxBlocksGlobalsAndUnsafeLibraries() throws Exception {
        try (RespTestClient c = client()) {
            RespValue globalWrite = c.call("EVAL", "x = 5 return x", "0");
            assertThat(globalWrite).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) globalWrite).message()).contains("global");

            // os was removed from the sandbox, so indexing it errors.
            assertThat(c.call("EVAL", "return os.time()", "0")).isInstanceOf(RespValue.SimpleError.class);
        }
    }

    @Test
    void noScriptCommandsAreRejected() throws Exception {
        try (RespTestClient c = client()) {
            RespValue r = c.call("EVAL", "return redis.call('subscribe', 'ch')", "0");
            assertThat(r).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) r).message()).contains("not allowed from script");
        }
    }

    @Test
    void numkeysValidation() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("EVAL", "return 1", "-1")).isInstanceOf(RespValue.SimpleError.class);
            assertThat(c.call("EVAL", "return 1", "5", "onlyonekey")).isInstanceOf(RespValue.SimpleError.class);
        }
    }
}
