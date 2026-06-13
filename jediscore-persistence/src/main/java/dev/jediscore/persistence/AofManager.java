package dev.jediscore.persistence;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandDispatcher;
import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
 * Redis 7 multi-part AOF: a base file (RDB) capturing a point in time, an incr
 * file (RESP commands) appended since, and a manifest tying them together.
 *
 * <p><strong>fsync.</strong> {@code always} fsyncs after every command;
 * {@code everysec} flushes each command to the OS and fsyncs roughly once per
 * second (driven by the cron); {@code no} flushes to the OS and lets it decide.
 *
 * <p><strong>Rewrite (fork-free).</strong> {@code BGREWRITEAOF} deep-copies the
 * keyspace and switches appends to a fresh incr file on the command thread, then a
 * background thread writes the new base RDB and commits a new manifest before
 * deleting the old files. (Crash between the incr switch and the manifest commit
 * can lose commands written during the rewrite — a narrow, documented window.)
 *
 * <p><strong>Replay.</strong> On load, the base RDB is read directly and the incr
 * commands are replayed through the {@link CommandDispatcher} against a synthetic
 * connection, with appending suppressed.
 */
final class AofManager {

    private static final Logger log = LoggerFactory.getLogger(AofManager.class);

    private final ServerContext context;
    private final PersistenceConfig config;
    private final Path aofDir;
    private final Path manifestPath;
    private final String fsync;
    private final ExecutorService rewriteExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jedicore-aofrw");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean rewriteInProgress = new AtomicBoolean(false);

    private int currentSeq;
    private FileOutputStream incrFos;
    private BufferedOutputStream incrBos;
    private int aofSelectedDb = -1;
    private long lastEverysecFsyncMs = System.currentTimeMillis();
    private volatile boolean loading;

    AofManager(ServerContext context, PersistenceConfig config) {
        this.context = context;
        this.config = config;
        this.aofDir = Path.of(config.dir(), config.appendDirname());
        this.manifestPath = aofDir.resolve(config.appendFilename() + ".manifest");
        this.fsync = config.appendFsync();
    }

    /** Loads an existing AOF (or creates a fresh one) and opens it for appending. */
    void startup() throws IOException {
        Files.createDirectories(aofDir);
        if (Files.exists(manifestPath)) {
            List<String[]> entries = parseManifest();
            replay(entries);
            openIncrForAppend(currentSeq);
        } else {
            currentSeq = 1;
            writeBase(currentSeq, Snapshots.of(context, false));
            openNewIncr(currentSeq);
            writeManifest(currentSeq);
        }
    }

    boolean enabled() {
        return config.appendOnly();
    }

    boolean rewriteInProgress() {
        return rewriteInProgress.get();
    }

    /** Appends one command (no-op while loading). Called on the command thread. */
    void feed(int database, byte[][] args) {
        if (loading || incrBos == null) {
            return;
        }
        try {
            if (database != aofSelectedDb) {
                writeCommand(new byte[][] {
                        "SELECT".getBytes(StandardCharsets.US_ASCII),
                        Integer.toString(database).getBytes(StandardCharsets.US_ASCII)});
                aofSelectedDb = database;
            }
            writeCommand(args);
            incrBos.flush(); // push to the OS
            if ("always".equals(fsync)) {
                incrFos.getFD().sync();
            }
        } catch (IOException e) {
            throw new RdbException("AOF append failed: " + e.getMessage(), e);
        }
    }

    /** Cron hook: performs the everysec fsync. */
    void onCron() {
        if (!"everysec".equals(fsync) || incrFos == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEverysecFsyncMs >= 1000) {
            try {
                incrBos.flush();
                incrFos.getFD().sync();
            } catch (IOException e) {
                log.warn("AOF everysec fsync failed", e);
            }
            lastEverysecFsyncMs = now;
        }
    }

    /** Starts a background rewrite. Called on the command thread. */
    boolean rewrite() {
        if (!rewriteInProgress.compareAndSet(false, true)) {
            return false;
        }
        try {
            RdbSnapshot snapshot = Snapshots.of(context, true); // deep copy (the pause)
            int oldSeq = currentSeq;
            int newSeq = currentSeq + 1;
            flushAndCloseIncr();
            openNewIncr(newSeq);
            currentSeq = newSeq;
            rewriteExecutor.execute(() -> {
                try {
                    writeBase(newSeq, snapshot);
                    writeManifest(newSeq);
                    deleteOld(oldSeq);
                    log.info("AOF rewrite completed (seq {})", newSeq);
                } catch (IOException e) {
                    log.error("AOF rewrite failed", e);
                } finally {
                    rewriteInProgress.set(false);
                }
            });
            return true;
        } catch (IOException e) {
            rewriteInProgress.set(false);
            throw new RdbException("AOF rewrite failed: " + e.getMessage(), e);
        }
    }

