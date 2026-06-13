package dev.jediscore.server;

import dev.jediscore.commands.CoreCommands;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.engine.ServerCron;
import dev.jediscore.network.RespServer;

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
    private final int port;

    private JediCore(ServerContext context, RespServer server, CommandExecutor executor,
                     ServerCron cron, int port) {
        this.context = context;
        this.server = server;
        this.executor = executor;
        this.cron = cron;
        this.port = port;
    }

    /**
     * Builds and starts a server from the given configuration.
     *
     * @param config the server configuration
     * @return a running server handle
     * @throws InterruptedException if interrupted while binding the socket
     */
    public static JediCore start(ServerConfig config) throws InterruptedException {
        CommandRegistry registry = new CommandRegistry();
        CoreCommands.registerAll(registry);

        // The command loop is created (and thus its thread started) only after
        // the registry is fully populated, establishing safe publication of the
        // command table to that thread.
        CommandExecutor executor = new CommandExecutor("jedicore-cmd");
        ServerContext context = new ServerContext(config, registry, executor);

        RespServer server = new RespServer(context);
        int boundPort = server.start();

        // Start background maintenance (active expiration; eviction housekeeping).
        ServerCron cron = new ServerCron(context);
        cron.start();

        return new JediCore(context, server, executor, cron, boundPort);
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

    /** Stops background maintenance, then the network server, then the command loop. */
    @Override
    public void close() {
        cron.close();
        server.close();
        executor.close();
    }
}
