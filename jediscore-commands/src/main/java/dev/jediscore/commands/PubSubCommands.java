package dev.jediscore.commands;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.PubSubRegistry;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Publish/subscribe: {@code SUBSCRIBE}, {@code UNSUBSCRIBE}, {@code PSUBSCRIBE},
 * {@code PUNSUBSCRIBE}, {@code PUBLISH}, {@code PUBSUB}, and the Redis 7 sharded
 * variants {@code SSUBSCRIBE}/{@code SUNSUBSCRIBE}/{@code SPUBLISH}.
 *
 * <p><strong>Reply shape.</strong> Subscribe and unsubscribe emit <em>one frame
 * per channel</em> as out-of-band {@link RespValue.Push} messages (rendered as
 * {@code >} pushes in RESP3 and plain arrays in RESP2), then return {@code null}
 * so the dispatcher writes no additional reply. Delivered messages are likewise
 * pushes. {@code PUBLISH}/{@code SPUBLISH} return the integer receiver count, and
 * {@code PUBSUB} returns ordinary replies.
 *
 * <p>All handlers run on the command thread, where {@link PubSubRegistry} lives.
 */
public final class PubSubCommands {

    private static final byte[] SUBSCRIBE = bytes("subscribe");
    private static final byte[] UNSUBSCRIBE = bytes("unsubscribe");
    private static final byte[] PSUBSCRIBE = bytes("psubscribe");
    private static final byte[] PUNSUBSCRIBE = bytes("punsubscribe");
    private static final byte[] SSUBSCRIBE = bytes("ssubscribe");
    private static final byte[] SUNSUBSCRIBE = bytes("sunsubscribe");

