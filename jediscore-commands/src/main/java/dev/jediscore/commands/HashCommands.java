package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.HashValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The hash command family. Hashes use a listpack encoding while small and
 * convert to a hashtable past the configured thresholds; this is transparent to
 * these commands, which operate through {@link HashValue}.
 *
 * <p>{@code HSCAN} is implemented alongside the rest of the {@code SCAN} cursor
 * family in a later phase and is intentionally absent here.
 */
public final class HashCommands {

    private HashCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the hash commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("hset", -4, HashCommands::hset));
        registry.register(CommandSpec.of("hsetnx", 4, HashCommands::hsetnx));
        registry.register(CommandSpec.of("hmset", -4, HashCommands::hmset));
        registry.register(CommandSpec.of("hget", 3, HashCommands::hget));
        registry.register(CommandSpec.of("hmget", -3, HashCommands::hmget));
        registry.register(CommandSpec.of("hgetall", 2, HashCommands::hgetall));
        registry.register(CommandSpec.of("hdel", -3, HashCommands::hdel));
        registry.register(CommandSpec.of("hexists", 3, HashCommands::hexists));
        registry.register(CommandSpec.of("hkeys", 2, HashCommands::hkeys));
        registry.register(CommandSpec.of("hvals", 2, HashCommands::hvals));
        registry.register(CommandSpec.of("hlen", 2, HashCommands::hlen));
        registry.register(CommandSpec.of("hstrlen", 3, HashCommands::hstrlen));
        registry.register(CommandSpec.of("hincrby", 4, HashCommands::hincrby));
        registry.register(CommandSpec.of("hincrbyfloat", 4, HashCommands::hincrbyfloat));
        registry.register(CommandSpec.of("hrandfield", -2, HashCommands::hrandfield));
    }

    /** Fetches the hash at a key, or {@code null} if absent (WRONGTYPE if other type). */
    private static HashValue lookup(CommandContext ctx, Bytes key) {
        return Keyspaces.asHash(ctx.database().lookup(key));
    }

    private static RespValue hset(CommandContext ctx) {
        if (ctx.argCount() % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for 'hset' command");
        }
        int created = doSet(ctx);
        return RespValue.integer(created);
    }

    private static RespValue hmset(CommandContext ctx) {
        if (ctx.argCount() % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for 'hmset' command");
        }
        doSet(ctx);
        return RespValue.OK;
    }

    private static int doSet(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        HashValue hash = lookup(ctx, key);
        boolean fresh = hash == null;
        if (fresh) {
            hash = Keyspaces.newHash(ctx);
        }
        int created = 0;
        for (int i = 2; i < ctx.argCount(); i += 2) {
            if (hash.put(ctx.arg(i), ctx.arg(i + 1))) {
                created++;
            }
        }
        if (fresh) {
            db.put(key, hash);
        }
        return created;
    }

    private static RespValue hsetnx(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        HashValue hash = lookup(ctx, key);
        if (hash != null && hash.contains(ctx.arg(2))) {
            return RespValue.integer(0);
        }
        if (hash == null) {
            hash = Keyspaces.newHash(ctx);
            db.put(key, hash);
        }
        hash.put(ctx.arg(2), ctx.arg(3));
        return RespValue.integer(1);
    }

    private static RespValue hget(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        if (hash == null) {
            return RespValue.NULL;
        }
        byte[] value = hash.get(ctx.arg(2));
        return value == null ? RespValue.NULL : RespValue.bulk(value);
    }

    private static RespValue hmget(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        List<RespValue> out = new ArrayList<>(ctx.argCount() - 2);
        for (int i = 2; i < ctx.argCount(); i++) {
            byte[] value = (hash == null) ? null : hash.get(ctx.arg(i));
            out.add(value == null ? RespValue.NULL : RespValue.bulk(value));
        }
        return new RespValue.Array(out);
    }

    private static RespValue hgetall(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        if (hash == null) {
            return new RespValue.Map(List.of());
        }
        List<byte[]> flat = hash.entriesFlattened();
        List<RespValue.MapEntry> entries = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i < flat.size(); i += 2) {
            entries.add(new RespValue.MapEntry(RespValue.bulk(flat.get(i)), RespValue.bulk(flat.get(i + 1))));
        }
        return new RespValue.Map(entries);
    }

    private static RespValue hdel(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        HashValue hash = lookup(ctx, key);
        if (hash == null) {
            return RespValue.integer(0);
        }
        int removed = 0;
        for (int i = 2; i < ctx.argCount(); i++) {
            if (hash.remove(ctx.arg(i))) {
                removed++;
            }
        }
        if (hash.size() == 0) {
            db.remove(key);
        }
        return RespValue.integer(removed);
    }

    private static RespValue hexists(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        return RespValue.integer(hash != null && hash.contains(ctx.arg(2)) ? 1 : 0);
    }

    private static RespValue hkeys(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        return toArray(hash == null ? List.of() : hash.fields());
    }

    private static RespValue hvals(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        return toArray(hash == null ? List.of() : hash.values());
    }

    private static RespValue hlen(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        return RespValue.integer(hash == null ? 0 : hash.size());
    }

    private static RespValue hstrlen(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));
        return RespValue.integer(hash == null ? 0 : hash.valueLength(ctx.arg(2)));
    }

    private static RespValue hincrby(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        byte[] field = ctx.arg(2);
        // Validate the increment BEFORE touching the keyspace, so a bad argument
        // never leaves a spurious empty hash behind (matching Redis).
        long increment = Keyspaces.parseLong(ctx.arg(3));
        Database db = ctx.database();
        HashValue hash = lookup(ctx, key);
        if (hash == null) {
            hash = Keyspaces.newHash(ctx);
            db.put(key, hash);
        }
        byte[] cur = hash.get(field);
        long current = (cur == null) ? 0 : parseHashLong(cur);
        if ((increment > 0 && current > Long.MAX_VALUE - increment)
                || (increment < 0 && current < Long.MIN_VALUE - increment)) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        long result = current + increment;
        hash.put(field, Long.toString(result).getBytes(StandardCharsets.US_ASCII));
        return RespValue.integer(result);
    }

    private static RespValue hincrbyfloat(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        byte[] field = ctx.arg(2);
        // Validate the increment before touching the keyspace (see hincrby).
        double increment = Keyspaces.parseFiniteDouble(ctx.arg(3));
        Database db = ctx.database();
        HashValue hash = lookup(ctx, key);
        byte[] cur = (hash == null) ? null : hash.get(field);
        double current = (cur == null) ? 0 : parseHashDouble(cur);
        double result = current + increment;
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new CommandException("ERR increment would produce NaN or Infinity");
        }
        // Only now (after all validation) create the key if it was absent.
        if (hash == null) {
            hash = Keyspaces.newHash(ctx);
            db.put(key, hash);
        }
        String formatted = Keyspaces.formatDouble(result);
        hash.put(field, formatted.getBytes(StandardCharsets.US_ASCII));
        return RespValue.bulk(formatted);
    }

    private static RespValue hrandfield(CommandContext ctx) {
        HashValue hash = lookup(ctx, new Bytes(ctx.arg(1)));

        if (ctx.argCount() == 2) {
            // No count: a single random field, or nil.
            if (hash == null || hash.size() == 0) {
                return RespValue.NULL;
            }
            List<byte[]> fields = hash.fields();
            return RespValue.bulk(fields.get(ThreadLocalRandom.current().nextInt(fields.size())));
        }

        long count = Keyspaces.parseLong(ctx.arg(2));
        boolean withValues = false;
        if (ctx.argCount() == 4) {
            if (!ctx.argUpper(3).equals("WITHVALUES")) {
                throw CommandException.syntax();
            }
            withValues = true;
        } else if (ctx.argCount() > 4) {
            throw CommandException.syntax();
        }

        if (hash == null || hash.size() == 0) {
            return new RespValue.Array(List.of());
        }
        List<byte[]> flat = hash.entriesFlattened();
        int n = flat.size() / 2;

        List<Integer> picks = new ArrayList<>();
        if (count >= 0) {
            // Distinct fields, capped at the hash size.
            int want = (int) Math.min(count, n);
            List<Integer> indices = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                indices.add(i);
            }
            Collections.shuffle(indices, ThreadLocalRandom.current());
            picks = indices.subList(0, want);
        } else {
            // |count| fields, with repetition allowed.
            int want = (int) Math.min(-count, Integer.MAX_VALUE);
            for (int i = 0; i < want; i++) {
                picks.add(ThreadLocalRandom.current().nextInt(n));
            }
        }

        List<RespValue> out = new ArrayList<>();
        for (int idx : picks) {
            out.add(RespValue.bulk(flat.get(idx * 2)));
            if (withValues) {
                out.add(RespValue.bulk(flat.get(idx * 2 + 1)));
            }
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

    private static long parseHashLong(byte[] bytes) {
        if (!dev.jediscore.datastructures.StringValue.isCanonicalLong(bytes)) {
            throw new CommandException("ERR hash value is not an integer");
        }
        return Long.parseLong(new String(bytes, StandardCharsets.US_ASCII));
    }

    private static double parseHashDouble(byte[] bytes) {
        try {
            return Keyspaces.parseFiniteDouble(bytes);
        } catch (CommandException e) {
            throw new CommandException("ERR hash value is not a float");
        }
    }
}
