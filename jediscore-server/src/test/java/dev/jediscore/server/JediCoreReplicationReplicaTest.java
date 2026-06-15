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
 * Replica-side replication: REPLICAOF connects out to a master, loads the RDB,
 * applies the live stream, serves reads read-only, and reports replica state.
 * Exercised with two in-process JediCore instances.
 */
class JediCoreReplicationReplicaTest {

    private JediCore master;
    private JediCore replica;

    @BeforeEach
    void start() throws InterruptedException {
        master = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
        replica = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
    }

    @AfterEach
    void stop() {
        if (replica != null) {
            replica.close();
        }
        if (master != null) {
            master.close();
        }
    }

    private RespTestClient masterClient() throws IOException {
        return new RespTestClient(master.port());
    }

    private RespTestClient replicaClient() throws IOException {
        return new RespTestClient(replica.port());
    }

    private static String str(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    /** Polls a key on the replica until it equals the expected value, or fails after a timeout. */
    private static void awaitValue(RespTestClient c, String key, String expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        RespValue last = null;
        while (System.nanoTime() < deadline) {
            last = c.call("GET", key);
            if (last instanceof RespValue.BulkString && str(last).equals(expected)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("replica did not converge on " + key + "=" + expected + " (last=" + last + ")");
    }

    private void replicaOfMaster(RespTestClient c) throws IOException {
        assertThat(c.call("REPLICAOF", "127.0.0.1", Integer.toString(master.port())))
                .isEqualTo(RespValue.OK);
    }

    @Test
    void replicaSyncsExistingDataThenLiveStream() throws Exception {
        try (RespTestClient m = masterClient(); RespTestClient r = replicaClient()) {
            // Data present before the replica attaches (must arrive via the RDB).
            m.call("SET", "pre", "snapshot");
            m.call("RPUSH", "list", "a", "b", "c");

            replicaOfMaster(r);
            awaitValue(r, "pre", "snapshot"); // full resync delivered it

            // Writes after attach must arrive via the live stream.
            m.call("SET", "post", "streamed");
            m.call("INCR", "counter");
            m.call("INCR", "counter");
            awaitValue(r, "post", "streamed");
            awaitValue(r, "counter", "2");

            // The list from the RDB is intact and read-queryable on the replica.
            List<RespValue> list = ((RespValue.Array) r.call("LRANGE", "list", "0", "-1")).items();
            assertThat(list).map(JediCoreReplicationReplicaTest::str).containsExactly("a", "b", "c");
        }
    }

    @Test
    void replicaRejectsClientWrites() throws Exception {
        try (RespTestClient r = replicaClient()) {
            replicaOfMaster(r);
            // isReplica flips synchronously on REPLICAOF, so writes are refused at once.
            RespValue err = r.call("SET", "x", "1");
            assertThat(err).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) err).message()).startsWith("READONLY");
            // Reads are still served.
            assertThat(r.call("GET", "x")).isInstanceOf(RespValue.Null.class);
        }
    }

    @Test
    void replicaofNoOnePromotesToMaster() throws Exception {
        try (RespTestClient m = masterClient(); RespTestClient r = replicaClient()) {
            m.call("SET", "k", "v");
            replicaOfMaster(r);
            awaitValue(r, "k", "v");

            assertThat(r.call("REPLICAOF", "NO", "ONE")).isEqualTo(RespValue.OK);
            // Now a master again: writes are accepted, data retained.
            assertThat(r.call("SET", "local", "1")).isEqualTo(RespValue.OK);
            assertThat(str(r.call("GET", "k"))).isEqualTo("v"); // kept the synced data
        }
    }

    @Test
    void roleAndInfoReportSlaveState() throws Exception {
        try (RespTestClient m = masterClient(); RespTestClient r = replicaClient()) {
            m.call("SET", "ping", "1");
            replicaOfMaster(r);
            awaitValue(r, "ping", "1"); // ensure the link is connected

            List<RespValue> role = ((RespValue.Array) r.call("ROLE")).items();
            assertThat(str(role.get(0))).isEqualTo("slave");
            assertThat(str(role.get(1))).isEqualTo("127.0.0.1");
            assertThat(((RespValue.Integer) role.get(2)).value()).isEqualTo(master.port());

            String info = str(r.call("INFO", "replication"));
            assertThat(info).contains("role:slave");
            assertThat(info).contains("master_host:127.0.0.1");
            assertThat(info).contains("master_link_status:up");
        }
    }
}
