package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code COMMAND [subcommand]} — introspection over the command table:
 * {@code COMMAND} (full table), {@code COUNT}, {@code LIST}, {@code INFO},
 * {@code DOCS}, and {@code GETKEYS}.
 *
 * <p>Each entry follows Redis's {@code [name, arity, flags, first-key, last-key,
 * key-step]} shape. Key positions come from a built-in table
 * ({@link #firstKey}/{@link #lastKey}/{@link #keyStep}) that defaults to
 * {@code (1,1,1)} — correct for the great majority of commands — with explicit
 * entries for no-key and multi-key commands; {@code EVAL}/{@code EVALSHA} resolve
 * keys via their {@code numkeys} argument. This is a best-effort key-spec model
 * (documented in COMPATIBILITY.md), not Redis's full flag-rich spec.
 */
public final class CommandCommand implements Command {

    /** Commands that take no key arguments. */
    private static final java.util.Set<String> NO_KEY = java.util.Set.of(
            "PING", "ECHO", "HELLO", "AUTH", "QUIT", "RESET", "SELECT", "SWAPDB", "COMMAND", "INFO",
            "ROLE", "REPLICAOF", "SLAVEOF", "CONFIG", "DBSIZE", "FLUSHDB", "FLUSHALL", "DEBUG",
            "SLOWLOG", "LATENCY", "MONITOR", "CLIENT", "SAVE", "BGSAVE", "BGREWRITEAOF", "LASTSAVE",
            "MULTI", "EXEC", "DISCARD", "UNWATCH", "WAIT", "SCAN", "RANDOMKEY", "SCRIPT", "PUBLISH",
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PUBSUB", "SPUBLISH",
            "SSUBSCRIBE", "SUNSUBSCRIBE", "REPLCONF", "PSYNC", "SYNC", "SENTINEL", "FAILOVER");

    /** Commands whose keys span {@code args[1..N]} with the given step (last = -1 means "to end"). */
    private static final Map<String, int[]> MULTI_KEY = Map.ofEntries(
            Map.entry("MSET", new int[]{1, -1, 2}),
            Map.entry("MSETNX", new int[]{1, -1, 2}),
            Map.entry("MGET", new int[]{1, -1, 1}),
            Map.entry("DEL", new int[]{1, -1, 1}),
            Map.entry("UNLINK", new int[]{1, -1, 1}),
            Map.entry("EXISTS", new int[]{1, -1, 1}),
            Map.entry("WATCH", new int[]{1, -1, 1}),
            Map.entry("SINTER", new int[]{1, -1, 1}),
            Map.entry("SUNION", new int[]{1, -1, 1}),
            Map.entry("SDIFF", new int[]{1, -1, 1}),
            Map.entry("PFCOUNT", new int[]{1, -1, 1}),
            Map.entry("RENAME", new int[]{1, 2, 1}),
            Map.entry("RENAMENX", new int[]{1, 2, 1}),
            Map.entry("COPY", new int[]{1, 2, 1}),
            Map.entry("SMOVE", new int[]{1, 2, 1}),
            Map.entry("LMOVE", new int[]{1, 2, 1}),
            Map.entry("RPOPLPUSH", new int[]{1, 2, 1}),
            Map.entry("BLMOVE", new int[]{1, 2, 1}),
            Map.entry("BRPOPLPUSH", new int[]{1, 2, 1}));

    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() == 1) {
            return fullTable(ctx);
        }
        String sub = ctx.argUpper(1);
        return switch (sub) {
            case "COUNT" -> RespValue.integer(ctx.server().registry().size());
            case "LIST" -> commandList(ctx);
            case "INFO" -> commandInfo(ctx);
            case "DOCS" -> commandDocs(ctx);
            case "GETKEYS" -> getKeys(ctx);
            default -> RespValue.error(
                    "ERR Unknown subcommand or wrong number of arguments for '"
                            + ctx.argText(1) + "'. Try COMMAND HELP.");
        };
    }

    private static RespValue fullTable(CommandContext ctx) {
        List<RespValue> rows = new ArrayList<>();
        for (CommandSpec spec : ctx.server().registry().all()) {
            rows.add(describe(spec));
        }
        return new RespValue.Array(rows);
    }

    private static RespValue commandList(CommandContext ctx) {
        List<RespValue> names = new ArrayList<>();
        for (CommandSpec spec : ctx.server().registry().all()) {
            names.add(RespValue.bulk(spec.name()));
        }
        return new RespValue.Array(names);
    }

    private static RespValue commandInfo(CommandContext ctx) {
        List<RespValue> out = new ArrayList<>();
        if (ctx.argCount() == 2) {
            for (CommandSpec spec : ctx.server().registry().all()) {
                out.add(describe(spec));
            }
            return new RespValue.Array(out);
        }
        for (int i = 2; i < ctx.argCount(); i++) {
            CommandSpec spec = ctx.server().registry().lookup(ctx.argText(i));
            out.add(spec == null ? RespValue.NULL : describe(spec));
        }
        return new RespValue.Array(out);
    }

    private static RespValue commandDocs(CommandContext ctx) {
        List<RespValue.MapEntry> entries = new ArrayList<>();
        List<CommandSpec> specs = new ArrayList<>();
        if (ctx.argCount() == 2) {
            specs.addAll(ctx.server().registry().all());
        } else {
            for (int i = 2; i < ctx.argCount(); i++) {
                CommandSpec spec = ctx.server().registry().lookup(ctx.argText(i));
                if (spec != null) {
                    specs.add(spec);
                }
            }
        }
        for (CommandSpec spec : specs) {
            RespValue doc = new RespValue.Map(List.of(
                    new RespValue.MapEntry(RespValue.bulk("summary"), RespValue.bulk("")),
                    new RespValue.MapEntry(RespValue.bulk("since"), RespValue.bulk("1.0.0")),
                    new RespValue.MapEntry(RespValue.bulk("group"), RespValue.bulk("generic")),
                    new RespValue.MapEntry(RespValue.bulk("arity"), RespValue.integer(spec.arity()))));
            entries.add(new RespValue.MapEntry(RespValue.bulk(spec.name()), doc));
        }
        return new RespValue.Map(entries);
    }

    private static RespValue getKeys(CommandContext ctx) {
        if (ctx.argCount() < 3) {
            return RespValue.error("ERR Unknown subcommand or wrong number of arguments for 'GETKEYS'.");
        }
        String name = ctx.argText(2).toUpperCase(Locale.ROOT);
        // The args of the command being analysed start at index 2: args[2] is its name.
        int innerArgc = ctx.argCount() - 2;
        List<RespValue> keys = new ArrayList<>();
        if (name.equals("EVAL") || name.equals("EVALSHA") || name.equals("FCALL") || name.equals("FCALL_RO")) {
            // ... <name> <script/sha> <numkeys> key...   (numkeys is inner arg index 2)
            if (innerArgc >= 3) {
                long numKeys = parseLongSafe(ctx.argText(4));
                for (int i = 0; i < numKeys && 5 + i < ctx.argCount(); i++) {
                    keys.add(RespValue.bulk(ctx.arg(5 + i)));
                }
            }
        } else {
            int first = firstKey(name);
            if (first == 0) {
                return RespValue.error("ERR The command has no key arguments");
            }
            int last = lastKey(name);
            int step = keyStep(name);
            int innerLast = last < 0 ? innerArgc + last : last; // -1 → last inner arg
            for (int i = first; i <= innerLast && 2 + i < ctx.argCount(); i += step) {
                keys.add(RespValue.bulk(ctx.arg(2 + i)));
            }
        }
        if (keys.isEmpty()) {
            return RespValue.error("ERR The command has no key arguments");
        }
        return new RespValue.Array(keys);
    }

    private static RespValue describe(CommandSpec spec) {
        List<RespValue> flags = new ArrayList<>();
        for (String flag : spec.flags()) {
            flags.add(RespValue.simple(flag));
        }
        String name = spec.name().toUpperCase(Locale.ROOT);
        return new RespValue.Array(List.of(
                RespValue.bulk(spec.name()),
                RespValue.integer(spec.arity()),
                new RespValue.Array(flags),
                RespValue.integer(firstKey(name)),
                RespValue.integer(lastKey(name)),
                RespValue.integer(keyStep(name))));
    }

    private static int firstKey(String name) {
        if (NO_KEY.contains(name)) {
            return 0;
        }
        int[] spec = MULTI_KEY.get(name);
        return spec == null ? 1 : spec[0];
    }

    private static int lastKey(String name) {
        if (NO_KEY.contains(name)) {
            return 0;
        }
        int[] spec = MULTI_KEY.get(name);
        return spec == null ? 1 : spec[1];
    }

    private static int keyStep(String name) {
        if (NO_KEY.contains(name)) {
            return 0;
        }
        int[] spec = MULTI_KEY.get(name);
        return spec == null ? 1 : spec[2];
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
