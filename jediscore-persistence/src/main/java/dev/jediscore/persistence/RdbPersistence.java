package dev.jediscore.persistence;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.engine.Database;
import dev.jediscore.engine.Persistence;
import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.SavePoint;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RDB persistence: synchronous {@code SAVE}, background {@code BGSAVE}, startup
 * loading, {@code DEBUG RELOAD}, and save-point evaluation.
 *
 * <p><strong>Fork-free snapshot.</strong> The JVM cannot {@code fork()} for
 * copy-on-write, so {@code BGSAVE} takes a <em>consistent deep-copy snapshot</em>
 * of the keyspace on the command thread (the brief stop-the-world), then
 * serializes that immutable snapshot on a background thread while the command
 * thread resumes. {@code SAVE} serializes directly (it already holds the command
 * thread, so a reference snapshot suffices). The snapshot pause is O(dataset
 * size) — larger than Redis's O(page-table) fork — which is the documented cost
 * of lacking fork.
 */
public final class RdbPersistence implements Persistence {

    private static final Logger log = LoggerFactory.getLogger(RdbPersistence.class);

    private final ServerContext context;
    private final PersistenceConfig config;
    private final Path file;
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
        this.file = Path.of(config.dir(), config.dbFilename());
    }

    @Override
    public boolean save() {
        try {
            writeSnapshot(buildSnapshot(false));
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
        // Snapshot now (the pause), serialize off-thread.
        RdbSnapshot snapshot = buildSnapshot(true);
        context.resetDirty();
        backgroundExecutor.execute(() -> {
            try {
                writeSnapshot(snapshot);
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
            writeSnapshot(buildSnapshot(false));
            for (int i = 0; i < context.databaseCount(); i++) {
                context.database(i).clear();
            }
            loadFile();
        } catch (IOException e) {
            throw new RdbException("DEBUG RELOAD failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void loadOnStartup() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            loadFile();
            log.info("Loaded RDB from {}", file);
        } catch (IOException e) {
            throw new RdbException("failed to load RDB " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void onCron() {
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

    @Override
    public void shutdown() {
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

    private RdbSnapshot buildSnapshot(boolean deepCopy) {
        List<RdbSnapshot.DatabaseSnapshot> dbs = new ArrayList<>();
        for (int i = 0; i < context.databaseCount(); i++) {
            Database db = context.database(i);
            List<Bytes> keys = db.liveKeys();
            if (keys.isEmpty()) {
                continue;
            }
            List<RdbSnapshot.Entry> entries = new ArrayList<>(keys.size());
            for (Bytes key : keys) {
                RedisValue value = db.peek(key);
                if (value == null) {
                    continue;
                }
                Long expireAt = db.getExpireAt(key);
                entries.add(new RdbSnapshot.Entry(
                        key.copy(),
                        deepCopy ? value.deepCopy() : value,
                        expireAt == null ? -1 : expireAt));
            }
            dbs.add(new RdbSnapshot.DatabaseSnapshot(i, entries));
        }
        return new RdbSnapshot(dbs);
    }

    private void writeSnapshot(RdbSnapshot snapshot) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Path.of(file.toString() + ".tmp-" + System.nanoTime());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
            RdbWriter.write(snapshot, out);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void loadFile() throws IOException {
        long now = System.currentTimeMillis();
        EncodingLimits limits = limits(context.config());
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            new RdbReader(in, limits).readInto((db, key, value, expireAtMs) -> {
                if (expireAtMs >= 0 && expireAtMs <= now) {
                    return; // already expired at load time — drop it, as Redis does on a master
                }
                Database database = context.database(db);
                Bytes wrapped = new Bytes(key);
                database.putKeepTtl(wrapped, value);
                if (expireAtMs >= 0) {
                    database.setExpireAt(wrapped, expireAtMs);
                }
            });
        }
    }

    private static EncodingLimits limits(ServerConfig cfg) {
        return new EncodingLimits(
                cfg.hashMaxListpackEntries(), cfg.hashMaxListpackValue(),
                cfg.listMaxListpackSize(), cfg.listMaxListpackValue(),
                cfg.setMaxIntsetEntries(), cfg.setMaxListpackEntries(), cfg.setMaxListpackValue(),
                cfg.zsetMaxListpackEntries(), cfg.zsetMaxListpackValue());
    }
}
