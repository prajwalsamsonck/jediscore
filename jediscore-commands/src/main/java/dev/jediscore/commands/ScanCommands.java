package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.Glob;
import dev.jediscore.datastructures.HashValue;
import dev.jediscore.datastructures.SetValue;
import dev.jediscore.datastructures.ZSetValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;

/**
 * The cursor-based iteration family: {@code SCAN} over the keyspace and
 * {@code HSCAN}/{@code SSCAN}/{@code ZSCAN} over a collection.
 *
 * <p>Cursors are produced by the {@link dev.jediscore.datastructures.Dict}
 * bucket cursor, so a complete iteration (until the cursor returns to 0) yields
 * every element present for the whole scan even as the table grows or keys are
 * added/removed between calls. {@code COUNT} is a hint (buckets to visit per
 * call); {@code MATCH} filters results after the fact (so a call may return no
 * matches yet a non-zero cursor). Compact encodings (listpack/intset) are
 * returned whole with cursor {@code 0}, as in Redis.
 */
public final class ScanCommands {

    private ScanCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the scan commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("scan", -2, ScanCommands::scan));
        registry.register(CommandSpec.of("hscan", -3, ScanCommands::hscan));
        registry.register(CommandSpec.of("sscan", -3, ScanCommands::sscan));
        registry.register(CommandSpec.of("zscan", -3, ScanCommands::zscan));
    }

    /** Parsed common scan options. */
    private static final class Options {
        byte[] match;
        int count = 10;
        boolean noValues;
        String type;
    }

    private static long parseCursor(byte[] bytes) {
        try {
            return Long.parseUnsignedLong(new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR invalid cursor");
        }
    }

    private static Options parseOptions(CommandContext ctx, int from, boolean allowType, boolean allowNoValues) {
        Options opt = new Options();
        for (int i = from; i < ctx.argCount(); ) {
            String token = ctx.argUpper(i);
            switch (token) {
                case "MATCH" -> {
                    if (i + 1 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    opt.match = ctx.arg(i + 1);
                    i += 2;
                }
                case "COUNT" -> {
                    if (i + 1 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    long c = Keyspaces.parseLong(ctx.arg(i + 1));
                    if (c <= 0) {
                        throw CommandException.syntax();
                    }
                    opt.count = (int) Math.min(c, Integer.MAX_VALUE);
                    i += 2;
                }
                case "TYPE" -> {
                    if (!allowType || i + 1 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    opt.type = ctx.argText(i + 1).toLowerCase(java.util.Locale.ROOT);
                    i += 2;
                }
                case "NOVALUES" -> {
                    if (!allowNoValues) {
                        throw CommandException.syntax();
                    }
                    opt.noValues = true;
                    i++;
                }
                default -> throw CommandException.syntax();
            }
        }
        return opt;
    }

    private static RespValue reply(long nextCursor, List<RespValue> elements) {
        return new RespValue.Array(List.of(
                RespValue.bulk(Long.toUnsignedString(nextCursor)),
                new RespValue.Array(elements)));
    }

    private static RespValue scan(CommandContext ctx) {
        long cursor = parseCursor(ctx.arg(1));
        Options opt = parseOptions(ctx, 2, true, false);
        List<RespValue> keys = new ArrayList<>();
        long next = ctx.database().scan(cursor, opt.count, (key, value) -> {
            if (opt.match != null && !Glob.match(opt.match, key.array())) {
                return;
            }
            if (opt.type != null && !value.type().typeName().equals(opt.type)) {
                return;
            }
            keys.add(RespValue.bulk(key.array()));
        });
        return reply(next, keys);
    }

    private static RespValue hscan(CommandContext ctx) {
        HashValue hash = Keyspaces.asHash(ctx.database().lookup(new Bytes(ctx.arg(1))));
        long cursor = parseCursor(ctx.arg(2));
        Options opt = parseOptions(ctx, 3, false, true);
        List<RespValue> out = new ArrayList<>();
        if (hash == null) {
            return reply(0, out);
        }
        long next = hash.scan(cursor, opt.count, (field, value) -> {
            if (opt.match != null && !Glob.match(opt.match, field)) {
                return;
            }
            out.add(RespValue.bulk(field));
            if (!opt.noValues) {
                out.add(RespValue.bulk(value));
            }
        });
        return reply(next, out);
    }

    private static RespValue sscan(CommandContext ctx) {
        SetValue set = Keyspaces.asSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        long cursor = parseCursor(ctx.arg(2));
        Options opt = parseOptions(ctx, 3, false, false);
        List<RespValue> out = new ArrayList<>();
        if (set == null) {
            return reply(0, out);
        }
        long next = set.scan(cursor, opt.count, member -> {
            if (opt.match != null && !Glob.match(opt.match, member)) {
                return;
            }
            out.add(RespValue.bulk(member));
        });
        return reply(next, out);
    }

    private static RespValue zscan(CommandContext ctx) {
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        long cursor = parseCursor(ctx.arg(2));
        Options opt = parseOptions(ctx, 3, false, false);
        List<RespValue> out = new ArrayList<>();
        if (zset == null) {
            return reply(0, out);
        }
        long next = zset.scan(cursor, opt.count, (member, score) -> {
            if (opt.match != null && !Glob.match(opt.match, member)) {
                return;
            }
            out.add(RespValue.bulk(member));
            out.add(RespValue.bulk(Keyspaces.formatScore(score)));
        });
        return reply(next, out);
    }
}
