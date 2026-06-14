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
        for (int i = 0; i < databases.length; i++) {
            databases[i] = new Database(i, System::currentTimeMillis);
        }
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
