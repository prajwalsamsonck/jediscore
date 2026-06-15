package dev.jediscore.persistence;

import dev.jediscore.engine.Persistence;
import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.SavePoint;
import dev.jediscore.engine.ServerContext;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The persistence facade: RDB ({@code SAVE}/{@code BGSAVE}, save points,
 * {@code DEBUG RELOAD}) plus AOF (delegated to {@link AofManager}).
 *
 * <p><strong>Fork-free RDB snapshot.</strong> {@code SAVE} serializes
 * synchronously on the command thread; {@code BGSAVE} deep-copies the keyspace on
 * the command thread (the O(dataset) pause) then serializes off-thread. See
 * ARCHITECTURE.md for the pause tradeoff vs Redis's {@code fork()}.
 *
 * <p><strong>Startup load.</strong> When AOF is enabled it takes precedence over
 * the RDB; otherwise the RDB is loaded if present.
 */
public final class RdbPersistence implements Persistence {

    private static final Logger log = LoggerFactory.getLogger(RdbPersistence.class);

    private final ServerContext context;
    private final PersistenceConfig config;
    private final Path rdbFile;
    private final AofManager aof;
    private final ExecutorService backgroundExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jedicore-bgsave");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean bgsaveInProgress = new AtomicBoolean(false);
    private volatile long lastSaveSeconds = System.currentTimeMillis() / 1000;

    /**
     * Creates the service.
     *
     * @param context the server context
     * @param config  the persistence configuration
     */
    public RdbPersistence(ServerContext context, PersistenceConfig config) {
        this.context = context;
        this.config = config;
        this.rdbFile = Path.of(config.dir(), config.dbFilename());
        this.aof = new AofManager(context, config);
    }

    @Override
    public boolean save() {
        try {
            writeRdb(Snapshots.of(context, false));
            lastSaveSeconds = System.currentTimeMillis() / 1000;
            context.resetDirty();
            return true;
        } catch (IOException e) {
            throw new RdbException("RDB save failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean backgroundSave() {
        if (!bgsaveInProgress.compareAndSet(false, true)) {
            return false;
        }
        RdbSnapshot snapshot = Snapshots.of(context, true);
        context.resetDirty();
        backgroundExecutor.execute(() -> {
            try {
                writeRdb(snapshot);
                lastSaveSeconds = System.currentTimeMillis() / 1000;
                log.info("Background RDB save completed");
            } catch (IOException e) {
                log.error("Background RDB save failed", e);
            } finally {
                bgsaveInProgress.set(false);
            }
        });
        return true;
    }

    @Override
    public long lastSaveSeconds() {
        return lastSaveSeconds;
    }

    @Override
    public boolean backgroundSaveInProgress() {
        return bgsaveInProgress.get();
    }

    @Override
    public void reload() {
        try {
            writeRdb(Snapshots.of(context, false));
            for (int i = 0; i < context.databaseCount(); i++) {
                context.database(i).clear();
            }
            try (InputStream in = new BufferedInputStream(Files.newInputStream(rdbFile))) {
                Snapshots.loadRdbStream(in, context);
            }
        } catch (IOException e) {
            throw new RdbException("DEBUG RELOAD failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void loadOnStartup() {
        try {
            if (config.appendOnly()) {
                aof.startup(); // AOF takes precedence and is opened for appending
            } else if (Files.exists(rdbFile)) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(rdbFile))) {
                    Snapshots.loadRdbStream(in, context);
                }
                log.info("Loaded RDB from {}", rdbFile);
            }
        } catch (IOException e) {
            throw new RdbException("startup load failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void onCron() {
        savePointCheck();
        aof.onCron();
    }

    @Override
    public boolean appendOnlyEnabled() {
        return aof.enabled();
    }

    @Override
    public void feedAppendOnly(int database, byte[][] args) {
        aof.feed(database, args);
    }

    @Override
    public boolean rewriteAppendOnly() {
        return aof.enabled() && aof.rewrite();
    }

    @Override
    public boolean appendRewriteInProgress() {
        return aof.rewriteInProgress();
    }

    @Override
    public byte[] dumpRdb() {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            RdbWriter.write(Snapshots.of(context, false), out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RdbException("replication RDB dump failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void shutdown() {
        aof.close();
        backgroundExecutor.shutdown();
        try {
            if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            backgroundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- internals ----------------------------------------------------------

    private void savePointCheck() {
        if (config.savePoints().isEmpty() || bgsaveInProgress.get()) {
            return;
        }
        long elapsed = System.currentTimeMillis() / 1000 - lastSaveSeconds;
        long dirty = context.dirty();
        for (SavePoint point : config.savePoints()) {
            if (dirty >= point.changes() && elapsed >= point.seconds()) {
                backgroundSave();
                return;
            }
        }
    }

    private void writeRdb(RdbSnapshot snapshot) throws IOException {
        Path dir = rdbFile.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Path.of(rdbFile + ".tmp-" + System.nanoTime());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
            RdbWriter.write(snapshot, out);
        }
        Files.move(temp, rdbFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
