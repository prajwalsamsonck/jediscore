package dev.jediscore.server;

import dev.jediscore.engine.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the JediCore server.
 *
 * <p>It parses a minimal configuration from the arguments, starts the server via
 * {@link JediCore}, installs a shutdown hook for graceful termination, and then
 * blocks until the server is closed.
 *
 * <p>Usage: {@code jediscore [port] | [host:port]} (defaults to
 * {@code 127.0.0.1:6379}).
 */
public final class JediCoreServer {

    private static final Logger log = LoggerFactory.getLogger(JediCoreServer.class);

    /** Human-readable version tag for the current development phase. */
    public static final String VERSION = "0.1.0-phase1";

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 6379;

    private JediCoreServer() {
        // Application entry point; not instantiable.
    }

    /**
     * Boots the server and blocks until shutdown.
     *
     * @param args optional {@code port} or {@code host:port}
     * @throws InterruptedException if interrupted while running
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.print(banner());

        ServerConfig config = parseConfig(args);
        log.info("JediCore {} starting (RESP2/RESP3, single command thread)", VERSION);
        log.info("  java.version = {}", System.getProperty("java.version"));
        log.info("  run_id       = {}", config.runId());

        JediCore jediCore = JediCore.start(config);
        log.info("Ready to accept connections tcp on {}:{}", config.host(), jediCore.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received; stopping JediCore");
            jediCore.close();
        }, "jedicore-shutdown"));

        jediCore.awaitShutdown();
    }

    /**
     * Parses {@code [port]} or {@code [host:port]} into a configuration.
     *
     * @param args the command-line arguments
     * @return the parsed configuration, falling back to defaults
     */
    static ServerConfig parseConfig(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (args.length >= 1 && !args[0].isBlank()) {
            String arg = args[0];
            int colon = arg.lastIndexOf(':');
            if (colon >= 0) {
                host = arg.substring(0, colon);
                port = Integer.parseInt(arg.substring(colon + 1));
            } else {
                port = Integer.parseInt(arg);
            }
        }
        return ServerConfig.defaults(host, port);
    }

    /**
     * Builds the startup banner.
     *
     * @return a multi-line ASCII banner ending with a trailing newline
     */
    static String banner() {
        return """

                   __         _ _  _____
                  |  |___ ___| |_||     |___ ___ ___
                  |  | -_|  _| | ||   --| . |  _| -_|
              |___|___|___|_|_||_____|___|_| |___|
              JediCore %s  -  a wire-compatible Redis engine in Java 21
            ------------------------------------------------------------
            """.formatted(VERSION);
    }
}
