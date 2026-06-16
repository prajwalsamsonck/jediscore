package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the {@code INFO} sections and that the live stats counters move. */
class JediCoreInfoIntegrationTest {

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

    private static String infoText(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    /** Parses {@code key:value} lines (ignoring {@code # Section} headers) into a map. */
    private static Map<String, String> fields(String info) {
        Map<String, String> map = new HashMap<>();
        for (String line : info.split("\r\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                map.put(line.substring(0, colon), line.substring(colon + 1));
            }
        }
        return map;
    }

    @Test
    void infoHasAllSections() throws Exception {
        try (RespTestClient c = client()) {
            String info = infoText(c.call("INFO"));
            assertThat(info).contains("# Server", "# Clients", "# Memory", "# Persistence",
                    "# Stats", "# Replication", "# CPU", "# Cluster", "# Keyspace");
        }
    }

    @Test
    void serverSectionReportsIdentity() throws Exception {
        try (RespTestClient c = client()) {
            Map<String, String> f = fields(infoText(c.call("INFO", "server")));
            assertThat(f.get("redis_version")).isEqualTo("7.4.0");
            assertThat(f.get("redis_mode")).isEqualTo("standalone");
            assertThat(f.get("run_id")).hasSize(40);
            assertThat(Long.parseLong(f.get("uptime_in_seconds"))).isGreaterThanOrEqualTo(0);
            assertThat(f).doesNotContainKey("db0"); // section filter excludes Keyspace
        }
    }

    @Test
    void statsReflectCommandActivity() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "k", "v");
            c.call("GET", "k");        // hit
            c.call("GET", "missing");  // miss
            Map<String, String> f = fields(infoText(c.call("INFO", "stats")));
            assertThat(Long.parseLong(f.get("total_commands_processed"))).isGreaterThan(0);
            assertThat(Long.parseLong(f.get("keyspace_hits"))).isGreaterThanOrEqualTo(1);
            assertThat(Long.parseLong(f.get("keyspace_misses"))).isGreaterThanOrEqualTo(1);
            assertThat(f).containsKey("instantaneous_ops_per_sec");
        }
    }

    @Test
    void clientsAndMemoryAreLive() throws Exception {
        try (RespTestClient c = client()) {
            Map<String, String> f = fields(infoText(c.call("INFO")));
            assertThat(Integer.parseInt(f.get("connected_clients"))).isGreaterThanOrEqualTo(1);
            assertThat(Long.parseLong(f.get("used_memory"))).isGreaterThanOrEqualTo(0);
            assertThat(f.get("maxmemory_policy")).isEqualTo("noeviction");
            assertThat(f.get("mem_allocator")).isEqualTo("jvm");
            assertThat(f.get("total_connections_received")).isNotNull();
        }
    }

    @Test
    void keyspaceSectionCountsKeys() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "a", "1");
            c.call("SET", "b", "2");
            c.call("SET", "c", "3");
            c.call("EXPIRE", "c", "1000");
            Map<String, String> f = fields(infoText(c.call("INFO", "keyspace")));
            assertThat(f.get("db0")).isEqualTo("keys=3,expires=1,avg_ttl=0");
        }
    }
}
