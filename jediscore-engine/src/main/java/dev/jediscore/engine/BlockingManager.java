package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The wait-queue behind blocking commands (BLPOP/BRPOP/BLMOVE/BLMPOP/BZPOPMIN/MAX,
 * WAIT).
 *
 * <p><strong>Why a wait-queue, not a thread per client.</strong> The single
 * command thread must never block, so a blocking command that cannot be satisfied
 * registers the client here and returns no reply; the command thread keeps
 * serving. When a later write makes a key ready, the blocked client is served on
 * the command thread and its reply is pushed out-of-band via {@link
 * ClientConnection#deliver}. This is event-driven and costs nothing while idle —
 * strictly better than parking a (virtual or platform) thread per blocked client.
 *
 * <p><strong>FIFO &amp; re-validation.</strong> Each {@code (db, key)} keeps an
 * insertion-ordered deque, so the longest-waiting client is served first. Serving
 * re-runs the operation's real pop ({@link BlockingOp#attempt}), so a spurious
 * signal, an emptied key, or a key that turned into the wrong type are all handled
 * correctly (a type clash unblocks the client with an error, as in Redis).
 *
 * <p><strong>Timeouts.</strong> A shared daemon scheduler fires at the deadline
 * and submits the timeout back to the command thread, so the timeout logic is
 * single-threaded like everything else — precise, and never busy-waiting.
 *
 * <p><strong>Threading.</strong> Every mutation runs on the command thread:
 * blocking, signalling (from the dispatcher after writes), timeout firing
 * (submitted to the command thread), and disconnect cleanup (also submitted). The
 * maps therefore need no locking.
 */
public final class BlockingManager {

    private final ServerContext server;
    private final List<Map<Bytes, ArrayDeque<BlockedClient>>> perDb;
    private final Map<ClientConnection, BlockedClient> active = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jedicore-block-timeout");
        t.setDaemon(true);
        return t;
    });

    // Pending readiness signals, drained iteratively so a serve that itself makes
    // another key ready (e.g. BLMOVE feeding a BLPOP) never recurses.
    private final ArrayDeque<Signal> pending = new ArrayDeque<>();
    private boolean draining;

    /**
     * Creates a manager bound to the server context.
     *
     * @param server the server context
     */
    public BlockingManager(ServerContext server) {
        this.server = server;
        this.perDb = new ArrayList<>(server.databaseCount());
        for (int i = 0; i < server.databaseCount(); i++) {
            perDb.add(new HashMap<>());
        }
    }

    /** @return whether any client is currently blocked (a fast gate for the dispatcher) */
    public boolean hasBlockedClients() {
        return !active.isEmpty();
    }

    /** @return the number of currently blocked clients (for {@code INFO clients}) */
    public int blockedCount() {
        return active.size();
    }

    /**
     * Blocks a connection on a set of keys until one is ready or the timeout fires.
     *
     * @param conn           the connection
     * @param db             the database index
     * @param keys           the keys to wait on (empty for {@code WAIT})
     * @param op             the retry-able operation
     * @param timeoutSeconds the timeout in seconds; {@code 0} means block forever
     */
    public void block(ClientConnection conn, int db, List<Bytes> keys, BlockingOp op, double timeoutSeconds) {
        BlockedClient bc = new BlockedClient(conn, db, keys, op);
        for (Bytes key : keys) {
            perDb.get(db).computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(bc);
        }
        active.put(conn, bc);
        if (timeoutSeconds > 0) {
            long ms = Math.max(1, (long) (timeoutSeconds * 1000));
            bc.timeoutTask = scheduler.schedule(
                    () -> server.executor().submit(() -> finish(bc, op.timeoutReply(), true)),
                    ms, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Signals that the given command's argument keys may now be ready, serving any
     * blocked clients. Called by the dispatcher after a successful write.
     *
     * @param db   the database index
     * @param args the command argument vector ({@code args[0]} is the command name)
     */
    public void signalKeys(int db, byte[][] args) {
        if (active.isEmpty()) {
            return;
        }
        for (int i = 1; i < args.length; i++) {
            pending.add(new Signal(db, new Bytes(args[i])));
        }
        drain();
    }

    /**
     * Signals a single key as possibly ready (used by serves that feed another key,
     * such as {@code BLMOVE} pushing to its destination).
     *
     * @param db  the database index
     * @param key the key
     */
    public void signalKey(int db, Bytes key) {
        pending.add(new Signal(db, key));
        drain();
    }

    /**
     * Re-attempts every active blocked client. Used when a condition that is not a
     * keyspace write changes — currently a {@code REPLCONF ACK} that may satisfy a
     * pending {@code WAIT}. Clients waiting on keys simply re-check and stay blocked
     * if their key is still empty (the attempt is idempotent).
     */
    public void signalAll() {
        if (active.isEmpty()) {
            return;
        }
        for (BlockedClient bc : new ArrayList<>(active.values())) {
            if (bc.served) {
                continue;
            }
            RespValue reply;
            try {
                reply = bc.op.attempt(server, bc.conn);
            } catch (CommandException e) {
                reply = RespValue.error(e.getMessage());
            }
            if (reply != null) {
                finish(bc, reply, true);
            }
        }
    }

    /**
     * Cancels any block held by a connection (on disconnect or {@code RESET}); no
     * reply is delivered.
     *
     * @param conn the connection
     */
    public void cancel(ClientConnection conn) {
        BlockedClient bc = active.get(conn);
        if (bc != null) {
            finish(bc, null, false);
        }
    }

    /** Stops the timeout scheduler (called at server shutdown). */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ---- internals -----------------------------------------------------------

    private void drain() {
        if (draining) {
            return; // a serve re-entered; the outer loop will pick up the new signal
        }
        draining = true;
        try {
            Signal s;
            while ((s = pending.poll()) != null) {
                serveKey(s.db(), s.key());
            }
        } finally {
            draining = false;
        }
    }

    private void serveKey(int db, Bytes key) {
        Map<Bytes, ArrayDeque<BlockedClient>> dbQueues = perDb.get(db);
        ArrayDeque<BlockedClient> queue = dbQueues.get(key);
        while (queue != null && !queue.isEmpty()) {
            BlockedClient bc = queue.peekFirst();
            if (bc.served) {
                queue.pollFirst();
                queue = dbQueues.get(key);
                continue;
            }
            RespValue reply;
            try {
                reply = bc.op.attempt(server, bc.conn);
            } catch (CommandException e) {
                reply = RespValue.error(e.getMessage()); // wrong type, etc.: unblock with the error
            }
            if (reply == null) {
                return; // the longest-waiting client cannot be served → nothing to do
            }
            finish(bc, reply, true);
            queue = dbQueues.get(key);
        }
    }

    private void finish(BlockedClient bc, RespValue reply, boolean deliver) {
        if (bc.served) {
            return;
        }
        bc.served = true;
        Map<Bytes, ArrayDeque<BlockedClient>> dbQueues = perDb.get(bc.db);
        for (Bytes key : bc.keys) {
            ArrayDeque<BlockedClient> queue = dbQueues.get(key);
            if (queue != null) {
                queue.remove(bc);
                if (queue.isEmpty()) {
                    dbQueues.remove(key);
                }
            }
        }
        if (bc.timeoutTask != null) {
            bc.timeoutTask.cancel(false);
        }
        active.remove(bc.conn, bc);
        if (deliver) {
            bc.conn.deliver(reply);
        }
    }

    private record Signal(int db, Bytes key) { }

    /** One blocked connection: the keys it waits on, the retry, and its timeout. */
    private static final class BlockedClient {
        private final ClientConnection conn;
        private final int db;
        private final List<Bytes> keys;
        private final BlockingOp op;
        private ScheduledFuture<?> timeoutTask;
        private boolean served;

        BlockedClient(ClientConnection conn, int db, List<Bytes> keys, BlockingOp op) {
            this.conn = conn;
            this.db = db;
            this.keys = keys;
            this.op = op;
        }
    }
}
