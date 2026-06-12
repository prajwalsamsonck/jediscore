package dev.jediscore.commands;

import dev.jediscore.engine.Command;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code COMMAND [subcommand]} — introspection over the command table.
 *
 * <p>Phase-1 supports {@code COMMAND} (full table), {@code COUNT}, {@code LIST},
 * {@code INFO}, and {@code DOCS}. {@code DOCS} returns an empty map, which is a
 * valid response that {@code redis-cli} tolerates when building its help; richer
 * docs are deferred. Each command's reply entry follows Redis's
 * {@code [name, arity, flags, first-key, last-key, key-step]} shape, with key
 * positions reported as {@code 0} until commands declare key specs in a later
 * phase.
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
            case "DOCS" -> new RespValue.Map(List.of());
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
            // No names given: describe the whole table.
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

    private static RespValue describe(CommandSpec spec) {
        List<RespValue> flags = new ArrayList<>();
        for (String flag : spec.flags()) {
            flags.add(RespValue.simple(flag));
        }
        return new RespValue.Array(List.of(
                RespValue.bulk(spec.name()),
                RespValue.integer(spec.arity()),
                new RespValue.Array(flags),
                RespValue.integer(0),   // first key
                RespValue.integer(0),   // last key
                RespValue.integer(0))); // key step
    }
}
