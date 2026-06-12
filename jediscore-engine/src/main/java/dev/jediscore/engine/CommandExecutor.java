package dev.jediscore.engine;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The single-writer command-execution loop.
 *
 * <p><strong>Threading model (and why it is this way).</strong> Every command
 * runs on this one thread. Because exactly one thread mutates the keyspace, the
 * data structures need no internal locking and each command is atomic with
 * respect to every other — which is precisely Redis's guarantee, and the reason
 * Redis can be both lock-free and correct. Netty's I/O threads only parse
 * requests and write replies; they hand decoded requests to this executor and
 * never touch shared state themselves.
 *
 * <p><strong>Ordering.</strong> The backing executor is a single thread with a
 * FIFO queue, so requests submitted from one connection (in read order) run in
 * that order, which is what makes pipelining correct: replies come back in
 * request order.
 *
 * <p><strong>Contrast with alternatives.</strong> A lock-per-structure or
 * concurrent-map design would admit more cores but reintroduce contention,
 * subtle atomicity bugs across multi-key commands, and unpredictable tail
 * latency. A sharded design (one of these loops per key-range) is the planned
 * path to multiple cores; it is deferred until there is a correct cross-shard
 * story, because partial sharding silently breaks multi-key atomicity. v1 runs
 * a single shard — one loop — which keeps semantics identical to Redis.
 *
 * <p>This is <em>not</em> a virtual-thread pool: it is one pinned platform
 * thread that must never be multiplied. Virtual threads are used elsewhere (for
 * blocking commands and background work) so they never stall this loop.
 */
public final class CommandExecutor implements AutoCloseable {

    private final ExecutorService executor;

    /**
     * Creates and starts the command loop.
     *
     * @param name a thread name for diagnostics (e.g. {@code "jedicore-cmd"})
     */
    public CommandExecutor(String name) {
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, name);
            t.setDaemon(false);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Submits work to run on the command thread. Tasks run in submission order.
     *
     * @param task the work to run
     */
    public void submit(Runnable task) {
        executor.execute(task);
    }

    /**
     * Shuts the loop down, waiting briefly for in-flight work to drain.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
