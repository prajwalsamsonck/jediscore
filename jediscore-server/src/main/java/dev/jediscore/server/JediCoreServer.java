package dev.jediscore.server;

import dev.jediscore.engine.PersistenceConfig;
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

        BootConfig boot = BootConfig.load(args);
        ServerConfig config = boot.server();
        PersistenceConfig persistenceConfig = boot.persistence();
        log.info("JediCore {} starting (RESP2/RESP3, single command thread)", VERSION);
        log.info("  java.version = {}", System.getProperty("java.version"));
        log.info("  run_id       = {}", config.runId());
        log.info("  config_file  = {}", boot.configFile() == null ? "(none)" : boot.configFile());
        log.info("  dir          = {}  appendonly = {}", persistenceConfig.dir(), persistenceConfig.appendOnly());

        JediCore jediCore = JediCore.start(config, persistenceConfig, boot.renameCommands(), boot.tls());
        jediCore.context().setStandalone(true);
        jediCore.context().setMaxClients(boot.maxClients());
        jediCore.context().setProtectedMode(boot.protectedMode());
        if (boot.configFile() != null) {
            jediCore.context().setConfigFile(boot.configFile());
        }
        if (boot.metricsPort() > 0) {
            try {
                jediCore.enableMetrics(boot.metricsPort());
            } catch (java.io.IOException e) {
                log.warn("Could not start metrics endpoint on port {}: {}", boot.metricsPort(), e.getMessage());
            }
        }
        if (boot.tls().enabled()) {
            log.info("  TLS          = enabled ({})", boot.tls().hasCertificate() ? "cert files" : "self-signed");
        }
        log.info("Ready to accept connections tcp on {}:{}", config.host(), jediCore.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received; stopping JediCore");
            // Graceful shutdown: persist state first (when save points are configured),
            // then release resources.
            if (jediCore.context().saveOnShutdown() && jediCore.context().persistence() != null) {
                try {
                    jediCore.context().persistence().save();
                    log.info("Final RDB save complete");
                } catch (RuntimeException e) {
                    log.warn("Final RDB save failed: {}", e.getMessage());
                }
            }
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
        String address = firstPositional(args);
        if (address != null && !address.isBlank()) {
            int colon = address.lastIndexOf(':');
            if (colon >= 0) {
                host = address.substring(0, colon);
                port = Integer.parseInt(address.substring(colon + 1));
            } else {
                port = Integer.parseInt(address);
            }
        }
        return ServerConfig.defaults(host, port);
    }

    /**
     * Parses persistence flags: {@code --dir}, {@code --appendonly yes|no},
     * {@code --appendfsync always|everysec|no}.
     *
     * @param args the command-line arguments
     * @return the persistence configuration
     */
    static PersistenceConfig parsePersistenceConfig(String[] args) {
        PersistenceConfig config = PersistenceConfig.defaults().withDir(flag(args, "--dir", "."));
        if ("yes".equalsIgnoreCase(flag(args, "--appendonly", "no"))) {
            config = config.withAppendOnly(flag(args, "--appendfsync", "everysec"));
        }
        return config;
    }

    /** Returns the first non-flag argument (the address), or {@code null}. */
    private static String firstPositional(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                i++; // skip the flag's value
            } else {
                return args[i];
            }
        }
        return null;
    }

    /** Returns the value following {@code name}, or {@code fallback} if absent. */
    private static String flag(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
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
