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
 * End-to-end blocking-command coverage: immediate service, async wakeup, FIFO
 * ordering, precise timeouts, chained wakeups (BLMOVE → BLPOP), wrong-type
 * unblocking, WAIT, and the non-blocking behaviour inside MULTI/EXEC.
 */
class JediCoreBlockingIntegrationTest {

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

    /**
     * Sends a blocking command and confirms it has been processed (and the client
     * parked) by round-tripping a PING on the same connection — commands from one
     * connection run in order on the command thread, so a PONG proves the prior
     * blocking command was already handled.
     */
    private static void blockAndConfirm(RespTestClient c, String... cmd) throws IOException {
        c.send(cmd);
        assertThat(c.call("PING")).isEqualTo(RespValue.PONG);
    }

    @Test
    void blpopReturnsImmediatelyWhenDataPresent() throws Exception {
        try (RespTestClient c = client()) {
            c.call("RPUSH", "q", "a", "b");
            List<RespValue> r = arr(c.call("BLPOP", "q", "0"));
            assertThat(str(r.get(0))).isEqualTo("q");
            assertThat(str(r.get(1))).isEqualTo("a"); // head
        }
    }

    @Test
    void blpopWakesWhenAnotherClientPushes() throws Exception {
        try (RespTestClient consumer = client(); RespTestClient producer = client()) {
            blockAndConfirm(consumer, "BLPOP", "q", "0");
            assertThat(producer.call("RPUSH", "q", "hello")).isEqualTo(RespValue.integer(1));

            List<RespValue> r = arr(consumer.receive());
            assertThat(str(r.get(0))).isEqualTo("q");
            assertThat(str(r.get(1))).isEqualTo("hello");
        }
    }

    @Test
    void blockedClientsAreServedInFifoOrder() throws Exception {
        try (RespTestClient c1 = client(); RespTestClient c2 = client(); RespTestClient pusher = client()) {
            blockAndConfirm(c1, "BLPOP", "k", "0"); // registered first
            blockAndConfirm(c2, "BLPOP", "k", "0"); // registered second

            pusher.call("RPUSH", "k", "first");
            assertThat(str(arr(c1.receive()).get(1))).isEqualTo("first");

            pusher.call("RPUSH", "k", "second");
            assertThat(str(arr(c2.receive()).get(1))).isEqualTo("second");
        }
    }

    @Test
    void brpopPopsFromTail() throws Exception {
        try (RespTestClient c = client()) {
            c.call("RPUSH", "q", "a", "b", "c");
            assertThat(str(arr(c.call("BRPOP", "q", "0")).get(1))).isEqualTo("c");
        }
    }

    @Test
    void blpopTimesOutWithNilAndHonoursTheDeadline() throws Exception {
        try (RespTestClient c = client()) {
            long start = System.nanoTime();
            RespValue r = c.call("BLPOP", "absent", "0.3");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(r).isInstanceOf(RespValue.Null.class); // nil array
            assertThat(elapsedMs).isGreaterThanOrEqualTo(280).isLessThan(2000);
        }
    }

    @Test
    void blmoveWakesAndChainsToAnotherBlockedClient() throws Exception {
        try (RespTestClient consumer = client(); RespTestClient mover = client(); RespTestClient helper = client()) {
            // A consumer blocks on the destination list.
            blockAndConfirm(consumer, "BLPOP", "dst", "0");
            helper.call("RPUSH", "src", "x");
            // BLMOVE pops src immediately and pushes to dst, which must wake the consumer.
            assertThat(str(mover.call("BLMOVE", "src", "dst", "LEFT", "RIGHT", "0"))).isEqualTo("x");

            List<RespValue> r = arr(consumer.receive());
            assertThat(str(r.get(0))).isEqualTo("dst");
            assertThat(str(r.get(1))).isEqualTo("x");
        }
    }

    @Test
    void bzpopminReturnsLowestScoredMember() throws Exception {
        try (RespTestClient c = client()) {
            c.call("ZADD", "z", "1", "a", "2", "b");
            List<RespValue> r = arr(c.call("BZPOPMIN", "z", "0"));
            assertThat(str(r.get(0))).isEqualTo("z");
            assertThat(str(r.get(1))).isEqualTo("a");
            assertThat(str(r.get(2))).isEqualTo("1");
        }
    }

    @Test
    void bzpopminWakesOnZadd() throws Exception {
        try (RespTestClient consumer = client(); RespTestClient producer = client()) {
            blockAndConfirm(consumer, "BZPOPMAX", "z", "0");
            producer.call("ZADD", "z", "5", "top");
            List<RespValue> r = arr(consumer.receive());
            assertThat(str(r.get(1))).isEqualTo("top");
            assertThat(str(r.get(2))).isEqualTo("5");
        }
    }

    @Test
    void blmpopPopsMultipleFromFirstNonEmptyKey() throws Exception {
        try (RespTestClient c = client()) {
            c.call("RPUSH", "l", "a", "b", "c");
            List<RespValue> r = arr(c.call("BLMPOP", "0", "2", "missing", "l", "LEFT", "COUNT", "2"));
            assertThat(str(r.get(0))).isEqualTo("l");
            List<RespValue> popped = arr(r.get(1));
            assertThat(popped).map(JediCoreBlockingIntegrationTest::str).containsExactly("a", "b");
        }
    }

    @Test
    void wrongTypeUnblocksWithError() throws Exception {
        try (RespTestClient consumer = client(); RespTestClient other = client()) {
            blockAndConfirm(consumer, "BLPOP", "wt", "0");
            other.call("SET", "wt", "i-am-a-string"); // makes the key the wrong type
            RespValue r = consumer.receive();
            assertThat(r).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) r).message()).startsWith("WRONGTYPE");
        }
    }

    @Test
    void waitReturnsZeroImmediatelyWhenNoReplicasRequired() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("WAIT", "0", "100")).isEqualTo(RespValue.integer(0));
        }
    }

    @Test
    void waitBlocksUntilTimeoutWhenReplicasUnreachable() throws Exception {
        try (RespTestClient c = client()) {
            long start = System.nanoTime();
            RespValue r = c.call("WAIT", "1", "200"); // 200ms, no replicas will ack
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(r).isEqualTo(RespValue.integer(0));
            assertThat(elapsedMs).isGreaterThanOrEqualTo(180).isLessThan(2000);
        }
    }

    @Test
    void blockingInsideTransactionDoesNotBlock() throws Exception {
        try (RespTestClient c = client()) {
            c.call("MULTI");
            assertThat(c.call("BLPOP", "absent", "0")).isEqualTo(RespValue.simple("QUEUED"));
            List<RespValue> results = arr(c.call("EXEC"));
            // EXEC returned promptly; the BLPOP element is a nil array (timed out at once).
            assertThat(results).hasSize(1);
            assertThat(results.get(0)).isInstanceOf(RespValue.Null.class);
        }
    }
}
