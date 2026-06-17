package dev.jediscore.server;

import dev.jediscore.commands.CoreCommands;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.Persistence;
import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.engine.ServerCron;
import dev.jediscore.network.RespServer;
import dev.jediscore.persistence.RdbPersistence;
import dev.jediscore.replication.ReplicaLink;

/**
 * The composition root: wires the engine, commands, and network layer into a
 * running server instance, and owns their lifecycle.
 *
 * <p>This is the single place that knows about all the modules at once. It is
 * intentionally small — construct the registry, populate it, start the command
 * loop, then start the network server — and it returns a handle the caller (the
 * {@code main} method, or a test) uses to discover the bound port and to shut
 * everything down cleanly.
 */
public final class JediCore implements AutoCloseable {

    private final ServerContext context;
    private final RespServer server;
    private final CommandExecutor executor;
    private final ServerCron cron;
    private final Persistence persistence;
    private final int port;

    private JediCore(ServerContext context, RespServer server, CommandExecutor executor,
                     ServerCron cron, Persistence persistence, int port) {
        this.context = context;
        this.server = server;
        this.executor = executor;
        this.cron = cron;
        this.persistence = persistence;
        this.port = port;
    }

    /**
     * Builds and starts a server with default (RDB-only) persistence.
     *
     * @param config the server configuration
     * @return a running server handle
     * @throws InterruptedException if interrupted while binding the socket
     */
    public static JediCore start(ServerConfig config) throws InterruptedException {
        return start(config, PersistenceConfig.defaults());
    }

    /**
     * Builds and starts a server with explicit persistence configuration.
     *
     * @param config            the server configuration
     * @param persistenceConfig the persistence configuration (dir, save points, …)
     * @return a running server handle
     * @throws InterruptedException if interrupted while binding the socket
     */
    public static JediCore start(ServerConfig config, PersistenceConfig persistenceConfig)
            throws InterruptedException {
        return start(config, persistenceConfig, java.util.Map.of());
    }

    /**
     * Builds and starts a server, additionally applying {@code rename-command}
     * directives before the command table is published.
     *
     * @param config            the server configuration
     * @param persistenceConfig the persistence configuration
     * @param renameCommands    {@code from → to} renames (empty {@code to} disables)
     * @return a running server handle
     * @throws InterruptedException if interrupted while binding the socket
     */
    public static JediCore start(ServerConfig config, PersistenceConfig persistenceConfig,
                                 java.util.Map<String, String> renameCommands) throws InterruptedException {
        CommandRegistry registry = new CommandRegistry();
        CoreCommands.registerAll(registry);
        renameCommands.forEach(registry::rename);

        // The command loop is created (and thus its thread started) only after
        // the registry is fully populated, establishing safe publication of the
        // command table to that thread.
        CommandExecutor executor = new CommandExecutor("jedicore-cmd");
        ServerContext context = new ServerContext(config, registry, executor);

        // Wire persistence and load any existing dataset before accepting clients.
        RdbPersistence persistence = new RdbPersistence(context, persistenceConfig);
        context.setPersistence(persistence);
        persistence.loadOnStartup();

        // Wire the replica-side link so REPLICAOF can replicate from a master.
        context.setMasterLink(new ReplicaLink(context));
        // Persist on shutdown when save points are configured (Redis's default).
        context.setSaveOnShutdown(!persistenceConfig.savePoints().isEmpty());

        RespServer server = new RespServer(context);
        int boundPort = server.start();

        // Start background maintenance (active expiration, save-point checks).
        ServerCron cron = new ServerCron(context);
        cron.start();

        return new JediCore(context, server, executor, cron, persistence, boundPort);
    }

    /** @return the bound TCP port */
    public int port() {
        return port;
    }

    /** @return the shared server context */
    public ServerContext context() {
        return context;
    }

    /**
     * Blocks until the server channel is closed (e.g. by {@link #close()} from a
     * shutdown hook).
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitShutdown() throws InterruptedException {
        server.awaitShutdown();
    }

    /** Stops background maintenance, the network server, persistence, then the command loop. */
    @Override
    public void close() {
        cron.close();
        if (context.masterLink() != null) {
            context.masterLink().disconnect();
        }
        server.close();
        persistence.shutdown();
        context.blocking().shutdown();
        executor.close();
    }
}
