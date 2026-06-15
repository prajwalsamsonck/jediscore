package dev.jediscore.engine;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Everything a command needs for one invocation: the server, the issuing
 * connection, and the argument vector. Created fresh per request on the command
 * thread, so it is single-threaded by construction.
 */
public final class CommandContext {

    private final ServerContext server;
    private final ClientConnection connection;
    private final byte[][] args;
    private final boolean blockingAllowed;
    private java.util.List<byte[][]> propagationOverride;

    /**
     * Creates a context in which blocking commands may block.
     *
     * @param server     the server context
     * @param connection the issuing connection
     * @param args       the argument vector ({@code args[0]} is the command name)
     */
    public CommandContext(ServerContext server, ClientConnection connection, byte[][] args) {
        this(server, connection, args, true);
    }

    /**
     * Creates a context, specifying whether blocking commands may block.
     *
     * @param server          the server context
     * @param connection      the issuing connection
     * @param args            the argument vector ({@code args[0]} is the command name)
     * @param blockingAllowed {@code false} inside {@code EXEC} (and other contexts
     *                        where a command must not park), so a blocking command
     *                        that cannot be satisfied returns its timeout reply now
     */
    public CommandContext(ServerContext server, ClientConnection connection, byte[][] args,
                          boolean blockingAllowed) {
        this.server = server;
        this.connection = connection;
        this.args = args;
        this.blockingAllowed = blockingAllowed;
    }

    /** @return whether a blocking command issued in this context may park the client */
    public boolean blockingAllowed() {
        return blockingAllowed;
    }

    /**
     * Overrides what is propagated to the AOF and replicas for this command, so a
     * non-deterministic command rewrites itself to a deterministic form (e.g.
     * {@code EXPIRE}→{@code PEXPIREAT}, {@code SPOP}→{@code SREM}). May be called
     * more than once to propagate several commands; calling it at all suppresses
     * the verbatim propagation.
     *
     * @param command the command to propagate instead of the verbatim one
     */
    public void propagate(byte[][] command) {
        if (propagationOverride == null) {
            propagationOverride = new java.util.ArrayList<>(2);
        }
        propagationOverride.add(command);
    }

    /**
     * Suppresses propagation entirely for this command (a write that turned out to
     * be a no-op, e.g. {@code SPOP} on a missing key).
     */
    public void suppressPropagation() {
        if (propagationOverride == null) {
            propagationOverride = new java.util.ArrayList<>(0);
        }
    }

    /**
     * @return the explicit propagation override (an empty list suppresses
     *         propagation entirely), or {@code null} to propagate verbatim
     */
    public java.util.List<byte[][]> propagationOverride() {
        return propagationOverride;
    }

    /** @return the server context */
    public ServerContext server() {
        return server;
    }

    /** @return the issuing connection */
    public ClientConnection connection() {
        return connection;
    }

    /** @return the database currently selected by the issuing connection */
    public Database database() {
        return server.database(connection.db());
    }

    /** @return the number of arguments, including the command name */
    public int argCount() {
        return args.length;
    }

    /** @return the raw argument vector (for AOF propagation; do not mutate) */
    public byte[][] args() {
        return args;
    }

    /**
     * Returns the raw bytes of an argument.
     *
     * @param index the argument index ({@code 0} is the command name)
     * @return the argument bytes
     */
    public byte[] arg(int index) {
        return args[index];
    }

    /**
     * Returns an argument decoded as a UTF-8 string. Use for command names,
     * subcommands, and option keywords — not for binary-safe keys/values.
     *
     * @param index the argument index
     * @return the decoded string
     */
    public String argText(int index) {
        return new String(args[index], StandardCharsets.UTF_8);
    }

    /**
     * Returns an argument upper-cased (ASCII) for keyword comparison.
     *
     * @param index the argument index
     * @return the upper-cased argument
     */
    public String argUpper(int index) {
        return argText(index).toUpperCase(Locale.ROOT);
    }

    /** @return the command name (lower-cased), i.e. {@code args[0]} */
    public String commandName() {
        return argText(0).toLowerCase(Locale.ROOT);
    }
}
