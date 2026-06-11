package dev.jediscore.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the JediCore server.
 *
 * <p><strong>Phase&nbsp;0 behaviour:</strong> this proves the assembled
 * application builds, launches on the pinned Java&nbsp;21 toolchain, prints a
 * banner, logs its environment, and exits cleanly with status&nbsp;0. There is
 * deliberately no networking yet — the Netty listener arrives in Phase&nbsp;2.
 *
 * <p>The body is intentionally side-effect-light and synchronous so it can be
 * driven directly from a unit test (see {@code JediCoreServerTest}).
 */
public final class JediCoreServer {

    private static final Logger log = LoggerFactory.getLogger(JediCoreServer.class);

    /** Human-readable version tag for the current development phase. */
    public static final String VERSION = "0.0.1-phase0";

    private JediCoreServer() {
        // Application entry point; not instantiable.
    }

    /**
     * Boots the server.
     *
     * @param args command-line arguments (ignored in Phase&nbsp;0)
     */
    public static void main(String[] args) {
        run(args);
    }

    /**
     * The testable core of {@link #main(String[])}. Kept package-visible so a
     * unit test can assert it completes without throwing and without calling
     * {@code System.exit}.
     *
     * @param args command-line arguments (ignored in Phase&nbsp;0)
     */
    static void run(String[] args) {
        System.out.print(banner());

        log.info("JediCore {} starting up", VERSION);
        log.info("  java.version  = {}", System.getProperty("java.version"));
        log.info("  java.vendor   = {}", System.getProperty("java.vendor"));
        log.info("  os            = {} {} ({})",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));
        log.info("  args          = {}", args.length == 0 ? "(none)" : String.join(" ", args));
        log.info("Phase 0: no subsystems wired yet (no networking, persistence, or replication).");
        log.info("Shutting down cleanly. Goodbye.");
    }

    /**
     * Builds the startup banner.
     *
     * @return a multi-line ASCII banner ending with a trailing newline
     */
    static String banner() {
        // Text block keeps the art readable in source; %s injects the version.
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
