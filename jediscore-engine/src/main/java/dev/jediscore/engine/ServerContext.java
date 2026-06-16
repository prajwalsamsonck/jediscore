package dev.jediscore.engine;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared server-wide state and services: configuration, the command registry,
 * the command-execution loop, and the live-connection table.
 *
 * <p>The connection table is a {@link ConcurrentHashMap} because connections are
 * added/removed on Netty I/O threads (on connect/disconnect) while commands such
 * as {@code CLIENT INFO} read it on the command thread.
 */
public final class ServerContext {

    private final ServerConfig config;
    private final CommandRegistry registry;
    private final CommandExecutor executor;
    private final AtomicLong clientIdSeq = new AtomicLong(0);
    private final ConcurrentHashMap<Long, ClientConnection> clients = new ConcurrentHashMap<>();
    private final Database[] databases;
    private final PubSubRegistry pubsub = new PubSubRegistry();
    private final WatchTable watchTable;
    private final BlockingManager blocking;
    private final ReplicationManager replication;
    private final ServerStats stats = new ServerStats();
    private CommandDispatcher dispatcher;
    private MasterLink masterLink;
    private Persistence persistence;
    private long dirty;

    /**
     * Creates a server context, allocating the configured number of databases.
     *
     * @param config   the configuration
     * @param registry the (already populated) command registry
     * @param executor the command-execution loop
     */
    public ServerContext(ServerConfig config, CommandRegistry registry, CommandExecutor executor) {
        this.config = config;
        this.registry = registry;
        this.executor = executor;
        this.databases = new Database[config.databases()];
        this.watchTable = new WatchTable(config.databases());
        KeyspaceListener casListener = new KeyspaceListener() {
            @Override public void onKeyModified(int db, dev.jediscore.datastructures.Bytes key) {
                watchTable.touch(db, key);
            }
            @Override public void onFlushed(int db) {
                watchTable.touchAll(db);
            }
        };
        for (int i = 0; i < databases.length; i++) {
            databases[i] = new Database(i, System::currentTimeMillis);
            databases[i].setListener(casListener);
            databases[i].setStats(stats);
        }
        this.blocking = new BlockingManager(this);
        this.replication = new ReplicationManager(config.runId());
    }

    /**
     * Returns the database at the given index.
     *
     * @param index the database index
     * @return the database
     * @throws IndexOutOfBoundsException if {@code index} is outside {@code [0, databaseCount())}
     */
    public Database database(int index) {
        return databases[index];
    }

    /** @return the number of databases */
    public int databaseCount() {
        return databases.length;
    }

    /** @return the persistence service, or {@code null} if persistence is not wired */
    public Persistence persistence() {
        return persistence;
    }

    /**
     * Attaches the persistence service (called once during startup wiring).
     *
     * @param persistence the service
     */
    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    /** @return the number of write operations since the dirty counter was last reset */
    public long dirty() {
        return dirty;
    }

    /**
     * Records write operations against the dirty counter (drives RDB save points).
     *
     * @param count the number of changes
     */
    public void markDirty(long count) {
        dirty += count;
    }

    /** Resets the dirty counter (after a successful save). */
    public void resetDirty() {
        dirty = 0;
    }

    /** @return the approximate total memory used across all databases, in bytes */
    public long usedMemory() {
        long total = 0;
        for (Database database : databases) {
            total += database.memoryUsed();
        }
        return total;
    }

    /**
     * Swaps the contents of two databases (for {@code SWAPDB}). Connections hold a
     * database <em>index</em>, not a reference, so after the swap every client
     * observing index {@code a} transparently sees what was at index {@code b}.
     *
     * @param a the first database index
     * @param b the second database index
     */
    public void swapDatabases(int a, int b) {
        Database tmp = databases[a];
        databases[a] = databases[b];
        databases[b] = tmp;
        // Keep each database's reported index aligned with its array slot so the
        // CAS modification signal (which carries the index) matches the slot under
        // which watches are registered.
        databases[a].reindex(a);
        databases[b].reindex(b);
        // Every key in both databases effectively changed identity, so any watcher
        // on either database must see its transaction invalidated.
        watchTable.touchAll(a);
        watchTable.touchAll(b);
    }

    /** @return the configuration */
    public ServerConfig config() {
        return config;
    }

    /** @return the command registry */
    public CommandRegistry registry() {
        return registry;
    }

    /** @return the pub/sub fan-out registry (command-thread confined) */
    public PubSubRegistry pubsub() {
        return pubsub;
    }

    /** @return the WATCH/CAS table (command-thread confined) */
    public WatchTable watchTable() {
        return watchTable;
    }

    /** @return the blocking-command wait-queue manager (command-thread confined) */
    public BlockingManager blocking() {
        return blocking;
    }

    /** @return the live server statistics counters */
    public ServerStats stats() {
        return stats;
    }

    /** @return the master-side replication manager (command-thread confined) */
    public ReplicationManager replication() {
        return replication;
    }

    /** @return the replica-side link to a master, or {@code null} if not wired */
    public MasterLink masterLink() {
        return masterLink;
    }

    /**
     * Attaches the replica-side link (called once during startup wiring).
     *
     * @param masterLink the link
     */
    public void setMasterLink(MasterLink masterLink) {
        this.masterLink = masterLink;
    }

    /**
     * Propagates the full side effects of a write that happened outside the
     * dispatcher's own write-tracking — namely a blocking command being served:
     * marks the dataset dirty, invalidates WATCHes, and propagates the
     * <em>effective</em> command (e.g. {@code LPOP}, never {@code BLPOP}) to the
     * AOF and replicas.
     *
     * @param db   the database index the write applied to
     * @param args the effective command's argument vector
     */
    public void propagateWrite(int db, byte[][] args) {
        markDirty(1);
        watchTable.touchByArguments(db, args);
        propagateEffect(db, args);
    }

    /**
     * Propagates a single effective command to the AOF and the replication stream.
     * Unlike {@link #propagateWrite}, it does <em>not</em> touch the dirty counter
     * or WATCH table — those are accounted once per original command by the
     * dispatcher, even when the command rewrites itself into several.
     *
     * @param db   the database index
     * @param args the effective command's argument vector
     */
    public void propagateEffect(int db, byte[][] args) {
        if (persistence != null && persistence.appendOnlyEnabled()) {
            persistence.feedAppendOnly(db, args);
        }
        replication.propagate(db, args);
    }

    /** @return the command dispatcher (used by {@code EXEC} to replay queued commands) */
    public CommandDispatcher dispatcher() {
        return dispatcher;
    }

    /**
     * Attaches the command dispatcher (called once during startup wiring, before
     * the server accepts connections).
     *
     * @param dispatcher the dispatcher
     */
    public void setDispatcher(CommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** @return the command-execution loop */
    public CommandExecutor executor() {
        return executor;
    }

    /** @return whether AUTH is required before other commands */
    public boolean requiresAuth() {
        return config.requiresAuth();
    }

    /**
     * Allocates the next unique client id.
     *
     * @return a fresh, monotonically increasing id
     */
    public long nextClientId() {
        return clientIdSeq.incrementAndGet();
    }

    /**
     * Registers a live connection.
     *
     * @param connection the connection
     */
    public void register(ClientConnection connection) {
        clients.put(connection.id(), connection);
    }

    /**
     * Removes a connection from the live table.
     *
     * @param connection the connection
     */
    public void unregister(ClientConnection connection) {
        clients.remove(connection.id());
    }

    /** @return the current live connections */
    public Collection<ClientConnection> connections() {
        return clients.values();
    }

    /** @return the number of live connections */
    public int connectionCount() {
        return clients.size();
    }
}
