package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Persistence;
import dev.jediscore.protocol.RespValue;

/**
 * Persistence control commands: {@code SAVE}, {@code BGSAVE}, {@code LASTSAVE},
 * and {@code DEBUG RELOAD}. They delegate to the engine's {@link Persistence}
 * service, which the server wires in at startup.
 */
public final class PersistenceCommands {

    private PersistenceCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the persistence commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("save", 1, PersistenceCommands::save));
        registry.register(CommandSpec.of("bgsave", -1, PersistenceCommands::bgsave));
        registry.register(CommandSpec.of("bgrewriteaof", 1, PersistenceCommands::bgrewriteaof));
        registry.register(CommandSpec.of("lastsave", 1, PersistenceCommands::lastsave));
        registry.register(CommandSpec.of("debug", -2, PersistenceCommands::debug));
    }

    private static Persistence persistence(CommandContext ctx) {
        Persistence p = ctx.server().persistence();
        if (p == null) {
            throw new CommandException("ERR persistence is not available");
        }
        return p;
    }

    private static RespValue save(CommandContext ctx) {
        try {
            persistence(ctx).save();
            return RespValue.OK;
        } catch (RuntimeException e) {
            throw new CommandException("ERR " + e.getMessage());
        }
    }

    private static RespValue bgsave(CommandContext ctx) {
        boolean started = persistence(ctx).backgroundSave();
        if (!started) {
            throw new CommandException("ERR Background save already in progress");
        }
        return RespValue.simple("Background saving started");
    }

    private static RespValue bgrewriteaof(CommandContext ctx) {
        Persistence p = persistence(ctx);
        if (!p.appendOnlyEnabled()) {
            // Redis still "schedules" a rewrite even when AOF is off; we report
            // honestly that there is nothing to rewrite.
            throw new CommandException("ERR Background append only file rewriting requires AOF to be enabled");
        }
        if (!p.rewriteAppendOnly()) {
            throw new CommandException("ERR Background append only file rewriting already in progress");
        }
        return RespValue.simple("Background append only file rewriting started");
    }

    private static RespValue lastsave(CommandContext ctx) {
        return RespValue.integer(persistence(ctx).lastSaveSeconds());
    }

    private static RespValue debug(CommandContext ctx) {
        String sub = ctx.argUpper(1);
        switch (sub) {
            case "RELOAD" -> {
                persistence(ctx).reload();
                return RespValue.OK;
            }
            case "SLEEP" -> {
                if (ctx.argCount() < 3) {
                    throw new CommandException("ERR wrong number of arguments for 'debug|sleep'");
                }
                double seconds = Keyspaces.parseDouble(ctx.arg(2));
                try {
                    // Blocks the command thread, exactly like Redis blocks the server.
                    Thread.sleep((long) (seconds * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return RespValue.OK;
            }
            case "OBJECT" -> {
                if (ctx.argCount() < 3) {
                    throw new CommandException("ERR wrong number of arguments for 'debug|object'");
                }
                RedisValue value = ctx.database().lookup(new Bytes(ctx.arg(2)));
                if (value == null) {
                    throw new CommandException("ERR no such key");
                }
                return RespValue.simple("Value at:0x0 refcount:1 encoding:" + value.encoding()
                        + " serializedlength:" + value.estimateBytes()
                        + " lru:0 lru_seconds_idle:0");
            }
            case "SET-ACTIVE-EXPIRE" -> {
                if (ctx.argCount() >= 3) {
                    ctx.server().setActiveExpiryEnabled(!"0".equals(ctx.argText(2)));
                }
                return RespValue.OK;
            }
            case "QUICKLIST-PACKED-THRESHOLD", "STRINGMATCH-LEN", "JMAP" -> {
                return RespValue.OK; // accepted no-ops used by test suites
            }
            default -> throw new CommandException(
                    "ERR DEBUG subcommand not supported: " + ctx.argText(1));
        }
    }
}
