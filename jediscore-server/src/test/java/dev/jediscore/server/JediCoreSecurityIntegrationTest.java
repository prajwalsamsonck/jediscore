package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Coverage for ACL, AUTH, requirepass, maxclients, and rename-command. */
class JediCoreSecurityIntegrationTest {

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
    void aclWhoamiListCatUsers() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(str(c.call("ACL", "WHOAMI"))).isEqualTo("default");
            assertThat(arr(c.call("ACL", "LIST")).stream().map(JediCoreSecurityIntegrationTest::str))
                    .anyMatch(s -> s.startsWith("user default"));
            assertThat(arr(c.call("ACL", "CAT")).stream().map(JediCoreSecurityIntegrationTest::str))
                    .contains("read", "write", "admin");
            assertThat(arr(c.call("ACL", "USERS")).stream().map(JediCoreSecurityIntegrationTest::str))
                    .contains("default");
        }
    }

    @Test
    void setuserThenAuthSwitchesUser() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("ACL", "SETUSER", "alice", "on", ">secret", "~*", "+@all"))
                    .isEqualTo(RespValue.OK);
            assertThat(c.call("AUTH", "alice", "secret")).isEqualTo(RespValue.OK);
            assertThat(str(c.call("ACL", "WHOAMI"))).isEqualTo("alice");
            assertThat(c.call("AUTH", "alice", "wrong")).isInstanceOf(RespValue.SimpleError.class);
        }
    }

    @Test
    void commandPermissionsAreEnforced() throws Exception {
        try (RespTestClient c = client()) {
            c.call("ACL", "SETUSER", "bob", "on", ">pw", "~*", "nocommands", "+get", "+ping", "+auth");
            try (RespTestClient bob = client()) {
                assertThat(bob.call("AUTH", "bob", "pw")).isEqualTo(RespValue.OK);
                assertThat(bob.call("GET", "k")).isInstanceOf(RespValue.Null.class); // +get allowed
                RespValue denied = bob.call("SET", "k", "v");                          // not allowed
                assertThat(denied).isInstanceOf(RespValue.SimpleError.class);
                assertThat(((RespValue.SimpleError) denied).message()).startsWith("NOPERM");
            }
        }
    }

    @Test
    void categoryRulesAreEnforced() throws Exception {
        try (RespTestClient c = client()) {
            // A read-only user: +@read allows GET, denies writes.
            c.call("ACL", "SETUSER", "ro", "on", ">pw", "~*", "nocommands", "+@read", "+auth");
            try (RespTestClient ro = client()) {
                ro.call("AUTH", "ro", "pw");
                assertThat(ro.call("GET", "k")).isInstanceOf(RespValue.Null.class);
                assertThat(ro.call("SET", "k", "v")).isInstanceOf(RespValue.SimpleError.class);
            }
        }
    }

    @Test
    void deluserAndDefaultProtected() throws Exception {
        try (RespTestClient c = client()) {
            c.call("ACL", "SETUSER", "temp", "on");
            assertThat(c.call("ACL", "DELUSER", "temp")).isEqualTo(RespValue.integer(1));
            assertThat(c.call("ACL", "DELUSER", "default")).isInstanceOf(RespValue.SimpleError.class);
        }
    }

    @Test
    void requirepassViaConfigGatesNewConnections() throws Exception {
        try (RespTestClient admin = client()) {
            admin.call("CONFIG", "SET", "requirepass", "s3cret");
            // A fresh connection must now AUTH before running commands.
            try (RespTestClient c = client()) {
                assertThat(c.call("GET", "k")).isInstanceOf(RespValue.SimpleError.class);
                assertThat(((RespValue.SimpleError) c.call("GET", "k")).message()).startsWith("NOAUTH");
                assertThat(c.call("AUTH", "s3cret")).isEqualTo(RespValue.OK);
                assertThat(c.call("GET", "k")).isInstanceOf(RespValue.Null.class);
            }
        }
    }

    @Test
    void maxclientsConfigAndReject() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("CONFIG", "SET", "maxclients", "1")).isEqualTo(RespValue.OK);
            // c is the only allowed client; a second is rejected at accept time.
            boolean rejected = false;
            try (RespTestClient c2 = client()) {
                RespValue r = c2.call("PING");
                rejected = r instanceof RespValue.SimpleError
                        && ((RespValue.SimpleError) r).message().contains("max number of clients");
            } catch (IOException e) {
                rejected = true; // connection closed by the reject
            }
            assertThat(rejected).isTrue();
        }
    }

    @Test
    void renameAndDisableCommands() throws Exception {
        JediCore renamed = JediCore.start(ServerConfig.defaults("127.0.0.1", 0),
                dev.jediscore.engine.PersistenceConfig.defaults(),
                Map.of("CONFIG", "MYCONFIG", "FLUSHALL", ""));
        try (RespTestClient c = new RespTestClient(renamed.port())) {
            // CONFIG is renamed to MYCONFIG; the old name is unknown.
            assertThat(c.call("MYCONFIG", "GET", "maxmemory")).isInstanceOf(RespValue.Array.class);
            assertThat(c.call("CONFIG", "GET", "maxmemory")).isInstanceOf(RespValue.SimpleError.class);
            // FLUSHALL is disabled.
            assertThat(c.call("FLUSHALL")).isInstanceOf(RespValue.SimpleError.class);
        } finally {
            renamed.close();
        }
    }
}
