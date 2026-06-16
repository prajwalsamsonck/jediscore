package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-compatibility of replication with a real {@code redis-server}, in both
 * directions, via Testcontainers.
 *
 * <p>Runs in CI where Docker is reachable from the Testcontainers client; it skips
 * locally on machines where that handshake fails (the same environment caveat as
 * the differential test). The equivalent checks were also performed manually
 * against Redis 7.4 — see ARCHITECTURE.md.
 */
class JediCoreReplicationCrossCompatIT {

    private static final DockerImageName REDIS = DockerImageName.parse("redis:7.4");

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String str(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static void awaitTrue(Check check) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        AssertionError last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (check.passed()) {
                    return;
                }
            } catch (AssertionError e) {
                last = e;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met within timeout", last);
    }

    @FunctionalInterface
    private interface Check {
        boolean passed() throws Exception;
    }

    @Test
    void realRedisReplicatesFromOurMaster() throws Exception {
        assumeTrue(dockerAvailable(), "Docker not reachable from Testcontainers");
        JediCore master = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
        try (RespTestClient m = new RespTestClient(master.port())) {
            m.call("SET", "k1", "v1");
            m.call("RPUSH", "list", "a", "b", "c");

            Testcontainers.exposeHostPorts(master.port());
            try (GenericContainer<?> replica = new GenericContainer<>(REDIS)
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--replicaof",
                            "host.testcontainers.internal", Integer.toString(master.port()))
                    .waitingFor(Wait.forListeningPort())) {
                replica.start();
                // The replica must full-resync our RDB and then take the live stream.
                awaitTrue(() -> "v1".equals(exec(replica, "GET", "k1")));
                m.call("SET", "k2", "streamed");
                awaitTrue(() -> "streamed".equals(exec(replica, "GET", "k2")));
                assertThat(exec(replica, "LRANGE", "list", "0", "-1")).contains("a", "b", "c");
            }
        } finally {
            master.close();
        }
    }

    @Test
    void ourServerReplicatesFromRealRedisMaster() throws Exception {
        assumeTrue(dockerAvailable(), "Docker not reachable from Testcontainers");
        try (GenericContainer<?> realMaster = new GenericContainer<>(REDIS)
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort())) {
            realMaster.start();
            exec(realMaster, "SET", "rk", "rv");
            exec(realMaster, "SADD", "rs", "x", "y", "z");

            JediCore ours = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
            try (RespTestClient r = new RespTestClient(ours.port())) {
                r.call("REPLICAOF", realMaster.getHost(), Integer.toString(realMaster.getMappedPort(6379)));
                awaitTrue(() -> {
                    RespValue v = r.call("GET", "rk");
                    return v instanceof RespValue.BulkString && str(v).equals("rv");
                });
                // Live write on the real master streams to us.
                exec(realMaster, "SET", "live", "1");
                awaitTrue(() -> {
                    RespValue v = r.call("GET", "live");
                    return v instanceof RespValue.BulkString && str(v).equals("1");
                });
                assertThat(((RespValue.Integer) r.call("SCARD", "rs")).value()).isEqualTo(3);
            } finally {
                ours.close();
            }
        }
    }

    private static String exec(GenericContainer<?> c, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "redis-cli";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return c.execInContainer(cmd).getStdout().trim();
    }
}
