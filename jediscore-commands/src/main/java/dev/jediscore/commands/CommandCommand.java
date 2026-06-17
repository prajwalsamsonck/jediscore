package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandKeys;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code COMMAND [subcommand]} — introspection over the command table:
 * {@code COMMAND} (full table), {@code COUNT}, {@code LIST}, {@code INFO},
 * {@code DOCS}, and {@code GETKEYS}.
 *
 * <p>Each entry follows Redis's {@code [name, arity, flags, first-key, last-key,
 * key-step]} shape. Key positions come from the engine's {@link CommandKeys}
 * table (shared with ACL key enforcement): a {@code (1,1,1)} default with no-key
 * and multi-key overrides, and {@code EVAL}/{@code EVALSHA} via {@code numkeys}.
 * A best-effort model (documented in COMPATIBILITY.md), not Redis's full spec.
 */
public final class CommandCommand implements Command {

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
        // Build the analysed command's own argument vector (args[2..]) and delegate.
        byte[][] inner = new byte[ctx.argCount() - 2][];
        for (int i = 2; i < ctx.argCount(); i++) {
            inner[i - 2] = ctx.arg(i);
        }
        List<byte[]> extracted = CommandKeys.extractKeys(inner);
        if (extracted.isEmpty()) {
            return RespValue.error("ERR The command has no key arguments");
        }
        List<RespValue> keys = new ArrayList<>(extracted.size());
        for (byte[] key : extracted) {
            keys.add(RespValue.bulk(key));
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
                RespValue.integer(CommandKeys.firstKey(name)),
                RespValue.integer(CommandKeys.lastKey(name)),
                RespValue.integer(CommandKeys.keyStep(name))));
    }
}
