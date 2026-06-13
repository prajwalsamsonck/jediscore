package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.MaxmemoryPolicy;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** End-to-end coverage of maxmemory eviction, OOM, MEMORY, and OBJECT FREQ/IDLETIME. */
class JediCoreEvictionIntegrationTest {

    private static String bulk(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static long integer(RespValue v) {
        return ((RespValue.Integer) v).value();
    }

    private static String error(RespValue v) {
        return ((RespValue.SimpleError) v).message();
    }

    private interface ClientTest {
        void run(RespTestClient client) throws IOException;
    }

    /** Starts a server with the given config, runs the body, and tears down. */
    private static void withServer(ServerConfig config, ClientTest body) throws Exception {
        JediCore server = JediCore.start(config);
        try (RespTestClient c = new RespTestClient(server.port())) {
            body.run(c);
        } finally {
            server.close();
        }
    }

    @Test
    void evictionKeepsMemoryBounded() throws Exception {
        ServerConfig config = ServerConfig.defaults("127.0.0.1", 0)
                .withMaxMemory(4000, MaxmemoryPolicy.ALLKEYS_RANDOM);
        withServer(config, c -> {
            for (int i = 0; i < 500; i++) {
                c.call("SET", "key" + i, "value" + i);
            }
            long dbsize = integer(c.call("DBSIZE"));
            assertThat(dbsize).as("eviction should cap the key count").isLessThan(500).isGreaterThan(0);
        });
    }

    @Test
    void noevictionReturnsOomWhenFull() throws Exception {
        ServerConfig config = ServerConfig.defaults("127.0.0.1", 0)
                .withMaxMemory(2000, MaxmemoryPolicy.NOEVICTION);
        withServer(config, c -> {
            boolean sawOom = false;
            for (int i = 0; i < 1000 && !sawOom; i++) {
                RespValue reply = c.call("SET", "key" + i, "value" + i);
                if (reply instanceof RespValue.SimpleError e) {
                    assertThat(e.message()).startsWith("OOM");
                    sawOom = true;
                }
            }
            assertThat(sawOom).as("noeviction must eventually refuse writes with OOM").isTrue();
        });
    }

    @Test
    void memoryUsageAndDoctor() throws Exception {
        withServer(ServerConfig.defaults("127.0.0.1", 0), c -> {
            c.call("SET", "k", "hello");
            assertThat(integer(c.call("MEMORY", "USAGE", "k"))).isGreaterThan(0);
            assertThat(c.call("MEMORY", "USAGE", "missing")).isInstanceOf(RespValue.Null.class);
            assertThat(bulk(c.call("MEMORY", "DOCTOR"))).contains("memory");
        });
    }

    @Test
    void objectFreqRequiresLfuPolicy() throws Exception {
        // Default policy (noeviction): FREQ errors, IDLETIME works.
        withServer(ServerConfig.defaults("127.0.0.1", 0), c -> {
            c.call("SET", "k", "v");
            assertThat(error(c.call("OBJECT", "FREQ", "k"))).startsWith("ERR An LFU");
            assertThat(integer(c.call("OBJECT", "IDLETIME", "k"))).isGreaterThanOrEqualTo(0);
        });
        // LFU policy: FREQ works, IDLETIME errors.
        ServerConfig lfu = ServerConfig.defaults("127.0.0.1", 0).withMaxMemory(0, MaxmemoryPolicy.ALLKEYS_LFU);
        withServer(lfu, c -> {
            c.call("SET", "k", "v");
            assertThat(integer(c.call("OBJECT", "FREQ", "k"))).isGreaterThanOrEqualTo(0);
            assertThat(error(c.call("OBJECT", "IDLETIME", "k"))).startsWith("ERR An LFU");
        });
    }
}
