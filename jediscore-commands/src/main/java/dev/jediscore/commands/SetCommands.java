package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.SetValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The set command family: membership and mutation, random access
 * ({@code SPOP}/{@code SRANDMEMBER}), and set algebra
 * ({@code SUNION}/{@code SINTER}/{@code SDIFF} and their {@code STORE} and
 * {@code CARD} variants). Sets use the intset/listpack/hashtable encodings.
 *
 * <p>{@code SSCAN} ships with the SCAN cursor family in a later phase.
 */
public final class SetCommands {

    private SetCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the set commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("sadd", -3, SetCommands::sadd));
        registry.register(CommandSpec.of("srem", -3, SetCommands::srem));
        registry.register(CommandSpec.of("smembers", 2, SetCommands::smembers));
        registry.register(CommandSpec.of("sismember", 3, SetCommands::sismember));
        registry.register(CommandSpec.of("smismember", -3, SetCommands::smismember));
        registry.register(CommandSpec.of("scard", 2, SetCommands::scard));
        registry.register(CommandSpec.of("spop", -2, SetCommands::spop));
        registry.register(CommandSpec.of("srandmember", -2, SetCommands::srandmember));
        registry.register(CommandSpec.of("sunion", -2, ctx -> algebra(ctx, Op.UNION, 1)));
        registry.register(CommandSpec.of("sinter", -2, ctx -> algebra(ctx, Op.INTER, 1)));
        registry.register(CommandSpec.of("sdiff", -2, ctx -> algebra(ctx, Op.DIFF, 1)));
        registry.register(CommandSpec.of("sunionstore", -3, ctx -> store(ctx, Op.UNION)));
        registry.register(CommandSpec.of("sinterstore", -3, ctx -> store(ctx, Op.INTER)));
        registry.register(CommandSpec.of("sdiffstore", -3, ctx -> store(ctx, Op.DIFF)));
        registry.register(CommandSpec.of("sintercard", -3, SetCommands::sintercard));
        registry.register(CommandSpec.of("smove", 4, SetCommands::smove));
    }

    private enum Op { UNION, INTER, DIFF }

    private static RespValue sadd(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        SetValue set = Keyspaces.asSet(db.lookup(key));
        boolean fresh = set == null;
        if (fresh) {
            set = Keyspaces.newSet(ctx);
        }
        int added = 0;
        for (int i = 2; i < ctx.argCount(); i++) {
            if (set.add(ctx.arg(i))) {
                added++;
            }
        }
        if (fresh) {
            db.put(key, set);
        }
        return RespValue.integer(added);
    }

    private static RespValue srem(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        SetValue set = Keyspaces.asSet(db.lookup(key));
        if (set == null) {
            return RespValue.integer(0);
        }
        int removed = 0;
        for (int i = 2; i < ctx.argCount(); i++) {
            if (set.remove(ctx.arg(i))) {
                removed++;
            }
        }
        if (set.size() == 0) {
            db.remove(key);
        }
        return RespValue.integer(removed);
    }

    private static RespValue smembers(CommandContext ctx) {
        SetValue set = Keyspaces.asSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        return toArray(set == null ? List.of() : set.members());
    }

    private static RespValue sismember(CommandContext ctx) {
        SetValue set = Keyspaces.asSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        return RespValue.integer(set != null && set.contains(ctx.arg(2)) ? 1 : 0);
    }

    private static RespValue smismember(CommandContext ctx) {
        SetValue set = Keyspaces.asSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        List<RespValue> out = new ArrayList<>(ctx.argCount() - 2);
        for (int i = 2; i < ctx.argCount(); i++) {
            out.add(RespValue.integer(set != null && set.contains(ctx.arg(i)) ? 1 : 0));
        }
        return new RespValue.Array(out);
    }

    private static RespValue scard(CommandContext ctx) {
        SetValue set = Keyspaces.asSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        return RespValue.integer(set == null ? 0 : set.size());
    }

    private static RespValue spop(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        SetValue set = Keyspaces.asSet(db.lookup(key));

        if (ctx.argCount() == 2) {
            if (set == null) {
                ctx.suppressPropagation();
                return RespValue.NULL;
            }
            byte[] member = set.popRandom();
            boolean emptied = set.size() == 0;
            if (emptied) {
                db.remove(key);
            }
            // SPOP is non-deterministic; propagate the concrete removal so replicas
            // and the AOF stay consistent with this master.
            propagateRemoval(ctx, key, emptied, List.of(member));
            return RespValue.bulk(member);
        }
        if (ctx.argCount() > 3) {
            throw new CommandException("ERR wrong number of arguments for 'spop' command");
        }
        long count = Keyspaces.parseLong(ctx.arg(2));
        if (count < 0) {
            throw new CommandException("ERR value is out of range, must be positive");
        }
        if (set == null) {
            ctx.suppressPropagation();
            return new RespValue.Array(List.of());
        }
        List<RespValue> out = new ArrayList<>();
        List<byte[]> popped = new ArrayList<>();
        for (long i = 0; i < count && set.size() > 0; i++) {
            byte[] member = set.popRandom();
            popped.add(member);
            out.add(RespValue.bulk(member));
        }
        boolean emptied = set.size() == 0;
        if (emptied) {
            db.remove(key);
        }
        if (popped.isEmpty()) {
            ctx.suppressPropagation();
        } else {
            propagateRemoval(ctx, key, emptied, popped);
        }
        return new RespValue.Array(out);
    }

    /** Propagates an SPOP's effect as a deterministic {@code SREM} (or {@code DEL} if emptied). */
    private static void propagateRemoval(CommandContext ctx, Bytes key, boolean emptied, List<byte[]> members) {
        if (emptied) {
            ctx.propagate(new byte[][]{"DEL".getBytes(StandardCharsets.UTF_8), key.array()});
            return;
        }
        byte[][] srem = new byte[2 + members.size()][];
        srem[0] = "SREM".getBytes(StandardCharsets.UTF_8);
        srem[1] = key.array();
        for (int i = 0; i < members.size(); i++) {
            srem[2 + i] = members.get(i);
        }
        ctx.propagate(srem);
    }

    private static RespValue srandmember(CommandContext ctx) {
        SetValue set = Keyspaces.asSet(ctx.database().lookup(new Bytes(ctx.arg(1))));

        if (ctx.argCount() == 2) {
            if (set == null) {
                return RespValue.NULL;
            }
            return RespValue.bulk(set.randomMember());
        }
        if (ctx.argCount() > 3) {
            throw new CommandException("ERR wrong number of arguments for 'srandmember' command");
        }
        long count = Keyspaces.parseLong(ctx.arg(2));
        if (set == null) {
            return new RespValue.Array(List.of());
        }
        List<byte[]> members = set.members();
        List<RespValue> out = new ArrayList<>();
        if (count >= 0) {
            // Distinct, capped at the set size.
            List<Integer> indices = new ArrayList<>(members.size());
            for (int i = 0; i < members.size(); i++) {
                indices.add(i);
            }
            Collections.shuffle(indices, ThreadLocalRandom.current());
            int want = (int) Math.min(count, members.size());
            for (int i = 0; i < want; i++) {
                out.add(RespValue.bulk(members.get(indices.get(i))));
            }
        } else {
            // |count| members, repetition allowed.
            long want = -count;
            for (long i = 0; i < want && !members.isEmpty(); i++) {
                out.add(RespValue.bulk(members.get(ThreadLocalRandom.current().nextInt(members.size()))));
            }
        }
        return new RespValue.Array(out);
    }

    private static RespValue smove(CommandContext ctx) {
        Bytes source = new Bytes(ctx.arg(1));
        Bytes dest = new Bytes(ctx.arg(2));
        byte[] member = ctx.arg(3);
        Database db = ctx.database();

        // Redis order: a missing source returns 0 BEFORE the destination's type is
        // checked; only once the source exists are both types validated.
        dev.jediscore.datastructures.RedisValue srcRaw = db.lookup(source);
        dev.jediscore.datastructures.RedisValue dstRaw = db.lookup(dest);
        if (srcRaw == null) {
            return RespValue.integer(0);
        }
        SetValue src = Keyspaces.asSet(srcRaw);
        SetValue dst = Keyspaces.asSet(dstRaw);
        if (!src.contains(member)) {
            return RespValue.integer(0);
        }
        if (source.equals(dest)) {
            return RespValue.integer(1); // present in source == dest: no-op move
        }
        src.remove(member);
        if (src.size() == 0) {
            db.remove(source);
        }
        boolean fresh = dst == null;
        if (fresh) {
            dst = Keyspaces.newSet(ctx);
        }
        dst.add(member);
        if (fresh) {
            db.put(dest, dst);
        }
        return RespValue.integer(1);
    }

    // ---- set algebra --------------------------------------------------------

    private static RespValue algebra(CommandContext ctx, Op op, int firstKey) {
        LinkedHashSet<Bytes> result = compute(ctx, op, firstKey, ctx.argCount());
        return membersToArray(result);
    }

    private static RespValue store(CommandContext ctx, Op op) {
        Bytes dest = new Bytes(ctx.arg(1));
        LinkedHashSet<Bytes> result = compute(ctx, op, 2, ctx.argCount());
        Database db = ctx.database();
        if (result.isEmpty()) {
            db.remove(dest);
            return RespValue.integer(0);
        }
        SetValue set = Keyspaces.newSet(ctx);
        for (Bytes member : result) {
            set.add(member.array());
        }
        db.put(dest, set);
        return RespValue.integer(set.size());
    }

    private static RespValue sintercard(CommandContext ctx) {
        long numKeys = Keyspaces.parseLong(ctx.arg(1));
        if (numKeys <= 0) {
            throw new CommandException("ERR numkeys should be greater than 0");
        }
        int firstKey = 2;
        int lastKey = (int) (firstKey + numKeys);
        if (lastKey > ctx.argCount()) {
            throw new CommandException("ERR Number of keys can't be greater than number of args");
        }
        long limit = 0;
        if (lastKey < ctx.argCount()) {
            if (!ctx.argUpper(lastKey).equals("LIMIT") || lastKey + 1 >= ctx.argCount()) {
                throw CommandException.syntax();
            }
            limit = Keyspaces.parseLong(ctx.arg(lastKey + 1));
            if (limit < 0) {
                throw new CommandException("ERR LIMIT can't be negative");
            }
        }
        LinkedHashSet<Bytes> result = compute(ctx, Op.INTER, firstKey, lastKey);
        long card = result.size();
        if (limit > 0) {
            card = Math.min(card, limit);
        }
        return RespValue.integer(card);
    }

    /** Computes the union/intersection/difference over keys in {@code [firstKey, lastKey)}. */
    private static LinkedHashSet<Bytes> compute(CommandContext ctx, Op op, int firstKey, int lastKey) {
        LinkedHashSet<Bytes> result = readSet(ctx, ctx.arg(firstKey));
        for (int i = firstKey + 1; i < lastKey; i++) {
            LinkedHashSet<Bytes> other = readSet(ctx, ctx.arg(i));
            switch (op) {
                case UNION -> result.addAll(other);
                case INTER -> {
                    result.retainAll(other);
                    if (result.isEmpty()) {
                        return result;
                    }
                }
                case DIFF -> result.removeAll(other);
            }
        }
        return result;
    }

    private static LinkedHashSet<Bytes> readSet(CommandContext ctx, byte[] keyArg) {
        SetValue set = Keyspaces.asSet(ctx.database().lookup(new Bytes(keyArg)));
        LinkedHashSet<Bytes> out = new LinkedHashSet<>();
        if (set != null) {
            for (byte[] m : set.members()) {
                out.add(new Bytes(m));
            }
        }
        return out;
    }

    private static RespValue membersToArray(LinkedHashSet<Bytes> members) {
        List<RespValue> out = new ArrayList<>(members.size());
        for (Bytes b : members) {
            out.add(RespValue.bulk(b.array()));
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
