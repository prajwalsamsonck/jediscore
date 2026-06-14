package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end transaction coverage: MULTI/EXEC/DISCARD, WATCH/UNWATCH CAS
 * semantics (including under concurrency), abort-on-queue-error, and the
 * in-place-mutation and expiration invalidation paths.
 */
class JediCoreTransactionIntegrationTest {

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
    void multiExecRunsQueuedCommandsInOrder() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("MULTI")).isEqualTo(RespValue.OK);
            assertThat(c.call("SET", "k", "v")).isEqualTo(RespValue.simple("QUEUED"));
            assertThat(c.call("INCR", "n")).isEqualTo(RespValue.simple("QUEUED"));
            assertThat(c.call("GET", "k")).isEqualTo(RespValue.simple("QUEUED"));

            List<RespValue> results = arr(c.call("EXEC"));
            assertThat(results).hasSize(3);
            assertThat(results.get(0)).isEqualTo(RespValue.OK);
            assertThat(results.get(1)).isEqualTo(RespValue.integer(1));
            assertThat(str(results.get(2))).isEqualTo("v");
            // After EXEC the connection is out of MULTI.
            assertThat(c.call("PING")).isEqualTo(RespValue.PONG);
        }
    }

    @Test
    void discardDropsTheQueue() throws Exception {
        try (RespTestClient c = client()) {
            c.call("MULTI");
            c.call("SET", "k", "should-not-apply");
            assertThat(c.call("DISCARD")).isEqualTo(RespValue.OK);
            assertThat(c.call("EXEC")).isInstanceOf(RespValue.SimpleError.class); // EXEC without MULTI
            assertThat(c.call("GET", "k")).isInstanceOf(RespValue.Null.class);
        }
    }

    @Test
    void watchedKeyModifiedByAnotherClientAbortsExec() throws Exception {
        try (RespTestClient c = client(); RespTestClient other = client()) {
            c.call("SET", "k", "1");
            c.call("WATCH", "k");
            // Another connection modifies the watched key.
            other.call("SET", "k", "2");

            c.call("MULTI");
            c.call("SET", "k", "3");
            // CAS failed → nil array, and the SET was not applied.
            assertThat(c.call("EXEC")).isInstanceOf(RespValue.Null.class);
            assertThat(str(c.call("GET", "k"))).isEqualTo("2");
        }
    }

    @Test
    void watchSucceedsWhenWatchedKeyUntouched() throws Exception {
        try (RespTestClient c = client(); RespTestClient other = client()) {
            c.call("SET", "k", "1");
            c.call("WATCH", "k");
            // Another connection modifies a DIFFERENT key; the watch must hold.
            other.call("SET", "other", "x");

            c.call("MULTI");
            c.call("INCR", "k");
            List<RespValue> results = arr(c.call("EXEC"));
            assertThat(results.get(0)).isEqualTo(RespValue.integer(2));
        }
    }

    @Test
    void inPlaceMutationOfWatchedAggregateAbortsExec() throws Exception {
        try (RespTestClient c = client(); RespTestClient other = client()) {
            c.call("RPUSH", "mylist", "a"); // pre-exists, so the next push mutates in place
            c.call("WATCH", "mylist");
            other.call("RPUSH", "mylist", "b"); // in-place append, no re-store

            c.call("MULTI");
            c.call("SET", "sentinel", "1");
            assertThat(c.call("EXEC")).isInstanceOf(RespValue.Null.class);
            assertThat(c.call("GET", "sentinel")).isInstanceOf(RespValue.Null.class);
        }
    }

    @Test
    void expirationOfWatchedKeyAbortsExec() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "k", "v");
            c.call("WATCH", "k");
            c.call("PEXPIRE", "k", "20");
            Thread.sleep(60); // let it lapse; lazy expiry fires on the next access
            c.call("EXISTS", "k"); // triggers lazy expiry → touches the watch

            c.call("MULTI");
            c.call("SET", "other", "1");
            assertThat(c.call("EXEC")).isInstanceOf(RespValue.Null.class);
        }
    }

    @Test
    void unknownCommandInTransactionAbortsWithExecabort() throws Exception {
        try (RespTestClient c = client()) {
            c.call("MULTI");
            assertThat(c.call("NOTACOMMAND")).isInstanceOf(RespValue.SimpleError.class);
            c.call("SET", "k", "v"); // queued fine, but the tx is already poisoned
            RespValue exec = c.call("EXEC");
            assertThat(exec).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) exec).message()).startsWith("EXECABORT");
            assertThat(c.call("GET", "k")).isInstanceOf(RespValue.Null.class);
        }
    }

    @Test
    void runtimeErrorInQueuedCommandDoesNotAbort() throws Exception {
        try (RespTestClient c = client()) {
            c.call("SET", "str", "hello");
            c.call("MULTI");
            c.call("INCR", "counter"); // ok
            c.call("LPUSH", "str", "x"); // WRONGTYPE at runtime, but queues fine
            List<RespValue> results = arr(c.call("EXEC"));
            assertThat(results).hasSize(2);
            assertThat(results.get(0)).isEqualTo(RespValue.integer(1));
            assertThat(results.get(1)).isInstanceOf(RespValue.SimpleError.class);
        }
    }

    @Test
    void watchInsideMultiIsRejected() throws Exception {
        try (RespTestClient c = client()) {
            c.call("MULTI");
            RespValue r = c.call("WATCH", "k");
            // WATCH is a control command: it runs immediately and errors.
            assertThat(r).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) r).message()).contains("WATCH inside MULTI");
            c.call("DISCARD");
        }
    }

    @Test
    void execWithoutMultiErrors() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("EXEC")).isInstanceOf(RespValue.SimpleError.class);
            assertThat(c.call("DISCARD")).isInstanceOf(RespValue.SimpleError.class);
        }
    }

    /**
     * The headline test: many connections race to increment one counter using the
     * WATCH/retry CAS loop. If CAS is correct, no update is lost and the final
     * value equals the total number of increments.
     */
    @Test
    void concurrentCasIncrementsLoseNoUpdates() throws Exception {
        int threads = 8;
        int incrementsPerThread = 50;
        int total = threads * incrementsPerThread;

        try (RespTestClient setup = client()) {
            setup.call("SET", "counter", "0");
        }

        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger retries = new AtomicInteger();
        Thread[] workers = new Thread[threads];
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try (RespTestClient c = client()) {
                    barrier.await();
                    for (int i = 0; i < incrementsPerThread; i++) {
                        while (true) {
                            c.call("WATCH", "counter");
                            long current = Long.parseLong(str(c.call("GET", "counter")));
                            c.call("MULTI");
                            c.call("SET", "counter", Long.toString(current + 1));
                            RespValue exec = c.call("EXEC");
                            if (exec instanceof RespValue.Null) {
                                retries.incrementAndGet();
                                continue; // CAS lost the race; retry
                            }
                            break; // committed
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
            workers[t].start();
        }
        for (Thread w : workers) {
            w.join();
        }

        assertThat(failures.get()).isZero();
        try (RespTestClient c = client()) {
            assertThat(Long.parseLong(str(c.call("GET", "counter")))).isEqualTo(total);
        }
        System.out.println("CAS concurrency: " + total + " increments committed, "
                + retries.get() + " retries due to contention");
    }
}
