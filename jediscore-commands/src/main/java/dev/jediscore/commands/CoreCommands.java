package dev.jediscore.commands;

import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import java.util.Set;

/**
 * Registers the connection/server commands implemented in Phase 1.
 *
 * <p>Arities and flags follow Redis. {@code -N} means "at least N arguments
 * (including the command name)"; a positive {@code N} means "exactly N".
 */
public final class CoreCommands {

    private CoreCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers every Phase-1 command into the given registry. Call once at
     * startup, before the server begins serving.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        // Connection & server (Phase 1)
        registry.register(CommandSpec.of("ping", -1, Set.of("fast"), new PingCommand()));
        registry.register(CommandSpec.of("echo", 2, Set.of("fast"), new EchoCommand()));
        registry.register(CommandSpec.of("hello", -1, Set.of("fast", "no-auth"), new HelloCommand()));
        registry.register(CommandSpec.of("command", -1, Set.of("loading"), new CommandCommand()));
        registry.register(CommandSpec.of("quit", 1, Set.of("fast", "no-auth"), new QuitCommand()));
        registry.register(CommandSpec.of("reset", 1, Set.of("fast", "no-auth"), new ResetCommand()));
        registry.register(CommandSpec.of("auth", -2, Set.of("fast", "no-auth"), new AuthCommand()));
        registry.register(CommandSpec.of("client", -2, Set.of("admin"), new ClientCommand()));

        // Keyspace & data types (Phase 2)
        GenericCommands.registerAll(registry);
        StringCommands.registerAll(registry);
        HashCommands.registerAll(registry);
        ListCommands.registerAll(registry);
        SetCommands.registerAll(registry);
        ZSetCommands.registerAll(registry);
        ScanCommands.registerAll(registry);
        MemoryCommands.registerAll(registry);
        PersistenceCommands.registerAll(registry);

        // Advanced command semantics (Phase 5)
        PubSubCommands.registerAll(registry);
        TransactionCommands.registerAll(registry);
        BlockingCommands.registerAll(registry);
        ScriptingCommands.registerAll(registry);
        ReplicationCommands.registerAll(registry);
        ServerCommands.registerAll(registry);
        DiagnosticsCommands.registerAll(registry);
    }
}
