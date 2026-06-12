package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.Glob;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic, type-agnostic key commands: existence and lifetime
 * ({@code DEL}/{@code EXISTS}/{@code TYPE}/{@code RENAME}/{@code COPY}/…),
 * iteration ({@code KEYS}/{@code RANDOMKEY}), introspection ({@code OBJECT}),
 * and database management ({@code SELECT}/{@code DBSIZE}/{@code FLUSHDB}/
 * {@code FLUSHALL}).
 *
 * <p>{@code SCAN} and {@code SWAPDB} are scheduled with the cursor family in a
 * later phase and are intentionally absent here.
 */
public final class GenericCommands {

    private GenericCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the generic key commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("del", -2, GenericCommands::del));
        registry.register(CommandSpec.of("unlink", -2, GenericCommands::del));
        registry.register(CommandSpec.of("exists", -2, GenericCommands::exists));
        registry.register(CommandSpec.of("type", 2, GenericCommands::type));
        registry.register(CommandSpec.of("keys", 2, GenericCommands::keys));
        registry.register(CommandSpec.of("dbsize", 1, GenericCommands::dbsize));
        registry.register(CommandSpec.of("flushdb", -1, GenericCommands::flushdb));
        registry.register(CommandSpec.of("flushall", -1, GenericCommands::flushall));
        registry.register(CommandSpec.of("rename", 3, GenericCommands::rename));
        registry.register(CommandSpec.of("renamenx", 3, GenericCommands::renamenx));
        registry.register(CommandSpec.of("randomkey", 1, GenericCommands::randomkey));
        registry.register(CommandSpec.of("touch", -2, GenericCommands::touch));
        registry.register(CommandSpec.of("copy", -3, GenericCommands::copy));
        registry.register(CommandSpec.of("select", 2, GenericCommands::select));
        registry.register(CommandSpec.of("object", -2, GenericCommands::object));
    }

    private static RespValue del(CommandContext ctx) {
        Database db = ctx.database();
        int removed = 0;
        for (int i = 1; i < ctx.argCount(); i++) {
            if (db.remove(new Bytes(ctx.arg(i)))) {
                removed++;
            }
        }
        return RespValue.integer(removed);
    }

    private static RespValue exists(CommandContext ctx) {
        Database db = ctx.database();
        int count = 0;
        for (int i = 1; i < ctx.argCount(); i++) {
            if (db.containsKey(new Bytes(ctx.arg(i)))) {
                count++; // counts multiplicity: EXISTS k k => 2 if k exists
            }
        }
        return RespValue.integer(count);
    }

    private static RespValue type(CommandContext ctx) {
        RedisValue v = ctx.database().peek(new Bytes(ctx.arg(1)));
        return RespValue.simple(v == null ? "none" : v.type().typeName());
    }

    private static RespValue keys(CommandContext ctx) {
        byte[] pattern = ctx.arg(1);
        Database db = ctx.database();
        List<RespValue> out = new ArrayList<>();
        for (Bytes key : db.liveKeys()) {
            if (Glob.match(pattern, key.array())) {
                out.add(RespValue.bulk(key.array()));
            }
        }
        return new RespValue.Array(out);
    }

    private static RespValue dbsize(CommandContext ctx) {
        return RespValue.integer(ctx.database().liveKeys().size());
    }

    private static RespValue flushdb(CommandContext ctx) {
        validateFlushMode(ctx);
        ctx.database().clear();
        return RespValue.OK;
    }

    private static RespValue flushall(CommandContext ctx) {
        validateFlushMode(ctx);
        for (int i = 0; i < ctx.server().databaseCount(); i++) {
            ctx.server().database(i).clear();
        }
        return RespValue.OK;
    }

    private static void validateFlushMode(CommandContext ctx) {
        if (ctx.argCount() == 1) {
            return;
        }
        if (ctx.argCount() == 2) {
            String mode = ctx.argUpper(1);
            if (mode.equals("ASYNC") || mode.equals("SYNC")) {
                return; // accepted; JediCore flushing is synchronous
            }
        }
        throw CommandException.syntax();
    }

    private static RespValue rename(CommandContext ctx) {
        Database db = ctx.database();
        Bytes src = new Bytes(ctx.arg(1));
        RedisValue value = db.lookup(src);
        if (value == null) {
            throw new CommandException("ERR no such key");
        }
        Bytes dst = new Bytes(ctx.arg(2));
        Long ttl = db.getExpireAt(src);
        db.remove(src);
        db.putKeepTtl(dst, value);
        if (ttl != null) {
            db.setExpireAt(dst, ttl);
        } else {
            db.persist(dst);
        }
        return RespValue.OK;
    }

    private static RespValue renamenx(CommandContext ctx) {
        Database db = ctx.database();
        Bytes src = new Bytes(ctx.arg(1));
        RedisValue value = db.lookup(src);
        if (value == null) {
            throw new CommandException("ERR no such key");
        }
        Bytes dst = new Bytes(ctx.arg(2));
        if (db.containsKey(dst)) {
            return RespValue.integer(0);
        }
        Long ttl = db.getExpireAt(src);
        db.remove(src);
        db.putKeepTtl(dst, value);
        if (ttl != null) {
            db.setExpireAt(dst, ttl);
        }
        return RespValue.integer(1);
    }

    private static RespValue randomkey(CommandContext ctx) {
        Bytes key = ctx.database().randomKey();
        return key == null ? RespValue.NULL : RespValue.bulk(key.array());
    }

    private static RespValue touch(CommandContext ctx) {
        Database db = ctx.database();
        int count = 0;
        for (int i = 1; i < ctx.argCount(); i++) {
            if (db.lookup(new Bytes(ctx.arg(i))) != null) {
                count++;
            }
        }
        return RespValue.integer(count);
    }

    private static RespValue copy(CommandContext ctx) {
        Bytes src = new Bytes(ctx.arg(1));
        Bytes dst = new Bytes(ctx.arg(2));
        Database srcDb = ctx.database();
        Database dstDb = srcDb;
        boolean replace = false;

        for (int i = 3; i < ctx.argCount(); ) {
            String opt = ctx.argUpper(i);
            switch (opt) {
                case "REPLACE" -> {
                    replace = true;
                    i++;
                }
                case "DB" -> {
                    if (i + 1 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    long index = Keyspaces.parseLong(ctx.arg(i + 1));
                    if (index < 0 || index >= ctx.server().databaseCount()) {
                        throw new CommandException("ERR DB index is out of range");
                    }
                    dstDb = ctx.server().database((int) index);
                    i += 2;
                }
                default -> throw CommandException.syntax();
            }
        }

        if (srcDb == dstDb && src.equals(dst)) {
            throw new CommandException("ERR source and destination objects are the same");
        }
        RedisValue value = srcDb.lookup(src);
        if (value == null) {
            return RespValue.integer(0);
        }
        if (dstDb.containsKey(dst) && !replace) {
            return RespValue.integer(0);
        }
        dstDb.put(dst, value.deepCopy());
        Long ttl = srcDb.getExpireAt(src);
        if (ttl != null) {
            dstDb.setExpireAt(dst, ttl);
        }
        return RespValue.integer(1);
    }

    private static RespValue select(CommandContext ctx) {
        long index = Keyspaces.parseLong(ctx.arg(1));
        if (index < 0 || index >= ctx.server().databaseCount()) {
            throw new CommandException("ERR DB index is out of range");
        }
        ctx.connection().selectDb((int) index);
        return RespValue.OK;
    }

    private static RespValue object(CommandContext ctx) {
        String sub = ctx.argUpper(1);
        if (sub.equals("HELP")) {
            return new RespValue.Array(List.of(RespValue.simple(
                    "OBJECT <ENCODING|REFCOUNT|IDLETIME> <key>")));
        }
        if (ctx.argCount() != 3) {
            throw new CommandException(
                    "ERR Unknown subcommand or wrong number of arguments for '" + ctx.argText(1)
                            + "'. Try OBJECT HELP.");
        }
        RedisValue value = ctx.database().peek(new Bytes(ctx.arg(2)));
        if (value == null) {
            throw new CommandException("ERR no such key");
        }
        return switch (sub) {
            case "ENCODING" -> RespValue.bulk(value.encoding());
            // We do not share objects, so refcount is always 1.
            case "REFCOUNT" -> RespValue.integer(1);
            case "IDLETIME" -> RespValue.integer(
                    Math.max(0, (System.currentTimeMillis() - value.lastAccessMillis()) / 1000));
            default -> throw new CommandException(
                    "ERR Unknown subcommand or wrong number of arguments for '" + ctx.argText(1)
                            + "'. Try OBJECT HELP.");
        };
    }
}
