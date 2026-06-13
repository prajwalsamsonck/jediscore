package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.MemoryEstimator;
import dev.jediscore.protocol.RespValue;

/**
 * The {@code MEMORY} introspection command.
 *
 * <p>{@code MEMORY USAGE key} reports the approximate bytes a key and its value
 * occupy (see {@link MemoryEstimator} for why it is an estimate); {@code MEMORY
 * DOCTOR} gives a short, human-readable health summary.
 */
public final class MemoryCommands {

    private MemoryCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the memory command.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("memory", -2, MemoryCommands::memory));
    }

    private static RespValue memory(CommandContext ctx) {
        String sub = ctx.argUpper(1);
        return switch (sub) {
            case "USAGE" -> usage(ctx);
            case "DOCTOR" -> RespValue.bulk(doctor(ctx));
            case "HELP" -> new RespValue.Array(java.util.List.of(
                    RespValue.simple("MEMORY USAGE <key> [SAMPLES <count>]"),
                    RespValue.simple("MEMORY DOCTOR")));
            default -> throw new CommandException(
                    "ERR Unknown subcommand or wrong number of arguments for '" + ctx.argText(1)
                            + "'. Try MEMORY HELP.");
        };
    }

    private static RespValue usage(CommandContext ctx) {
        if (ctx.argCount() != 3 && ctx.argCount() != 5) {
            throw CommandException.syntax();
        }
        if (ctx.argCount() == 5) {
            // SAMPLES <n>: accepted for compatibility; we compute the value fully.
            if (!ctx.argUpper(3).equals("SAMPLES")) {
                throw CommandException.syntax();
            }
            Keyspaces.parseLong(ctx.arg(4));
        }
        RedisValue value = ctx.database().peek(new Bytes(ctx.arg(2)));
        if (value == null) {
            return RespValue.NULL;
        }
        return RespValue.integer(MemoryEstimator.usage(new Bytes(ctx.arg(2)), value));
    }

    private static String doctor(CommandContext ctx) {
        long used = ctx.server().usedMemory();
        long max = ctx.server().config().maxMemory();
        if (max > 0 && used > max * 9 / 10) {
            return "Sam, you are over 90% of your configured maxmemory (" + used + "/" + max
                    + " bytes). Eviction policy is " + ctx.server().config().maxMemoryPolicy().configName()
                    + ". Consider raising maxmemory or expiring more keys.";
        }
        return "Hi Sam, I can't find any memory problem in your instance. "
                + "Estimated used memory is " + used + " bytes across "
                + ctx.server().databaseCount() + " databases.";
    }
}
