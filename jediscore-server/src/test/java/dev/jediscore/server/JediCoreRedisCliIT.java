package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.jediscore.engine.ServerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Wire-compatibility test using the official {@code redis-cli}.
 *
 * <p>A real Redis container is started purely to borrow its {@code redis-cli}; we
 * keep it idle with {@code sleep} and exec the CLI inside it, pointing it back at
 * the in-process JediCore server via Testcontainers' host-port tunnel. This
 * proves the unmodified official client speaks to JediCore successfully.
 *
 * <p>The test is skipped (not failed) when no Docker daemon is available, so the
 * ordinary build stays green on machines without Docker; CI runs it for real.
 */
class JediCoreRedisCliIT {

    private static JediCore server;
    private static GenericContainer<?> redis;

    @BeforeAll
    static void setUp() throws InterruptedException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");

        server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
        // Make the in-process server reachable from inside the container.
        Testcontainers.exposeHostPorts(server.port());

        redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                .withCommand("sleep", "3600");
        redis.start();
    }

    @AfterAll
    static void tearDown() {
        if (redis != null) {
            redis.stop();
        }
        if (server != null) {
            server.close();
        }
    }

    private static String redisCli(String... command) throws Exception {
        String[] argv = new String[command.length + 5];
        argv[0] = "redis-cli";
        argv[1] = "-h";
        argv[2] = "host.testcontainers.internal";
        argv[3] = "-p";
        argv[4] = String.valueOf(server.port());
        System.arraycopy(command, 0, argv, 5, command.length);
        Container.ExecResult result = redis.execInContainer(argv);
        assertThat(result.getExitCode()).as("redis-cli exit code; stderr=%s", result.getStderr()).isZero();
        return result.getStdout().strip();
    }

    @Test
    void pingViaRedisCli() throws Exception {
        assertThat(redisCli("PING")).isEqualTo("PONG");
    }

    @Test
    void echoViaRedisCli() throws Exception {
        assertThat(redisCli("ECHO", "hello-from-redis-cli")).isEqualTo("hello-from-redis-cli");
    }

    @Test
    void helloViaRedisCli() throws Exception {
        // redis-cli prints the HELLO map; our server identifies itself as "jediscore".
        assertThat(redisCli("HELLO", "3")).contains("jediscore");
    }

    @Test
    void helloRESP2ViaRedisCli() throws Exception {
        assertThat(redisCli("HELLO")).contains("jediscore");
    }
}
