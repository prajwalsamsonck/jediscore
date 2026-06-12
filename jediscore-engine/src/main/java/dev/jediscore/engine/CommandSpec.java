package dev.jediscore.engine;

import java.util.Set;

/**
 * Static metadata plus the handler for one command.
 *
 * @param name    the command name in lowercase (as Redis reports it)
 * @param arity   Redis-style arity: a positive {@code N} means exactly {@code N}
 *                arguments (including the command name); a negative {@code -N}
 *                means at least {@code N}
 * @param flags   command flags (e.g. {@code readonly}, {@code fast}, {@code loading})
 * @param handler the implementation
 */
public record CommandSpec(String name, int arity, Set<String> flags, Command handler) {

    /**
     * Convenience factory with no flags.
     *
     * @param name    lowercase command name
     * @param arity   Redis-style arity
     * @param handler the implementation
     * @return the spec
     */
    public static CommandSpec of(String name, int arity, Command handler) {
        return new CommandSpec(name, arity, Set.of(), handler);
    }

    /**
     * Convenience factory with flags.
     *
     * @param name    lowercase command name
     * @param arity   Redis-style arity
     * @param flags   command flags
     * @param handler the implementation
     * @return the spec
     */
    public static CommandSpec of(String name, int arity, Set<String> flags, Command handler) {
        return new CommandSpec(name, arity, flags, handler);
    }

    /**
     * Checks an argument count against this command's arity rule.
     *
     * @param argc the number of arguments including the command name
     * @return {@code true} if the count is acceptable
     */
    public boolean acceptsArgCount(int argc) {
        // Mirrors Redis: invalid when (arity >= 0 && argc != arity) || (argc < -arity).
        if (arity >= 0) {
            return argc == arity;
        }
        return argc >= -arity;
    }
}
