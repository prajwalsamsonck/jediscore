package dev.jediscore.commands;

import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.LatencyMonitor;
import dev.jediscore.engine.SlowLog;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Operational diagnostics: {@code SLOWLOG}, {@code LATENCY}, and {@code MONITOR}.
 *
 * <p>{@code SLOWLOG} and {@code LATENCY} read the command-thread-confined
 * {@link SlowLog}/{@link LatencyMonitor} that the dispatcher feeds with each
 * command's execution time. {@code MONITOR} flips the connection into the live
 * command-feed mode (delivered out-of-band via the connection's outbox).
 */
public final class DiagnosticsCommands {

    private DiagnosticsCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the diagnostics commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("slowlog", -2, DiagnosticsCommands::slowlog));
        registry.register(CommandSpec.of("latency", -2, DiagnosticsCommands::latency));
        registry.register(CommandSpec.of("monitor", 1, DiagnosticsCommands::monitor));
    }

    // ---- SLOWLOG -------------------------------------------------------------

    private static RespValue slowlog(CommandContext ctx) {
        SlowLog log = ctx.server().slowLog();
        String sub = ctx.argUpper(1);
        switch (sub) {
            case "GET" -> {
                int count = 10;
                if (ctx.argCount() > 2) {
                    count = (int) Keyspaces.parseLong(ctx.arg(2));
                }
                List<RespValue> out = new ArrayList<>();
                for (SlowLog.Entry e : log.get(count)) {
                    List<RespValue> args = new ArrayList<>(e.args().size());
                    for (byte[] arg : e.args()) {
                        args.add(RespValue.bulk(arg));
                    }
                    out.add(new RespValue.Array(List.of(
                            RespValue.integer(e.id()),
                            RespValue.integer(e.timeSeconds()),
                            RespValue.integer(e.durationMicros()),
                            new RespValue.Array(args),
                            RespValue.bulk(e.clientAddr()),
                            RespValue.bulk(e.clientName()))));
                }
                return new RespValue.Array(out);
            }
            case "LEN" -> {
                return RespValue.integer(log.length());
            }
            case "RESET" -> {
                log.reset();
                return RespValue.OK;
            }
            case "HELP" -> {
                return helpReply("SLOWLOG GET [count]", "SLOWLOG LEN", "SLOWLOG RESET");
            }
            default -> throw unknownSub("SLOWLOG", ctx.argText(1));
        }
    }

    // ---- LATENCY -------------------------------------------------------------

    private static RespValue latency(CommandContext ctx) {
        LatencyMonitor monitor = ctx.server().latencyMonitor();
        String sub = ctx.argUpper(1);
        switch (sub) {
            case "LATEST" -> {
                List<RespValue> out = new ArrayList<>();
                for (LatencyMonitor.LatestEntry e : monitor.latest()) {
                    out.add(new RespValue.Array(List.of(
                            RespValue.bulk(e.event()),
                            RespValue.integer(e.timeSeconds()),
                            RespValue.integer(e.latestMillis()),
                            RespValue.integer(e.maxMillis()))));
                }
                return new RespValue.Array(out);
            }
            case "HISTORY" -> {
                if (ctx.argCount() < 3) {
                    throw unknownSub("LATENCY", ctx.argText(1));
                }
                List<RespValue> out = new ArrayList<>();
                for (LatencyMonitor.Sample s : monitor.history(ctx.argText(2))) {
                    out.add(new RespValue.Array(List.of(
                            RespValue.integer(s.timeSeconds()), RespValue.integer(s.latencyMillis()))));
                }
                return new RespValue.Array(out);
            }
            case "RESET" -> {
                List<String> events = new ArrayList<>();
                for (int i = 2; i < ctx.argCount(); i++) {
                    events.add(ctx.argText(i));
                }
                return RespValue.integer(monitor.reset(events));
            }
            case "DOCTOR" -> {
                int tracked = monitor.trackedEvents().size();
                String msg = tracked == 0
                        ? "Dave, I have observed the system, no worrying latency spikes. Everything seems fine."
                        : "Dave, I have observed latency spikes in " + tracked + " event(s). "
                                + "Use LATENCY LATEST / HISTORY to inspect them.";
                return RespValue.bulk(msg);
            }
            default -> throw unknownSub("LATENCY", ctx.argText(1));
        }
    }

    // ---- MONITOR -------------------------------------------------------------

    private static RespValue monitor(CommandContext ctx) {
        ctx.server().monitors().add(ctx.connection());
        return RespValue.OK;
    }

    // ---- helpers -------------------------------------------------------------

    private static RespValue helpReply(String... lines) {
        List<RespValue> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            out.add(RespValue.simple(line));
        }
        return new RespValue.Array(out);
    }

    private static CommandException unknownSub(String command, String sub) {
        return new CommandException(
                "ERR Unknown " + command + " subcommand or wrong number of arguments for '" + sub + "'");
    }
}
