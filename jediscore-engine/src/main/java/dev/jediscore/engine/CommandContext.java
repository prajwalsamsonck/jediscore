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

    /**
     * Creates a context.
     *
     * @param server     the server context
     * @param connection the issuing connection
     * @param args       the argument vector ({@code args[0]} is the command name)
     */
    public CommandContext(ServerContext server, ClientConnection connection, byte[][] args) {
        this.server = server;
        this.connection = connection;
        this.args = args;
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