    private PubSubCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the pub/sub commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("subscribe", -2, PubSubCommands::subscribe));
        registry.register(CommandSpec.of("unsubscribe", -1, PubSubCommands::unsubscribe));
        registry.register(CommandSpec.of("psubscribe", -2, PubSubCommands::psubscribe));
        registry.register(CommandSpec.of("punsubscribe", -1, PubSubCommands::punsubscribe));
        registry.register(CommandSpec.of("publish", 3, PubSubCommands::publish));
        registry.register(CommandSpec.of("ssubscribe", -2, PubSubCommands::ssubscribe));
        registry.register(CommandSpec.of("sunsubscribe", -1, PubSubCommands::sunsubscribe));
        registry.register(CommandSpec.of("spublish", 3, PubSubCommands::spublish));
        registry.register(CommandSpec.of("pubsub", -2, PubSubCommands::pubsub));
    }

    // ---- (P/S)SUBSCRIBE ------------------------------------------------------

    private static RespValue subscribe(CommandContext ctx) {
        PubSubRegistry reg = ctx.server().pubsub();
        ClientConnection conn = ctx.connection();
        for (int i = 1; i < ctx.argCount(); i++) {
            int count = reg.subscribe(conn, ctx.arg(i));
            conn.deliver(confirmation(SUBSCRIBE, ctx.arg(i), count));
        }
        return null;
    }

    private static RespValue psubscribe(CommandContext ctx) {
        PubSubRegistry reg = ctx.server().pubsub();
        ClientConnection conn = ctx.connection();
        for (int i = 1; i < ctx.argCount(); i++) {
            int count = reg.psubscribe(conn, ctx.arg(i));
            conn.deliver(confirmation(PSUBSCRIBE, ctx.arg(i), count));
        }
        return null;
    }

    private static RespValue ssubscribe(CommandContext ctx) {
        PubSubRegistry reg = ctx.server().pubsub();
        ClientConnection conn = ctx.connection();
        for (int i = 1; i < ctx.argCount(); i++) {
            int count = reg.ssubscribe(conn, ctx.arg(i));
            conn.deliver(confirmation(SSUBSCRIBE, ctx.arg(i), count));
        }
        return null;
    }

    // ---- (P/S)UNSUBSCRIBE ----------------------------------------------------

    private static RespValue unsubscribe(CommandContext ctx) {
        PubSubRegistry reg = ctx.server().pubsub();
        ClientConnection conn = ctx.connection();
        List<byte[]> targets = explicitArgs(ctx);
        if (targets == null) {
            targets = reg.subscribedChannelsOf(conn);
        }
        if (targets.isEmpty()) {
            conn.deliver(confirmation(UNSUBSCRIBE, null, conn.regularSubscriptionCount()));
            return null;
        }
        for (byte[] channel : targets) {
            int count = reg.unsubscribe(conn, channel);
            conn.deliver(confirmation(UNSUBSCRIBE, channel, count));
        }
        return null;
    }

    private static RespValue punsubscribe(CommandContext ctx) {
        PubSubRegistry reg = ctx.server().pubsub();
        ClientConnection conn = ctx.connection();
        List<byte[]> targets = explicitArgs(ctx);
        if (targets == null) {
            targets = reg.subscribedPatternsOf(conn);
        }
        if (targets.isEmpty()) {
            conn.deliver(confirmation(PUNSUBSCRIBE, null, conn.regularSubscriptionCount()));
            return null;
        }
        for (byte[] pattern : targets) {
            int count = reg.punsubscribe(conn, pattern);
            conn.deliver(confirmation(PUNSUBSCRIBE, pattern, count));
        }
        return null;
    }

    private static RespValue sunsubscribe(CommandContext ctx) {
        PubSubRegistry reg = ctx.server().pubsub();
        ClientConnection conn = ctx.connection();
        List<byte[]> targets = explicitArgs(ctx);
        if (targets == null) {
            targets = reg.subscribedShardChannelsOf(conn);
        }
        if (targets.isEmpty()) {
            conn.deliver(confirmation(SUNSUBSCRIBE, null, conn.shardSubscriptionCount()));
            return null;
        }
        for (byte[] channel : targets) {
            int count = reg.sunsubscribe(conn, channel);
            conn.deliver(confirmation(SUNSUBSCRIBE, channel, count));
        }
        return null;
    }

    // ---- PUBLISH / SPUBLISH --------------------------------------------------

    private static RespValue publish(CommandContext ctx) {
        return RespValue.integer(ctx.server().pubsub().publish(ctx.arg(1), ctx.arg(2)));
    }

    private static RespValue spublish(CommandContext ctx) {
        return RespValue.integer(ctx.server().pubsub().spublish(ctx.arg(1), ctx.arg(2)));
    }

    // ---- PUBSUB --------------------------------------------------------------

    private static RespValue pubsub(CommandContext ctx) {
        PubSubRegistry reg = ctx.server().pubsub();
        String sub = ctx.argText(1).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "CHANNELS" -> {
                byte[] pattern = ctx.argCount() > 2 ? ctx.arg(2) : null;
                return bulkArray(reg.activeChannels(pattern));
            }
            case "SHARDCHANNELS" -> {
                byte[] pattern = ctx.argCount() > 2 ? ctx.arg(2) : null;
                return bulkArray(reg.activeShardChannels(pattern));
            }
            case "NUMSUB" -> {
                List<RespValue> out = new ArrayList<>();
                for (int i = 2; i < ctx.argCount(); i++) {
                    out.add(RespValue.bulk(ctx.arg(i)));
                    out.add(RespValue.integer(reg.subscriberCount(ctx.arg(i))));
                }
                return new RespValue.Array(out);
            }
            case "SHARDNUMSUB" -> {
                List<RespValue> out = new ArrayList<>();
                for (int i = 2; i < ctx.argCount(); i++) {
                    out.add(RespValue.bulk(ctx.arg(i)));
                    out.add(RespValue.integer(reg.shardSubscriberCount(ctx.arg(i))));
                }
                return new RespValue.Array(out);
            }
            case "NUMPAT" -> {
                return RespValue.integer(reg.patternCount());
            }
            default -> throw new CommandException(
                    "ERR Unknown PUBSUB subcommand or wrong number of arguments for '"
                            + ctx.argText(1) + "'");
        }
    }

    // ---- helpers -------------------------------------------------------------

    /** @return the explicit channel/pattern args, or {@code null} when none were given (unsubscribe-all) */
    private static List<byte[]> explicitArgs(CommandContext ctx) {
        if (ctx.argCount() == 1) {
            return null;
        }
        List<byte[]> args = new ArrayList<>(ctx.argCount() - 1);
        for (int i = 1; i < ctx.argCount(); i++) {
            args.add(ctx.arg(i));
        }
        return args;
    }

    /** Builds a {@code [kind, channel|nil, count]} confirmation push. */
    private static RespValue confirmation(byte[] kind, byte[] channel, int count) {
        RespValue channelValue = channel == null ? RespValue.NULL : RespValue.bulk(channel);
        return new RespValue.Push(List.of(RespValue.bulk(kind), channelValue, RespValue.integer(count)));
    }

    private static RespValue bulkArray(List<byte[]> items) {
        List<RespValue> out = new ArrayList<>(items.size());
        for (byte[] item : items) {
            out.add(RespValue.bulk(item));
        }
        return new RespValue.Array(out);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
