package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.ListValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;

/**
 * The list command family, with full Redis semantics for pushes/pops (including
 * {@code LPOP}/{@code RPOP} counts), ranges, in-place edits, {@code LINSERT},
 * {@code LREM}, {@code LTRIM}, the move commands, and {@code LPOS}.
 *
 * <p>An empty list is deleted, matching Redis (a key never holds an empty list).
 */
public final class ListCommands {

    private ListCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the list commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("lpush", -3, ctx -> push(ctx, true, false)));
        registry.register(CommandSpec.of("rpush", -3, ctx -> push(ctx, false, false)));
        registry.register(CommandSpec.of("lpushx", -3, ctx -> push(ctx, true, true)));
        registry.register(CommandSpec.of("rpushx", -3, ctx -> push(ctx, false, true)));
        registry.register(CommandSpec.of("lpop", -2, ctx -> pop(ctx, true)));
        registry.register(CommandSpec.of("rpop", -2, ctx -> pop(ctx, false)));
        registry.register(CommandSpec.of("lrange", 4, ListCommands::lrange));
        registry.register(CommandSpec.of("llen", 2, ListCommands::llen));
        registry.register(CommandSpec.of("lindex", 3, ListCommands::lindex));
        registry.register(CommandSpec.of("lset", 4, ListCommands::lset));
        registry.register(CommandSpec.of("linsert", 5, ListCommands::linsert));
        registry.register(CommandSpec.of("lrem", 4, ListCommands::lrem));
        registry.register(CommandSpec.of("ltrim", 4, ListCommands::ltrim));
        registry.register(CommandSpec.of("rpoplpush", 3, ListCommands::rpoplpush));
        registry.register(CommandSpec.of("lmove", 5, ListCommands::lmove));
        registry.register(CommandSpec.of("lpos", -3, ListCommands::lpos));
    }

    private static RespValue push(CommandContext ctx, boolean head, boolean existingOnly) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        ListValue list = Keyspaces.asList(db.lookup(key));
        if (list == null) {
            if (existingOnly) {
                return RespValue.integer(0);
            }
            list = Keyspaces.newList(ctx);
            db.put(key, list);
        }
        for (int i = 2; i < ctx.argCount(); i++) {
            if (head) {
                list.pushHead(ctx.arg(i));
            } else {
                list.pushTail(ctx.arg(i));
            }
        }
        return RespValue.integer(list.size());
    }

    private static RespValue pop(CommandContext ctx, boolean head) {
        if (ctx.argCount() > 3) {
            throw new CommandException(
                    "ERR wrong number of arguments for '" + (head ? "lpop" : "rpop") + "' command");
        }
        Bytes key = new Bytes(ctx.arg(1));
        // When a count is given, Redis parses it before looking the key up.
        Long count = null;
        if (ctx.argCount() == 3) {
            count = Keyspaces.parseLong(ctx.arg(2));
            if (count < 0) {
                throw new CommandException("ERR value is out of range, must be positive");
            }
        }
        Database db = ctx.database();
        ListValue list = Keyspaces.asList(db.lookup(key));

        if (count == null) {
            if (list == null) {
                return RespValue.NULL;
            }
            byte[] e = head ? list.popHead() : list.popTail();
            if (list.isEmpty()) {
                db.remove(key);
            }
            return RespValue.bulk(e);
        }

        if (list == null) {
            return RespValue.NULL;
        }
        List<RespValue> out = new ArrayList<>();
        for (long i = 0; i < count && !list.isEmpty(); i++) {
            out.add(RespValue.bulk(head ? list.popHead() : list.popTail()));
        }
        if (list.isEmpty()) {
            db.remove(key);
        }
        return new RespValue.Array(out);
    }

    private static RespValue lrange(CommandContext ctx) {
        // Redis parses the indices before looking the key up.
        long start = Keyspaces.parseLong(ctx.arg(2));
        long stop = Keyspaces.parseLong(ctx.arg(3));
        ListValue list = Keyspaces.asList(ctx.database().lookup(new Bytes(ctx.arg(1))));
        if (list == null) {
            return new RespValue.Array(List.of());
        }
        return toArray(list.range(start, stop));
    }

    private static RespValue llen(CommandContext ctx) {
        ListValue list = Keyspaces.asList(ctx.database().lookup(new Bytes(ctx.arg(1))));
        return RespValue.integer(list == null ? 0 : list.size());
    }

    private static RespValue lindex(CommandContext ctx) {
        ListValue list = Keyspaces.asList(ctx.database().lookup(new Bytes(ctx.arg(1))));
        if (list == null) {
            return RespValue.NULL;
        }
        byte[] e = list.index(Keyspaces.parseLong(ctx.arg(2)));
        return e == null ? RespValue.NULL : RespValue.bulk(e);
    }

    private static RespValue lset(CommandContext ctx) {
        ListValue list = Keyspaces.asList(ctx.database().lookup(new Bytes(ctx.arg(1))));
        if (list == null) {
            throw new CommandException("ERR no such key");
        }
        if (!list.set(Keyspaces.parseLong(ctx.arg(2)), ctx.arg(3))) {
            throw new CommandException("ERR index out of range");
        }
        return RespValue.OK;
    }

    private static RespValue linsert(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        // Redis validates the BEFORE/AFTER token before looking the key up.
        boolean before;
        String where = ctx.argUpper(2);
        if (where.equals("BEFORE")) {
            before = true;
        } else if (where.equals("AFTER")) {
            before = false;
        } else {
            throw CommandException.syntax();
        }
        ListValue list = Keyspaces.asList(ctx.database().lookup(key));
        if (list == null) {
            return RespValue.integer(0);
        }
        return RespValue.integer(list.insert(before, ctx.arg(3), ctx.arg(4)));
    }

    private static RespValue lrem(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        // Redis parses the count argument before looking the key up.
        long count = Keyspaces.parseLong(ctx.arg(2));
        Database db = ctx.database();
        ListValue list = Keyspaces.asList(db.lookup(key));
        if (list == null) {
            return RespValue.integer(0);
        }
        int removed = list.remove(count, ctx.arg(3));
        if (list.isEmpty()) {
            db.remove(key);
        }
        return RespValue.integer(removed);
    }

    private static RespValue ltrim(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        long start = Keyspaces.parseLong(ctx.arg(2));
        long stop = Keyspaces.parseLong(ctx.arg(3));
        Database db = ctx.database();
        ListValue list = Keyspaces.asList(db.lookup(key));
        if (list == null) {
            return RespValue.OK;
        }
        list.trim(start, stop);
        if (list.isEmpty()) {
            db.remove(key);
        }
        return RespValue.OK;
    }

    private static RespValue rpoplpush(CommandContext ctx) {
        return moveElement(ctx, new Bytes(ctx.arg(1)), new Bytes(ctx.arg(2)), false, true);
    }

    private static RespValue lmove(CommandContext ctx) {
        boolean fromHead = side(ctx.argUpper(3));
        boolean toHead = side(ctx.argUpper(4));
        return moveElement(ctx, new Bytes(ctx.arg(1)), new Bytes(ctx.arg(2)), fromHead, toHead);
    }

    private static boolean side(String token) {
        return switch (token) {
            case "LEFT" -> true;
            case "RIGHT" -> false;
            default -> throw CommandException.syntax();
        };
    }

    private static RespValue moveElement(CommandContext ctx, Bytes source, Bytes dest,
                                         boolean fromHead, boolean toHead) {
        Database db = ctx.database();
        // Resolve and type-check both keys before any mutation.
        ListValue src = Keyspaces.asList(db.lookup(source));
        ListValue dst = Keyspaces.asList(db.lookup(dest));
        if (src == null || src.isEmpty()) {
            return RespValue.NULL;
        }
        byte[] element = fromHead ? src.popHead() : src.popTail();
        boolean fresh = dst == null;
        if (fresh) {
            dst = Keyspaces.newList(ctx);
        }
        if (toHead) {
            dst.pushHead(element);
        } else {
            dst.pushTail(element);
        }
        if (fresh) {
            db.put(dest, dst);
        }
        if (src.isEmpty() && !source.equals(dest)) {
            db.remove(source);
        }
        return RespValue.bulk(element);
    }

    private static RespValue lpos(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        byte[] element = ctx.arg(2);
        long rank = 1;
        Long count = null;
        long maxLen = 0;
        for (int i = 3; i < ctx.argCount(); ) {
            String opt = ctx.argUpper(i);
            if (i + 1 >= ctx.argCount()) {
                throw CommandException.syntax();
            }
            long value = Keyspaces.parseLong(ctx.arg(i + 1));
            switch (opt) {
                case "RANK" -> {
                    if (value == 0) {
                        throw new CommandException(
                                "ERR RANK can't be zero: use 1 to start searching from the first match. "
                                        + "Negative to search backward.");
                    }
                    rank = value;
                }
                case "COUNT" -> {
                    if (value < 0) {
                        throw new CommandException("ERR COUNT can't be negative");
                    }
                    count = value;
                }
                case "MAXLEN" -> {
                    if (value < 0) {
                        throw new CommandException("ERR MAXLEN can't be negative");
                    }
                    maxLen = value;
                }
                default -> throw CommandException.syntax();
            }
            i += 2;
        }

        ListValue list = Keyspaces.asList(ctx.database().lookup(key));
        if (list == null) {
            return count == null ? RespValue.NULL : new RespValue.Array(List.of());
        }
        long maxMatches = (count == null) ? 1 : (count == 0 ? Integer.MAX_VALUE : count);
        List<Long> found = list.positions(element, rank, maxMatches, maxLen);
        if (count == null) {
            return found.isEmpty() ? RespValue.NULL : RespValue.integer(found.get(0));
        }
        List<RespValue> out = new ArrayList<>(found.size());
        for (long idx : found) {
            out.add(RespValue.integer(idx));
        }
        return new RespValue.Array(out);
    }

    private static RespValue toArray(List<byte[]> items) {
        List<RespValue> out = new ArrayList<>(items.size());
        for (byte[] item : items) {
            out.add(RespValue.bulk(item));
        }
        return new RespValue.Array(out);
    }
}
