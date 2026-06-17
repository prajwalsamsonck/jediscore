package dev.jediscore.engine;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A name → {@link CommandSpec} table.
 *
 * <p><strong>Concurrency.</strong> The registry is populated once at startup,
 * before the command thread or any I/O thread is started, and is treated as
 * immutable thereafter. Publishing it via thread start establishes a
 * happens-before edge, so concurrent reads of the underlying {@link HashMap}
 * during serving are safe without synchronisation. Do not register commands
 * after the server begins accepting connections.
 */
public final class CommandRegistry {

    private final Map<String, CommandSpec> byName = new HashMap<>();

    /**
     * Registers a command. Lookups are case-insensitive; the key is the
     * upper-cased name.
     *
     * @param spec the command spec
     * @throws IllegalStateException if a command with the same name already exists
     */
    public void register(CommandSpec spec) {
        String key = spec.name().toUpperCase(Locale.ROOT);
        if (byName.putIfAbsent(key, spec) != null) {
            throw new IllegalStateException("duplicate command registration: " + spec.name());
        }
    }

    /**
     * Looks up a command by name (case-insensitive).
     *
     * @param name the command name as sent by the client
     * @return the spec, or {@code null} if unknown
     */
    public CommandSpec lookup(String name) {
        return byName.get(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Renames or disables a command (Redis's {@code rename-command}). Renaming to
     * an empty string disables the command (it is removed from the table).
     *
     * @param from the existing command name
     * @param to   the new name, or {@code ""} to disable
     */
    public void rename(String from, String to) {
        CommandSpec spec = byName.remove(from.toUpperCase(Locale.ROOT));
        if (spec == null || to.isEmpty()) {
            return; // unknown command, or disabling it
        }
        byName.put(to.toUpperCase(Locale.ROOT),
                new CommandSpec(to, spec.arity(), spec.flags(), spec.handler()));
    }

    /** @return the number of registered commands */
    public int size() {
        return byName.size();
    }

    /** @return all registered specs */
    public Collection<CommandSpec> all() {
        return byName.values();
    }
}
