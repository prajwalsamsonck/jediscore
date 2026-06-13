package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * RDB cross-compatibility with real {@code redis-server} (Testcontainers),
 * exercised both ways. Skipped when Docker is unavailable.
 */
class JediCoreRdbCrossCompatIT {

    private static final DockerImageName REDIS = DockerImageName.parse("redis:7.4-alpine");

    private static String bulk(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static List<String> arr(RespValue v) {
        return ((RespValue.Array) v).items().stream().map(JediCoreRdbCrossCompatIT::bulk).toList();
    }

    /** Direction 1: JediCore writes the RDB, real redis-server loads it. */
    @Test
    void realRedisLoadsJediCoreRdb(@TempDir Path dir) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        JediCore server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0),
                PersistenceConfig.defaults().withDir(dir.toString()));
        try (RespTestClient c = new RespTestClient(server.port())) {
            c.call("SET", "str", "hello");
            c.call("RPUSH", "list", "a", "b", "c");
            c.call("SADD", "intset", "1", "2", "3");
            c.call("HSET", "hash", "f1", "v1", "f2", "v2");
            c.call("ZADD", "zset", "1", "alpha", "2.5", "beta");
            c.call("SAVE");
        } finally {
            server.close();
        }

        // Booting redis with our dump.rdb only succeeds if the file is valid (CRC + format).
        try (GenericContainer<?> redis = new GenericContainer<>(REDIS)
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("dump.rdb")), "/data/dump.rdb")
                .withCommand("redis-server", "--dir", "/data", "--dbfilename", "dump.rdb")
                .withExposedPorts(6379)) {
            redis.start();
            assertThat(cli(redis, "GET", "str")).isEqualTo("hello");
            assertThat(cli(redis, "LRANGE", "list", "0", "-1")).isEqualTo("a\nb\nc");
            assertThat(cli(redis, "SCARD", "intset")).isEqualTo("3");
            assertThat(cli(redis, "HGET", "hash", "f1")).isEqualTo("v1");
            assertThat(cli(redis, "ZSCORE", "zset", "beta")).isEqualTo("2.5");
        }
    }

    /** Direction 2: real redis-server writes the RDB (compact encodings + LZF), JediCore loads it. */
    @Test
    void jediCoreLoadsRealRedisRdb(@TempDir Path dir) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (GenericContainer<?> redis = new GenericContainer<>(REDIS)
                .withCommand("redis-server", "--dir", "/data", "--dbfilename", "dump.rdb")
                .withExposedPorts(6379)) {
            redis.start();
            cli(redis, "SET", "str", "hello");
            cli(redis, "SET", "big", "a".repeat(500)); // long + compressible -> LZF on the wire
            cli(redis, "RPUSH", "list", "a", "b", "c"); // quicklist v2 / listpack node
            cli(redis, "SADD", "intset", "1", "2", "3"); // intset encoding
            cli(redis, "SADD", "strset", "xx", "yy");    // listpack set
            cli(redis, "HSET", "hash", "f1", "v1", "f2", "v2"); // listpack hash
            cli(redis, "ZADD", "zset", "1", "alpha", "2.5", "beta"); // listpack zset
            cli(redis, "SAVE");
            redis.copyFileFromContainer("/data/dump.rdb", dir.resolve("dump.rdb").toString());
        }

        JediCore server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0),
                PersistenceConfig.defaults().withDir(dir.toString()));
        try (RespTestClient c = new RespTestClient(server.port())) {
            assertThat(bulk(c.call("GET", "str"))).isEqualTo("hello");
            assertThat(bulk(c.call("GET", "big"))).isEqualTo("a".repeat(500));
            assertThat(arr(c.call("LRANGE", "list", "0", "-1"))).containsExactly("a", "b", "c");
            assertThat(arr(c.call("SMEMBERS", "intset"))).containsExactlyInAnyOrder("1", "2", "3");
            assertThat(arr(c.call("SMEMBERS", "strset"))).containsExactlyInAnyOrder("xx", "yy");
            assertThat(bulk(c.call("HGET", "hash", "f2"))).isEqualTo("v2");
            assertThat(bulk(c.call("ZSCORE", "zset", "beta"))).isEqualTo("2.5");
        } finally {
            server.close();
        }
    }

    private static String cli(GenericContainer<?> redis, String... args) throws Exception {
        String[] argv = new String[args.length + 1];
        argv[0] = "redis-cli";
        System.arraycopy(args, 0, argv, 1, args.length);
        Container.ExecResult result = redis.execInContainer(argv);
        assertThat(result.getExitCode()).as("redis-cli stderr=%s", result.getStderr()).isZero();
        return result.getStdout().strip();
    }
}
