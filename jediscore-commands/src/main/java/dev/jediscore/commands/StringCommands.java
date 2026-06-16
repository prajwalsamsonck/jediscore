package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.datastructures.StringValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The string command family: {@code SET}/{@code GET} and friends, with full
 * Redis semantics including {@code SET}'s {@code EX}/{@code PX}/{@code EXAT}/
 * {@code PXAT}/{@code NX}/{@code XX}/{@code KEEPTTL}/{@code GET} options, the
 * {@code INCR}/{@code DECR} family, and ranged operations.
 */
public final class StringCommands {

    /** Redis's maximum string size (proto-max-bulk-len), 512 MB. */
    private static final int MAX_STRING_SIZE = 512 * 1024 * 1024;

    private StringCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the string commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("set", -3, StringCommands::set));
        registry.register(CommandSpec.of("get", 2, StringCommands::get));
        registry.register(CommandSpec.of("getset", 3, StringCommands::getset));
        registry.register(CommandSpec.of("getdel", 2, StringCommands::getdel));
        registry.register(CommandSpec.of("getex", -2, StringCommands::getex));
        registry.register(CommandSpec.of("append", 3, StringCommands::append));
        registry.register(CommandSpec.of("strlen", 2, StringCommands::strlen));
        registry.register(CommandSpec.of("incr", 2, ctx -> incrBy(ctx, 1)));
        registry.register(CommandSpec.of("decr", 2, ctx -> incrBy(ctx, -1)));
        registry.register(CommandSpec.of("incrby", 3, StringCommands::incrby));
        registry.register(CommandSpec.of("decrby", 3, StringCommands::decrby));
        registry.register(CommandSpec.of("incrbyfloat", 3, StringCommands::incrbyfloat));
        registry.register(CommandSpec.of("setrange", 4, StringCommands::setrange));
        registry.register(CommandSpec.of("getrange", 4, StringCommands::getrange));
        registry.register(CommandSpec.of("mset", -3, StringCommands::mset));
        registry.register(CommandSpec.of("mget", -2, StringCommands::mget));
        registry.register(CommandSpec.of("msetnx", -3, StringCommands::msetnx));
        registry.register(CommandSpec.of("setnx", 3, StringCommands::setnx));
        registry.register(CommandSpec.of("setex", 4, StringCommands::setex));
        registry.register(CommandSpec.of("psetex", 4, StringCommands::psetex));
    }

    // ---- SET and its option grammar -----------------------------------------

    private static RespValue set(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        byte[] value = ctx.arg(2);

        boolean nx = false;
        boolean xx = false;
        boolean keepttl = false;
        boolean get = false;
        boolean hasExpire = false;
        long expireAtMs = -1;

        for (int i = 3; i < ctx.argCount(); ) {
            String opt = ctx.argUpper(i);
            switch (opt) {
                case "NX" -> {
                    if (xx) {
                        throw CommandException.syntax();
                    }
                    nx = true;
                    i++;
                }
                case "XX" -> {
                    if (nx) {
                        throw CommandException.syntax();
                    }
                    xx = true;
                    i++;
                }
                case "GET" -> {
                    get = true;
                    i++;
                }
                case "KEEPTTL" -> {
                    if (hasExpire) {
                        throw CommandException.syntax();
                    }
                    keepttl = true;
                    i++;
                }
                case "EX", "PX", "EXAT", "PXAT" -> {
                    if (hasExpire || keepttl || i + 1 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    long n = Keyspaces.parseLong(ctx.arg(i + 1));
                    expireAtMs = absoluteExpiry(opt, n, "set");
                    hasExpire = true;
                    i += 2;
                }
                default -> throw CommandException.syntax();
            }
        }

        Database db = ctx.database();
        RedisValue existing = db.lookup(key);
        StringValue old = get ? Keyspaces.asString(existing) : null;
        boolean exists = existing != null;

        if ((nx && exists) || (xx && !exists)) {
            ctx.suppressPropagation(); // condition failed: nothing changed
            return get ? oldReply(old) : RespValue.NULL;
        }

        StringValue newValue = new StringValue(value);
        if (keepttl) {
            db.putKeepTtl(key, newValue);
        } else {
            db.put(key, newValue);
        }
        if (hasExpire) {
            db.setExpireAt(key, expireAtMs);
        }
        // Propagate a normalized, deterministic SET: drop NX/XX/GET (conditions and
        // reply modifiers, not state) and make any expiry absolute (PXAT).
        if (hasExpire) {
            ctx.propagate(new byte[][]{bytes("SET"), key.array(), value,
                    bytes("PXAT"), bytes(Long.toString(expireAtMs))});
        } else if (keepttl) {
            ctx.propagate(new byte[][]{bytes("SET"), key.array(), value, bytes("KEEPTTL")});
        } else {
            ctx.propagate(new byte[][]{bytes("SET"), key.array(), value});
        }
        return get ? oldReply(old) : RespValue.OK;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static RespValue oldReply(StringValue old) {
        return old == null ? RespValue.NULL : RespValue.bulk(old.get());
    }

    // ---- Plain reads/writes -------------------------------------------------

    private static RespValue get(CommandContext ctx) {
        StringValue v = Keyspaces.asString(ctx.database().lookup(new Bytes(ctx.arg(1))));
        return v == null ? RespValue.NULL : RespValue.bulk(v.get());
    }

    private static RespValue getset(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        StringValue old = Keyspaces.asString(db.lookup(key));
        db.put(key, new StringValue(ctx.arg(2)));
        return oldReply(old);
    }

    private static RespValue getdel(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        StringValue old = Keyspaces.asString(db.lookup(key));
        if (old != null) {
            db.remove(key);
        }
        return oldReply(old);
    }

    private static RespValue getex(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        StringValue v = Keyspaces.asString(db.lookup(key));
        if (v == null) {
            return RespValue.NULL;
        }
        boolean persist = false;
        boolean hasExpire = false;
        long expireAtMs = -1;
        for (int i = 2; i < ctx.argCount(); ) {
            String opt = ctx.argUpper(i);
            switch (opt) {
                case "PERSIST" -> {
                    if (hasExpire || persist) {
                        throw CommandException.syntax();
                    }
                    persist = true;
                    i++;
                }
                case "EX", "PX", "EXAT", "PXAT" -> {
                    if (hasExpire || persist || i + 1 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    long n = Keyspaces.parseLong(ctx.arg(i + 1));
                    expireAtMs = absoluteExpiry(opt, n, "getex");
                    hasExpire = true;
                    i += 2;
                }
                default -> throw CommandException.syntax();
            }
        }
        // GETEX only changes the TTL; propagate that change deterministically (the
        // read itself is not replicated). With no option it is a pure read.
        if (persist) {
            if (db.persist(key)) {
                ctx.propagate(new byte[][]{bytes("PERSIST"), key.array()});
            } else {
                ctx.suppressPropagation();
            }
        } else if (hasExpire) {
            db.setExpireAt(key, expireAtMs);
            ctx.propagate(new byte[][]{bytes("PEXPIREAT"), key.array(), bytes(Long.toString(expireAtMs))});
        } else {
            ctx.suppressPropagation();
        }
        return RespValue.bulk(v.get());
    }

    private static RespValue append(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        byte[] suffix = ctx.arg(2);
        Database db = ctx.database();
        StringValue v = Keyspaces.asString(db.lookup(key));
        if (v == null) {
            StringValue created = new StringValue(new byte[0]);
            created.setRaw(suffix);
            db.put(key, created);
            return RespValue.integer(suffix.length);
        }
        byte[] cur = v.get();
        long total = (long) cur.length + suffix.length;
        if (total > MAX_STRING_SIZE) {
            throw new CommandException("ERR string exceeds maximum allowed size (proto-max-bulk-len)");
        }
        byte[] combined = new byte[(int) total];
        System.arraycopy(cur, 0, combined, 0, cur.length);
        System.arraycopy(suffix, 0, combined, cur.length, suffix.length);
        v.setRaw(combined);
        return RespValue.integer(combined.length);
    }

    private static RespValue strlen(CommandContext ctx) {
        StringValue v = Keyspaces.asString(ctx.database().lookup(new Bytes(ctx.arg(1))));
        return RespValue.integer(v == null ? 0 : v.length());
    }

    // ---- INCR / DECR family -------------------------------------------------

    private static RespValue incrby(CommandContext ctx) {
        return incrBy(ctx, Keyspaces.parseLong(ctx.arg(2)));
    }

    private static RespValue decrby(CommandContext ctx) {
        long decr = Keyspaces.parseLong(ctx.arg(2));
        if (decr == Long.MIN_VALUE) {
            throw new CommandException("ERR decrement would overflow");
        }
        return incrBy(ctx, -decr);
    }

    private static RespValue incrBy(CommandContext ctx, long delta) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        StringValue v = Keyspaces.asString(db.lookup(key));
        long current = (v == null) ? 0 : Keyspaces.parseLong(v.get());
        if ((delta > 0 && current > Long.MAX_VALUE - delta)
                || (delta < 0 && current < Long.MIN_VALUE - delta)) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        long result = current + delta;
        db.putKeepTtl(key, new StringValue(Long.toString(result).getBytes(StandardCharsets.US_ASCII)));
        return RespValue.integer(result);
    }

    private static RespValue incrbyfloat(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        StringValue v = Keyspaces.asString(db.lookup(key));
        double current = (v == null) ? 0 : Keyspaces.parseFiniteDouble(v.get());
        double increment = Keyspaces.parseFiniteDouble(ctx.arg(2));
        double result = current + increment;
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new CommandException("ERR increment would produce NaN or Infinity");
        }
        String formatted = Keyspaces.formatDouble(result);
        byte[] formattedBytes = formatted.getBytes(StandardCharsets.US_ASCII);
        db.putKeepTtl(key, new StringValue(formattedBytes));
        // Propagate the concrete result as SET, since float formatting is not
        // bit-for-bit portable (replicas must store the master's exact string).
        ctx.propagate(new byte[][]{bytes("SET"), key.array(), formattedBytes, bytes("KEEPTTL")});
        return RespValue.bulk(formatted);
    }

    // ---- Ranged operations --------------------------------------------------

    private static RespValue setrange(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        long offset = Keyspaces.parseLong(ctx.arg(2));
        if (offset < 0) {
            throw new CommandException("ERR offset is out of range");
        }
        byte[] patch = ctx.arg(3);
        Database db = ctx.database();
        StringValue v = Keyspaces.asString(db.lookup(key));

        if (patch.length == 0) {
            return RespValue.integer(v == null ? 0 : v.length());
        }
        long needed = offset + patch.length;
        if (needed > MAX_STRING_SIZE) {
            throw new CommandException("ERR string exceeds maximum allowed size (proto-max-bulk-len)");
        }
        byte[] cur = (v == null) ? new byte[0] : v.get();
        byte[] result = new byte[(int) Math.max(cur.length, needed)];
        System.arraycopy(cur, 0, result, 0, cur.length);
        System.arraycopy(patch, 0, result, (int) offset, patch.length);
        if (v == null) {
            StringValue created = new StringValue(new byte[0]);
            created.setRaw(result);
            db.put(key, created);
        } else {
            v.setRaw(result);
        }
        return RespValue.integer(result.length);
    }

    private static RespValue getrange(CommandContext ctx) {
        StringValue v = Keyspaces.asString(ctx.database().lookup(new Bytes(ctx.arg(1))));
        long start = Keyspaces.parseLong(ctx.arg(2));
        long end = Keyspaces.parseLong(ctx.arg(3));
        if (v == null) {
            return RespValue.bulk(new byte[0]);
        }
        byte[] s = v.get();
        int len = s.length;
        if (start < 0) {
            start = len + start;
        }
        if (end < 0) {
            end = len + end;
        }
        if (start < 0) {
            start = 0;
        }
        if (end < 0) {
            end = 0;
        }
        if (end >= len) {
            end = len - 1;
        }
        if (start > end || len == 0) {
            return RespValue.bulk(new byte[0]);
        }
        byte[] out = new byte[(int) (end - start + 1)];
        System.arraycopy(s, (int) start, out, 0, out.length);
        return RespValue.bulk(out);
    }

    // ---- Multi-key ----------------------------------------------------------

    private static RespValue mset(CommandContext ctx) {
        if (ctx.argCount() % 2 == 0) {
            throw new CommandException("ERR wrong number of arguments for 'mset' command");
        }
        Database db = ctx.database();
        for (int i = 1; i < ctx.argCount(); i += 2) {
            db.put(new Bytes(ctx.arg(i)), new StringValue(ctx.arg(i + 1)));
        }
        return RespValue.OK;
    }

    private static RespValue mget(CommandContext ctx) {
        Database db = ctx.database();
        List<RespValue> out = new ArrayList<>(ctx.argCount() - 1);
        for (int i = 1; i < ctx.argCount(); i++) {
            RedisValue v = db.lookup(new Bytes(ctx.arg(i)));
            out.add(v instanceof StringValue s ? RespValue.bulk(s.get()) : RespValue.NULL);
        }
        return new RespValue.Array(out);
    }

    private static RespValue msetnx(CommandContext ctx) {
        if (ctx.argCount() % 2 == 0) {
            throw new CommandException("ERR wrong number of arguments for 'msetnx' command");
        }
        Database db = ctx.database();
        for (int i = 1; i < ctx.argCount(); i += 2) {
            if (db.containsKey(new Bytes(ctx.arg(i)))) {
                return RespValue.integer(0);
            }
        }
        for (int i = 1; i < ctx.argCount(); i += 2) {
            db.put(new Bytes(ctx.arg(i)), new StringValue(ctx.arg(i + 1)));
        }
        return RespValue.integer(1);
    }

    private static RespValue setnx(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        if (db.containsKey(key)) {
            return RespValue.integer(0);
        }
        db.put(key, new StringValue(ctx.arg(2)));
        return RespValue.integer(1);
    }

    private static RespValue setex(CommandContext ctx) {
        return setexImpl(ctx, true);
    }

    private static RespValue psetex(CommandContext ctx) {
        return setexImpl(ctx, false);
    }

    private static RespValue setexImpl(CommandContext ctx, boolean seconds) {
        Bytes key = new Bytes(ctx.arg(1));
        long ttl = Keyspaces.parseLong(ctx.arg(2));
        if (ttl <= 0) {
            throw new CommandException(
                    "ERR invalid expire time in '" + (seconds ? "setex" : "psetex") + "' command");
        }
        Database db = ctx.database();
        db.put(key, new StringValue(ctx.arg(3)));
        long expireAtMs = System.currentTimeMillis() + (seconds ? ttl * 1000 : ttl);
        db.setExpireAt(key, expireAtMs);
        // Propagate as an absolute SET ... PXAT, like Redis.
        ctx.propagate(new byte[][]{bytes("SET"), key.array(), ctx.arg(3),
                bytes("PXAT"), bytes(Long.toString(expireAtMs))});
        return RespValue.OK;
    }

    /**
     * Converts an {@code EX}/{@code PX}/{@code EXAT}/{@code PXAT} value to an
     * absolute epoch-millis expiry, validating relative TTLs are positive.
     */
    private static long absoluteExpiry(String option, long value, String command) {
        long now = System.currentTimeMillis();
        return switch (option) {
            case "EX" -> {
                requirePositive(value, command);
                yield now + value * 1000;
            }
            case "PX" -> {
                requirePositive(value, command);
                yield now + value;
            }
            case "EXAT" -> value * 1000;
            case "PXAT" -> value;
            default -> throw CommandException.syntax();
        };
    }

    private static void requirePositive(long value, String command) {
        if (value <= 0) {
            throw new CommandException("ERR invalid expire time in '" + command + "' command");
        }
    }
}
