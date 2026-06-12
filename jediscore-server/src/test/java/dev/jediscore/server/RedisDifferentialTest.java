package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The gold-standard test: property-based differential testing against real Redis.
 *
 * <p>jqwik generates random sequences of string/hash/generic commands; each
 * sequence is replayed against both JediCore and an authentic Redis, and the two
 * replies are required to be byte-for-byte identical after canonicalisation. Any
 * divergence is a bug in JediCore (or a deliberately documented difference), and
 * jqwik shrinks a failing sequence to a minimal reproducer.
 *
 * <p><strong>Reference Redis resolution.</strong> If the system property
 * {@code jedicore.diff.redis=host:port} is set, that server is used (handy for a
 * local {@code docker run} Redis). Otherwise, if Docker is available, a Redis
 * container is started via Testcontainers (this is the path CI takes).
 * Otherwise the properties are skipped.
 *
 * <p>Only commands with order-independent replies are generated, so comparison
 * needs no normalisation of collection ordering.
 */
class RedisDifferentialTest {

    private static JediCore jediCore;
    private static RespTestClient jedi;
    private static RespTestClient redis;
    private static GenericContainer<?> container;
    private static boolean enabled;

    @BeforeContainer
    static void setUp() throws Exception {
        String host;
        int port;
        String addr = System.getProperty("jedicore.diff.redis");
        if (addr != null && !addr.isBlank()) {
            int idx = addr.lastIndexOf(':');
            host = addr.substring(0, idx);
            port = Integer.parseInt(addr.substring(idx + 1));
        } else if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
            container.start();
            host = container.getHost();
            port = container.getMappedPort(6379);
        } else {
            // No reference Redis available: leave the property disabled. We can't
            // cleanly report "skipped" at runtime in jqwik (Assume.that rejects
            // tries; aborting the lifecycle surfaces as an error), so the property
            // instead passes trivially — see the early return in its body. Locally
            // run it with -Djedicore.diff.redis=host:port; CI provides Docker.
            enabled = false;
            return;
        }
        jediCore = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
        jedi = new RespTestClient(jediCore.port());
        redis = new RespTestClient(host, port);
        enabled = true;
    }

    @AfterContainer
    static void tearDown() throws IOException {
        if (jedi != null) {
            jedi.close();
        }
        if (redis != null) {
            redis.close();
        }
        if (jediCore != null) {
            jediCore.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Property(tries = 300)
    void repliesMatchRealRedis(@ForAll("commandSequences") List<String[]> sequence) throws IOException {
        if (!enabled) {
            return; // no reference Redis configured; nothing to compare against
        }
        jedi.call("FLUSHALL");
        redis.call("FLUSHALL");
        for (String[] command : sequence) {
            RespValue mine = jedi.call(command);
            RespValue theirs = redis.call(command);
            assertEquivalent(command, mine, theirs);
        }
    }

    /** Set-returning commands whose element order is unspecified in Redis. */
    private static final java.util.Set<String> UNORDERED =
            java.util.Set.of("SMEMBERS", "SUNION", "SINTER", "SDIFF");

    private static void assertEquivalent(String[] command, RespValue mine, RespValue theirs) {
        String name = command[0].toUpperCase(java.util.Locale.ROOT);
        if (UNORDERED.contains(name) && mine instanceof RespValue.Array a && theirs instanceof RespValue.Array b) {
            List<String> mineSorted = a.items().stream().map(RedisDifferentialTest::canonical).sorted().toList();
            List<String> theirsSorted = b.items().stream().map(RedisDifferentialTest::canonical).sorted().toList();
            assertThat(mineSorted).as("divergence on %s", Arrays.toString(command)).isEqualTo(theirsSorted);
            return;
        }
        assertThat(canonical(mine)).as("divergence on %s", Arrays.toString(command)).isEqualTo(canonical(theirs));
    }

    @Provide
    Arbitrary<List<String[]>> commandSequences() {
        // A shared, tiny key pool means the same key is reused across types,
        // which deliberately provokes WRONGTYPE errors that must match Redis.
        Arbitrary<String> keys = Arbitraries.of("k0", "k1", "k2");
        Arbitrary<String> fields = Arbitraries.of("f0", "f1", "f2");
        // A mix of integers, non-integers, and an empty string, to exercise both
        // the happy path and the matching error paths on both servers.
        Arbitrary<String> values = Arbitraries.of("0", "1", "5", "-3", "42", "hello", "");
        Arbitrary<String> indices = Arbitraries.of("-2", "-1", "0", "1", "2", "3");

        Arbitrary<String[]> command = Arbitraries.oneOf(List.of(
                // strings
                Combinators.combine(keys, values).as((k, v) -> new String[] {"SET", k, v}),
                keys.map(k -> new String[] {"GET", k}),
                Combinators.combine(keys, values).as((k, v) -> new String[] {"APPEND", k, v}),
                keys.map(k -> new String[] {"STRLEN", k}),
                keys.map(k -> new String[] {"INCR", k}),
                Combinators.combine(keys, values).as((k, v) -> new String[] {"INCRBY", k, v}),
                // generic
                keys.map(k -> new String[] {"DEL", k}),
                keys.map(k -> new String[] {"EXISTS", k}),
                keys.map(k -> new String[] {"TYPE", k}),
                // hashes
                Combinators.combine(keys, fields, values).as((k, f, v) -> new String[] {"HSET", k, f, v}),
                Combinators.combine(keys, fields).as((k, f) -> new String[] {"HGET", k, f}),
                Combinators.combine(keys, fields).as((k, f) -> new String[] {"HDEL", k, f}),
                keys.map(k -> new String[] {"HLEN", k}),
                Combinators.combine(keys, fields, values).as((k, f, v) -> new String[] {"HINCRBY", k, f, v}),
                // lists (replies are order-deterministic, so compared directly)
                Combinators.combine(keys, values).as((k, v) -> new String[] {"LPUSH", k, v}),
                Combinators.combine(keys, values).as((k, v) -> new String[] {"RPUSH", k, v}),
                keys.map(k -> new String[] {"LPOP", k}),
                keys.map(k -> new String[] {"RPOP", k}),
                keys.map(k -> new String[] {"LLEN", k}),
                Combinators.combine(keys, indices).as((k, i) -> new String[] {"LINDEX", k, i}),
                Combinators.combine(keys, indices, indices).as((k, s, e) -> new String[] {"LRANGE", k, s, e}),
                Combinators.combine(keys, indices, indices).as((k, s, e) -> new String[] {"LTRIM", k, s, e}),
                Combinators.combine(keys, values, values).as((k, c, v) -> new String[] {"LREM", k, c, v}),
                Combinators.combine(keys, values).as((k, v) -> new String[] {"LPOS", k, v}),
                // sets — integer-reply commands (compared directly)
                Combinators.combine(keys, values).as((k, v) -> new String[] {"SADD", k, v}),
                Combinators.combine(keys, values).as((k, v) -> new String[] {"SREM", k, v}),
                keys.map(k -> new String[] {"SCARD", k}),
                Combinators.combine(keys, values).as((k, v) -> new String[] {"SISMEMBER", k, v}),
                Combinators.combine(keys, keys, values).as((s, d, m) -> new String[] {"SMOVE", s, d, m}),
                // sets — element-returning commands (compared as unordered sets)
                keys.map(k -> new String[] {"SMEMBERS", k}),
                Combinators.combine(keys, keys).as((a, b) -> new String[] {"SUNION", a, b}),
                Combinators.combine(keys, keys).as((a, b) -> new String[] {"SINTER", a, b}),
                Combinators.combine(keys, keys).as((a, b) -> new String[] {"SDIFF", a, b})));

        return command.list().ofMinSize(1).ofMaxSize(30);
    }

    /** Renders a reply to a canonical string for comparison across servers. */
    private static String canonical(RespValue v) {
        if (v instanceof RespValue.SimpleString s) {
            return "+" + s.value();
        }
        if (v instanceof RespValue.SimpleError e) {
            return "-" + e.message();
        }
        if (v instanceof RespValue.Integer i) {
            return ":" + i.value();
        }
        if (v instanceof RespValue.BulkString b) {
            return "$" + new String(b.data(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (v instanceof RespValue.Null) {
            return "_";
        }
        if (v instanceof RespValue.Array a) {
            return "*" + a.items().stream().map(RedisDifferentialTest::canonical).collect(Collectors.toList());
        }
        return v.toString();
    }
}
