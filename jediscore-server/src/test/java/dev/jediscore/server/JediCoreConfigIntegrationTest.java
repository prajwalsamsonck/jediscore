package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coverage for CONFIG GET/SET/RESETSTAT/REWRITE and SHUTDOWN (embedded mode). */
class JediCoreConfigIntegrationTest {

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

    /** CONFIG GET decodes (in RESP2) as a flat array of name/value pairs. */
    private static Map<String, String> pairs(RespValue v) {
        List<RespValue> items = ((RespValue.Array) v).items();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < items.size(); i += 2) {
            map.put(str(items.get(i)), str(items.get(i + 1)));
        }
        return map;
    }

    private static String str(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    @Test
    void configGetSetMaxmemory() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("CONFIG", "SET", "maxmemory", "100mb")).isEqualTo(RespValue.OK);
            assertThat(pairs(c.call("CONFIG", "GET", "maxmemory")).get("maxmemory"))
                    .isEqualTo(Long.toString(100L * 1024 * 1024));
            // The live config was swapped, so the engine sees the new limit.
            assertThat(server.context().config().maxMemory()).isEqualTo(100L * 1024 * 1024);

            assertThat(c.call("CONFIG", "SET", "maxmemory-policy", "allkeys-lru")).isEqualTo(RespValue.OK);
            assertThat(pairs(c.call("CONFIG", "GET", "maxmemory-policy")).get("maxmemory-policy"))
                    .isEqualTo("allkeys-lru");
        }
    }

    @Test
    void configGetSupportsGlob() throws Exception {
        try (RespTestClient c = client()) {
            Map<String, String> m = pairs(c.call("CONFIG", "GET", "maxmemory*"));
            assertThat(m).containsKeys("maxmemory", "maxmemory-policy", "maxmemory-samples");
        }
    }

    @Test
    void configSetSlowlogThresholdTakesEffect() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("CONFIG", "SET", "slowlog-log-slower-than", "0")).isEqualTo(RespValue.OK);
            c.call("SET", "k", "v"); // threshold 0 logs everything
            assertThat(((RespValue.Integer) c.call("SLOWLOG", "LEN")).value()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void configSetUnknownParamErrors() throws Exception {
        try (RespTestClient c = client()) {
            RespValue r = c.call("CONFIG", "SET", "not-a-real-param", "1");
            assertThat(r).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) r).message()).contains("Unknown option");
        }
    }

    @Test
    void configResetstatClearsCounters() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "k", "v");
            c.call("GET", "k"); // a hit
            assertThat(server.context().stats().keyspaceHits()).isGreaterThanOrEqualTo(1);
            assertThat(c.call("CONFIG", "RESETSTAT")).isEqualTo(RespValue.OK);
            assertThat(server.context().stats().keyspaceHits()).isZero();
        }
    }

    @Test
    void configRewriteWritesTheFile(@TempDir Path dir) throws Exception {
        Path conf = dir.resolve("redis.conf");
        Files.writeString(conf, "# initial\n");
        server.context().setConfigFile(conf.toString());
        try (RespTestClient c = client()) {
            c.call("CONFIG", "SET", "maxmemory", "5mb");
            assertThat(c.call("CONFIG", "REWRITE")).isEqualTo(RespValue.OK);
            String written = Files.readString(conf);
            assertThat(written).contains("maxmemory " + (5L * 1024 * 1024));
        }
    }

    @Test
    void configRewriteWithoutFileErrors() throws Exception {
        try (RespTestClient c = client()) {
            RespValue r = c.call("CONFIG", "REWRITE");
            assertThat(r).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) r).message()).contains("without a config file");
        }
    }

    @Test
    void shutdownNosaveInEmbeddedModeRepliesOk() throws Exception {
        try (RespTestClient c = client()) {
            // standalone=false (default for an embedded instance), so the JVM is not killed.
            assertThat(c.call("SHUTDOWN", "NOSAVE")).isEqualTo(RespValue.OK);
            assertThat(server.context().saveOnShutdown()).isFalse();
        }
    }
}
