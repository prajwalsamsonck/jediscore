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

/** Coverage for SLOWLOG, LATENCY, MONITOR, DEBUG (SLEEP/OBJECT/SET-ACTIVE-EXPIRE), and COMMAND. */
class JediCoreDiagnosticsIntegrationTest {

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
    void slowlogCapturesSlowCommands() throws Exception {
        try (RespTestClient c = client()) {
            c.call("DEBUG", "SLEEP", "0.05"); // 50ms ≫ the 10ms default threshold
            assertThat(((RespValue.Integer) c.call("SLOWLOG", "LEN")).value()).isGreaterThanOrEqualTo(1);

            List<RespValue> entries = arr(c.call("SLOWLOG", "GET"));
            List<RespValue> entry = arr(entries.get(0));
            assertThat(((RespValue.Integer) entry.get(2)).value()).isGreaterThan(40_000); // ~50ms in micros
            List<RespValue> loggedArgs = arr(entry.get(3));
            assertThat(loggedArgs).map(JediCoreDiagnosticsIntegrationTest::str)
                    .containsExactly("DEBUG", "SLEEP", "0.05");

            assertThat(c.call("SLOWLOG", "RESET")).isEqualTo(RespValue.OK);
            assertThat(c.call("SLOWLOG", "LEN")).isEqualTo(RespValue.integer(0));
        }
    }

    @Test
    void latencyTracksSlowCommands() throws Exception {
        server.context().latencyMonitor().setThresholdMillis(10); // enable monitoring
        try (RespTestClient c = client()) {
            // No events yet.
            assertThat(arr(c.call("LATENCY", "LATEST"))).isEmpty();
            assertThat(str(c.call("LATENCY", "DOCTOR"))).contains("no worrying latency");

            c.call("DEBUG", "SLEEP", "0.05");
            List<RespValue> latest = arr(c.call("LATENCY", "LATEST"));
            assertThat(latest).isNotEmpty();
            assertThat(str(arr(latest.get(0)).get(0))).isEqualTo("command");

            assertThat(arr(c.call("LATENCY", "HISTORY", "command"))).isNotEmpty();
            assertThat(((RespValue.Integer) c.call("LATENCY", "RESET")).value()).isGreaterThanOrEqualTo(1);
            assertThat(arr(c.call("LATENCY", "LATEST"))).isEmpty();
        }
    }

    @Test
    void monitorReceivesTheCommandFeed() throws Exception {
        try (RespTestClient monitor = client(); RespTestClient worker = client()) {
            assertThat(monitor.call("MONITOR")).isEqualTo(RespValue.OK); // registered on return

            worker.call("SET", "foo", "bar");

            RespValue line = monitor.receive();
            String text = ((RespValue.SimpleString) line).value();
            assertThat(text).contains("\"SET\"", "\"foo\"", "\"bar\"");
            assertThat(text).contains("[0 "); // db 0 and the client address
        }
    }

    @Test
    void commandIntrospectionAndKeySpecs() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(((RespValue.Integer) c.call("COMMAND", "COUNT")).value()).isGreaterThan(50);

            List<RespValue> info = arr(c.call("COMMAND", "INFO", "set"));
            List<RespValue> setInfo = arr(info.get(0));
            assertThat(str(setInfo.get(0))).isEqualTo("set");
            assertThat(((RespValue.Integer) setInfo.get(3)).value()).isEqualTo(1); // first key
            assertThat(((RespValue.Integer) setInfo.get(5)).value()).isEqualTo(1); // step

            assertThat(arr(c.call("COMMAND", "GETKEYS", "SET", "foo", "bar")))
                    .map(JediCoreDiagnosticsIntegrationTest::str).containsExactly("foo");
            assertThat(arr(c.call("COMMAND", "GETKEYS", "MSET", "k1", "v1", "k2", "v2")))
                    .map(JediCoreDiagnosticsIntegrationTest::str).containsExactly("k1", "k2");
            assertThat(arr(c.call("COMMAND", "GETKEYS", "EVAL", "return 1", "2", "ka", "kb")))
                    .map(JediCoreDiagnosticsIntegrationTest::str).containsExactly("ka", "kb");

            assertThat(c.call("COMMAND", "GETKEYS", "PING")).isInstanceOf(RespValue.SimpleError.class);
        }
    }

    @Test
    void debugObjectReportsEncoding() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "k", "12345"); // an int-encoded string
            String info = ((RespValue.SimpleString) c.call("DEBUG", "OBJECT", "k")).value();
            assertThat(info).contains("encoding:").contains("refcount:1").contains("serializedlength:");

            assertThat(c.call("DEBUG", "OBJECT", "missing")).isInstanceOf(RespValue.SimpleError.class);
        }
    }

    @Test
    void debugSetActiveExpireToggles() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("DEBUG", "SET-ACTIVE-EXPIRE", "0")).isEqualTo(RespValue.OK);
            assertThat(server.context().activeExpiryEnabled()).isFalse();
            assertThat(c.call("DEBUG", "SET-ACTIVE-EXPIRE", "1")).isEqualTo(RespValue.OK);
            assertThat(server.context().activeExpiryEnabled()).isTrue();
        }
    }
}
