package dev.jediscore.engine;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The background "cron" that drives periodic maintenance, mirroring Redis's
 * {@code serverCron} (which runs at {@code hz}=10, i.e. every 100&nbsp;ms).
 *
 * <p>The cron's own thread does no keyspace work; it only <em>submits</em> a
 * maintenance task to the {@link CommandExecutor}, so the actual work (the
 * active-expiry cycle, and later eviction housekeeping) runs on the single
 * command thread and never races with command execution.
 */
public final class ServerCron implements AutoCloseable {

    private static final long PERIOD_MILLIS = 100;

    private final ServerContext context;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates the cron for a server context.
     *
     * @param context the server context
     */
    public ServerCron(ServerContext context) {
        this.context = context;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "jedicore-cron");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts the periodic cycle. */
    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, PERIOD_MILLIS, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        // Hand the work to the command thread; never touch the keyspace here.
        context.executor().submit(() -> ActiveExpiry.run(context));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