    void close() {
        try {
            flushAndCloseIncr();
        } catch (IOException e) {
            log.warn("error closing AOF", e);
        }
        rewriteExecutor.shutdown();
        try {
            if (!rewriteExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                rewriteExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            rewriteExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- file naming --------------------------------------------------------

    private String baseName(int seq) {
        return config.appendFilename() + "." + seq + ".base.rdb";
    }

    private String incrName(int seq) {
        return config.appendFilename() + "." + seq + ".incr.aof";
    }

    // ---- append plumbing ----------------------------------------------------

    private void writeCommand(byte[][] args) throws IOException {
        incrBos.write('*');
        incrBos.write(Integer.toString(args.length).getBytes(StandardCharsets.US_ASCII));
        incrBos.write('\r');
        incrBos.write('\n');
        for (byte[] a : args) {
            incrBos.write('$');
            incrBos.write(Integer.toString(a.length).getBytes(StandardCharsets.US_ASCII));
            incrBos.write('\r');
            incrBos.write('\n');
            incrBos.write(a);
            incrBos.write('\r');
            incrBos.write('\n');
        }
    }

    private void openNewIncr(int seq) throws IOException {
        incrFos = new FileOutputStream(aofDir.resolve(incrName(seq)).toFile(), false);
        incrBos = new BufferedOutputStream(incrFos);
        aofSelectedDb = -1;
    }

    private void openIncrForAppend(int seq) throws IOException {
        incrFos = new FileOutputStream(aofDir.resolve(incrName(seq)).toFile(), true);
        incrBos = new BufferedOutputStream(incrFos);
        aofSelectedDb = -1;
    }

    private void flushAndCloseIncr() throws IOException {
        if (incrBos != null) {
            incrBos.flush();
            incrFos.getFD().sync();
            incrBos.close();
            incrBos = null;
            incrFos = null;
        }
    }

    // ---- base / manifest ----------------------------------------------------

    private void writeBase(int seq, RdbSnapshot snapshot) throws IOException {
        Path base = aofDir.resolve(baseName(seq));
        try (FileOutputStream fos = new FileOutputStream(base.toFile());
             OutputStream out = new BufferedOutputStream(fos)) {
            RdbWriter.write(snapshot, out);
            out.flush();
            fos.getFD().sync();
        }
    }

    private void writeManifest(int seq) throws IOException {
        String content = "file " + baseName(seq) + " seq " + seq + " type b\n"
                + "file " + incrName(seq) + " seq " + seq + " type i\n";
        Path temp = Path.of(manifestPath + ".tmp-" + System.nanoTime());
        Files.writeString(temp, content);
        Files.move(temp, manifestPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void deleteOld(int seq) throws IOException {
        Files.deleteIfExists(aofDir.resolve(baseName(seq)));
        Files.deleteIfExists(aofDir.resolve(incrName(seq)));
    }

    // ---- load / replay ------------------------------------------------------

    private List<String[]> parseManifest() throws IOException {
        List<String[]> entries = new ArrayList<>();
        for (String line : Files.readAllLines(manifestPath)) {
            if (!line.isBlank()) {
                entries.add(line.trim().split("\\s+"));
            }
        }
        return entries;
    }

    private void replay(List<String[]> entries) throws IOException {
        loading = true;
        try {
            // Base file (type b) first.
            for (String[] e : entries) {
                if (fieldType(e).equals("b")) {
                    try (InputStream in = Files.newInputStream(aofDir.resolve(fieldName(e)))) {
                        Snapshots.loadRdbStream(in, context);
                    }
                }
            }
            // Then incr files (type i) in manifest order.
            CommandDispatcher dispatcher = new CommandDispatcher(context);
            ClientConnection replayConn = new ClientConnection(0, "aof", "aof", true);
            for (String[] e : entries) {
                if (fieldType(e).equals("i")) {
                    replayIncr(aofDir.resolve(fieldName(e)), dispatcher, replayConn);
                    currentSeq = Integer.parseInt(fieldSeq(e));
                }
            }
        } finally {
            loading = false;
        }
    }

    private void replayIncr(Path path, CommandDispatcher dispatcher, ClientConnection conn) throws IOException {
        byte[] data = Files.readAllBytes(path);
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            byte[][] args;
            while ((args = RespParser.parseRequest(buf)) != null) {
                if (args.length > 0) {
                    dispatcher.dispatch(new CommandContext(context, conn, args));
                }
            }
        } finally {
            buf.release();
        }
    }

    private static String fieldName(String[] e) {
        return e[1];
    }

    private static String fieldSeq(String[] e) {
        return e[3];
    }

    private static String fieldType(String[] e) {
        return e[5];
    }
}
