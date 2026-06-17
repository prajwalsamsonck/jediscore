package dev.jediscore.commands;

import dev.jediscore.datastructures.Glob;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.MaxmemoryPolicy;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * {@code CONFIG GET/SET/REWRITE/RESETSTAT} over a table of supported parameters.
 *
 * <p>Each parameter maps to a getter and (unless read-only) a setter. Config-backed
 * scalars rebuild the immutable {@link dev.jediscore.engine.ServerConfig} via its
 * builder and swap it on the {@link ServerContext}; subsystem parameters (slowlog,
 * latency) call the relevant setters directly. Parameters not in the table are
 * unknown to {@code CONFIG} (a faithful subset, documented in COMPATIBILITY.md).
 */
public final class ConfigCommands {

    /** One configurable parameter: a getter and an optional setter (null = read-only). */
    private record Param(Function<ServerContext, String> get, BiConsumer<ServerContext, String> set) { }

    private static final Map<String, Param> PARAMS = buildParams();

    private ConfigCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers {@code CONFIG}.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("config", -2, ConfigCommands::config));
    }

    private static RespValue config(CommandContext ctx) {
        String sub = ctx.argUpper(1);
        return switch (sub) {
            case "GET" -> get(ctx);
            case "SET" -> set(ctx);
            case "RESETSTAT" -> {
                ctx.server().stats().reset();
                yield RespValue.OK;
            }
            case "REWRITE" -> rewrite(ctx);
            default -> throw new CommandException(
                    "ERR Unknown CONFIG subcommand or wrong number of arguments for '" + ctx.argText(1) + "'");
        };
    }

    private static RespValue get(CommandContext ctx) {
        if (ctx.argCount() < 3) {
            throw new CommandException("ERR wrong number of arguments for 'config|get' command");
        }
        List<RespValue.MapEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Param> e : PARAMS.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.US_ASCII);
            for (int i = 2; i < ctx.argCount(); i++) {
                if (Glob.match(ctx.argText(i).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII), name)) {
                    entries.add(new RespValue.MapEntry(
                            RespValue.bulk(e.getKey()),
                            RespValue.bulk(e.getValue().get().apply(ctx.server()))));
                    break;
                }
            }
        }
        return new RespValue.Map(entries);
    }

    private static RespValue set(CommandContext ctx) {
        if (ctx.argCount() < 4 || (ctx.argCount() % 2) != 0) {
            throw new CommandException("ERR wrong number of arguments for 'config|set' command");
        }
        for (int i = 2; i + 1 < ctx.argCount(); i += 2) {
            String name = ctx.argText(i).toLowerCase(Locale.ROOT);
            Param param = PARAMS.get(name);
            if (param == null) {
                throw new CommandException(
                        "ERR Unknown option or number of arguments for CONFIG SET - '" + ctx.argText(i) + "'");
            }
            if (param.set() == null) {
                throw new CommandException(
                        "ERR CONFIG SET failed - can't set immutable config parameter '" + name + "'");
            }
            param.set().accept(ctx.server(), ctx.argText(i + 1));
        }
        return RespValue.OK;
    }

    private static RespValue rewrite(CommandContext ctx) {
        String file = ctx.server().configFile();
        if (file == null) {
            throw new CommandException("ERR The server is running without a config file");
        }
        StringBuilder sb = new StringBuilder("# Rewritten by JediCore CONFIG REWRITE\r\n");
        for (Map.Entry<String, Param> e : PARAMS.entrySet()) {
            if (e.getValue().set() != null) {
                String value = e.getValue().get().apply(ctx.server());
                sb.append(e.getKey()).append(' ').append(value.isEmpty() ? "\"\"" : value).append('\n');
            }
        }
        try {
            Files.writeString(Path.of(file), sb.toString());
        } catch (IOException ex) {
            throw new CommandException("ERR Rewriting config file: " + ex.getMessage());
        }
        return RespValue.OK;
    }

    // ---- parameter table -----------------------------------------------------

    private static Map<String, Param> buildParams() {
        Map<String, Param> p = new LinkedHashMap<>();
        // Read-only.
        p.put("port", readOnly(c -> Integer.toString(c.config().port())));
        p.put("bind", readOnly(c -> c.config().host()));
        p.put("databases", readOnly(c -> Integer.toString(c.config().databases())));
        p.put("appendonly", readOnly(c -> c.persistence() != null && c.persistence().appendOnlyEnabled() ? "yes" : "no"));
        p.put("dir", readOnly(c -> c.persistence() == null ? "" : c.persistence().dir()));

        // Hardening (settable).
        p.put("maxclients", param(c -> Integer.toString(c.maxClients()),
                (c, v) -> c.setMaxClients((int) parseLong(v))));
        p.put("protected-mode", param(c -> c.protectedMode() ? "yes" : "no",
                (c, v) -> c.setProtectedMode("yes".equalsIgnoreCase(v))));

        // Memory / eviction (config-backed).
        p.put("maxmemory", param(c -> Long.toString(c.config().maxMemory()),
                (c, v) -> c.setConfig(c.config().toBuilder().maxMemory(parseMemory(v)).build())));
        p.put("maxmemory-policy", param(c -> c.config().maxMemoryPolicy().configName(),
                (c, v) -> c.setConfig(c.config().toBuilder().maxMemoryPolicy(MaxmemoryPolicy.fromConfig(v)).build())));
        p.put("maxmemory-samples", param(c -> Integer.toString(c.config().maxMemorySamples()),
                (c, v) -> c.setConfig(c.config().toBuilder().maxMemorySamples((int) parseLong(v)).build())));

        // Auth.
        p.put("requirepass", param(c -> c.config().requirepass().orElse(""),
                (c, v) -> {
                    c.setConfig(c.config().toBuilder()
                            .requirepass(v.isEmpty() ? Optional.empty() : Optional.of(v)).build());
                    // Keep the ACL default user in sync with requirepass.
                    c.acl().defaultUser().setPlainPassword(v.isEmpty() ? null : v);
                }));

        // Encoding thresholds (config-backed).
        p.put("hash-max-listpack-entries", param(c -> Integer.toString(c.config().hashMaxListpackEntries()),
                (c, v) -> c.setConfig(c.config().toBuilder().hashMaxListpackEntries((int) parseLong(v)).build())));
        p.put("hash-max-listpack-value", param(c -> Integer.toString(c.config().hashMaxListpackValue()),
                (c, v) -> c.setConfig(c.config().toBuilder().hashMaxListpackValue((int) parseLong(v)).build())));
        p.put("list-max-listpack-size", param(c -> Integer.toString(c.config().listMaxListpackSize()),
                (c, v) -> c.setConfig(c.config().toBuilder().listMaxListpackSize((int) parseLong(v)).build())));
        p.put("set-max-intset-entries", param(c -> Integer.toString(c.config().setMaxIntsetEntries()),
                (c, v) -> c.setConfig(c.config().toBuilder().setMaxIntsetEntries((int) parseLong(v)).build())));
        p.put("set-max-listpack-entries", param(c -> Integer.toString(c.config().setMaxListpackEntries()),
                (c, v) -> c.setConfig(c.config().toBuilder().setMaxListpackEntries((int) parseLong(v)).build())));
        p.put("set-max-listpack-value", param(c -> Integer.toString(c.config().setMaxListpackValue()),
                (c, v) -> c.setConfig(c.config().toBuilder().setMaxListpackValue((int) parseLong(v)).build())));
        p.put("zset-max-listpack-entries", param(c -> Integer.toString(c.config().zsetMaxListpackEntries()),
                (c, v) -> c.setConfig(c.config().toBuilder().zsetMaxListpackEntries((int) parseLong(v)).build())));
        p.put("zset-max-listpack-value", param(c -> Integer.toString(c.config().zsetMaxListpackValue()),
                (c, v) -> c.setConfig(c.config().toBuilder().zsetMaxListpackValue((int) parseLong(v)).build())));

        // Diagnostics subsystems.
        p.put("slowlog-log-slower-than", param(c -> Long.toString(c.slowLog().thresholdMicros()),
                (c, v) -> c.slowLog().setThresholdMicros(parseLong(v))));
        p.put("slowlog-max-len", param(c -> Integer.toString(c.slowLog().maxLen()),
                (c, v) -> c.slowLog().setMaxLen((int) parseLong(v))));
        p.put("latency-monitor-threshold", param(c -> Long.toString(c.latencyMonitor().thresholdMillis()),
                (c, v) -> c.latencyMonitor().setThresholdMillis(parseLong(v))));
        return p;
    }

    private static Param param(Function<ServerContext, String> get, BiConsumer<ServerContext, String> set) {
        return new Param(get, set);
    }

    private static Param readOnly(Function<ServerContext, String> get) {
        return new Param(get, null);
    }

    /**
     * Parses a Redis memory size: {@code 100mb}, {@code 1gb}, {@code 1024}, …
     * (kb/mb/gb are 1024-based; k/m/g are 1000-based).
     *
     * @param text the size string
     * @return the value in bytes
     */
    public static long parseMemory(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        long mult = 1;
        if (s.endsWith("kb")) { mult = 1024L; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("mb")) { mult = 1024L * 1024; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("gb")) { mult = 1024L * 1024 * 1024; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("k")) { mult = 1000L; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1000_000L; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("g")) { mult = 1000_000_000L; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { s = s.substring(0, s.length() - 1); }
        try {
            return Long.parseLong(s.trim()) * mult;
        } catch (NumberFormatException e) {
            throw new CommandException("ERR Invalid memory value: " + text);
        }
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new CommandException("ERR argument couldn't be parsed into an integer");
        }
    }
}
