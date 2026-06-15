package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.ListValue;
import dev.jediscore.datastructures.ScoredMember;
import dev.jediscore.datastructures.ZSetValue;
import dev.jediscore.engine.BlockingOp;
import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.engine.ReplicationManager;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Blocking commands: {@code BLPOP}, {@code BRPOP}, {@code BLMOVE},
 * {@code BRPOPLPUSH}, {@code BLMPOP}, {@code BZPOPMIN}, {@code BZPOPMAX}, and
 * {@code WAIT}.
 *
 * <p>Each builds a {@link BlockingOp} and runs it immediately. If it succeeds the
 * reply is returned now; if not and blocking is permitted, the client is parked in
 * the {@link dev.jediscore.engine.BlockingManager} and the handler returns
 * {@code null} (no reply yet — it is pushed later when a key becomes ready or the
 * timeout fires). Inside {@code EXEC} (where {@link CommandContext#blockingAllowed}
 * is false) an unsatisfiable command returns its timeout reply immediately.
 *
 * <p>On success the operation propagates the <em>effective</em> mutation
 * ({@code LPOP}/{@code RPOP}/{@code ZPOPMIN}/…) to the AOF and WATCH layer; a
 * blocking command is never itself written to the AOF, since replaying it could
 * block during load.
 */
public final class BlockingCommands {

    private static final byte[] LPOP = "LPOP".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RPOP = "RPOP".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZPOPMIN = "ZPOPMIN".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZPOPMAX = "ZPOPMAX".getBytes(StandardCharsets.UTF_8);

    private BlockingCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the blocking commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("blpop", -3, ctx -> blpop(ctx, true)));
        registry.register(CommandSpec.of("brpop", -3, ctx -> blpop(ctx, false)));
        registry.register(CommandSpec.of("blmove", 6, BlockingCommands::blmove));
        registry.register(CommandSpec.of("brpoplpush", 4, BlockingCommands::brpoplpush));
        registry.register(CommandSpec.of("blmpop", -5, BlockingCommands::blmpop));
        registry.register(CommandSpec.of("bzpopmin", -3, ctx -> bzpop(ctx, false)));
        registry.register(CommandSpec.of("bzpopmax", -3, ctx -> bzpop(ctx, true)));
        registry.register(CommandSpec.of("wait", 3, BlockingCommands::wait_));
    }

    // ---- BLPOP / BRPOP -------------------------------------------------------

    private static RespValue blpop(CommandContext ctx, boolean head) {
        List<Bytes> keys = keysBetween(ctx, 1, ctx.argCount() - 1);
        double timeout = parseTimeout(ctx.arg(ctx.argCount() - 1));
        BlockingOp op = new BlockingOp() {
            @Override public RespValue attempt(ServerContext server, ClientConnection conn) {
                Database db = server.database(conn.db());
                for (Bytes key : keys) {
                    ListValue list = Keyspaces.asList(db.lookup(key));
                    if (list != null && !list.isEmpty()) {
                        byte[] element = head ? list.popHead() : list.popTail();
                        if (list.isEmpty()) {
                            db.remove(key);
                        }
                        server.propagateWrite(conn.db(), new byte[][]{head ? LPOP : RPOP, key.array()});
                        return new RespValue.Array(List.of(RespValue.bulk(key.array()), RespValue.bulk(element)));
                    }
                }
                return null;
            }
            @Override public RespValue timeoutReply() {
                return RespValue.NULL_ARRAY;
            }
        };
        return blockOrReply(ctx, keys, timeout, op);
    }

    // ---- BLMOVE / BRPOPLPUSH -------------------------------------------------

    private static RespValue blmove(CommandContext ctx) {
        boolean fromHead = side(ctx.argUpper(3));
        boolean toHead = side(ctx.argUpper(4));
        double timeout = parseTimeout(ctx.arg(5));
        return move(ctx, new Bytes(ctx.arg(1)), new Bytes(ctx.arg(2)), fromHead, toHead, timeout);
    }

    private static RespValue brpoplpush(CommandContext ctx) {
        double timeout = parseTimeout(ctx.arg(3));
        return move(ctx, new Bytes(ctx.arg(1)), new Bytes(ctx.arg(2)), false, true, timeout);
    }

    private static RespValue move(CommandContext ctx, Bytes source, Bytes dest,
                                  boolean fromHead, boolean toHead, double timeout) {
        BlockingOp op = new BlockingOp() {
            @Override public RespValue attempt(ServerContext server, ClientConnection conn) {
                Database db = server.database(conn.db());
                ListValue src = Keyspaces.asList(db.lookup(source));
                ListValue dst = Keyspaces.asList(db.lookup(dest)); // type-check dest too
                if (src == null || src.isEmpty()) {
                    return null;
                }
                byte[] element = fromHead ? src.popHead() : src.popTail();
                boolean fresh = dst == null;
                if (fresh) {
                    dst = Keyspaces.newList(ctx);
                }
                if (toHead) {
                    dst.pushHead(element);
                } else {
                    dst.pushTail(element);
                }
                if (fresh) {
                    db.put(dest, dst);
                }
                if (src.isEmpty() && !source.equals(dest)) {
                    db.remove(source);
                }
                // Effective propagation is the equivalent LMOVE.
                server.propagateWrite(conn.db(), new byte[][]{
                        "LMOVE".getBytes(StandardCharsets.UTF_8), source.array(), dest.array(),
                        fromHead ? "LEFT".getBytes(StandardCharsets.UTF_8) : "RIGHT".getBytes(StandardCharsets.UTF_8),
                        toHead ? "LEFT".getBytes(StandardCharsets.UTF_8) : "RIGHT".getBytes(StandardCharsets.UTF_8)});
                // The push may unblock a client waiting on the destination.
                server.blocking().signalKey(conn.db(), dest);
                return RespValue.bulk(element);
            }
            @Override public RespValue timeoutReply() {
                return RespValue.NULL; // BLMOVE/BRPOPLPUSH time out with a nil bulk, not a nil array
            }
        };
        return blockOrReply(ctx, List.of(source), timeout, op);
    }

    // ---- BLMPOP --------------------------------------------------------------

    private static RespValue blmpop(CommandContext ctx) {
        double timeout = parseTimeout(ctx.arg(1));
        long numKeys = Keyspaces.parseLong(ctx.arg(2));
        if (numKeys <= 0) {
            throw new CommandException("ERR numkeys should be greater than 0");
        }
        int keysEnd = 3 + (int) numKeys;
        if (keysEnd > ctx.argCount()) {
            throw CommandException.syntax();
        }
        List<Bytes> keys = keysBetween(ctx, 3, keysEnd);
        if (keysEnd >= ctx.argCount()) {
            throw CommandException.syntax();
        }
        boolean head = side(ctx.argUpper(keysEnd));
        long count = 1;
        int i = keysEnd + 1;
        if (i < ctx.argCount()) {
            if (!"COUNT".equals(ctx.argUpper(i)) || i + 1 >= ctx.argCount()) {
                throw CommandException.syntax();
            }
            count = Keyspaces.parseLong(ctx.arg(i + 1));
            if (count <= 0) {
                throw new CommandException("ERR count should be greater than 0");
            }
            i += 2;
        }
        if (i != ctx.argCount()) {
            throw CommandException.syntax();
        }
        long popCount = count;
        BlockingOp op = new BlockingOp() {
            @Override public RespValue attempt(ServerContext server, ClientConnection conn) {
                Database db = server.database(conn.db());
                for (Bytes key : keys) {
                    ListValue list = Keyspaces.asList(db.lookup(key));
                    if (list != null && !list.isEmpty()) {
                        List<RespValue> elements = new ArrayList<>();
                        for (long n = 0; n < popCount && !list.isEmpty(); n++) {
                            elements.add(RespValue.bulk(head ? list.popHead() : list.popTail()));
                        }
                        if (list.isEmpty()) {
                            db.remove(key);
                        }
                        server.propagateWrite(conn.db(), new byte[][]{
                                head ? LPOP : RPOP, key.array(),
                                Long.toString(elements.size()).getBytes(StandardCharsets.UTF_8)});
                        return new RespValue.Array(List.of(
                                RespValue.bulk(key.array()), new RespValue.Array(elements)));
                    }
                }
                return null;
            }
            @Override public RespValue timeoutReply() {
                return RespValue.NULL_ARRAY;
            }
        };
        return blockOrReply(ctx, keys, timeout, op);
    }

    // ---- BZPOPMIN / BZPOPMAX -------------------------------------------------

    private static RespValue bzpop(CommandContext ctx, boolean max) {
        List<Bytes> keys = keysBetween(ctx, 1, ctx.argCount() - 1);
        double timeout = parseTimeout(ctx.arg(ctx.argCount() - 1));
        BlockingOp op = new BlockingOp() {
            @Override public RespValue attempt(ServerContext server, ClientConnection conn) {
                Database db = server.database(conn.db());
                for (Bytes key : keys) {
                    ZSetValue zset = Keyspaces.asZSet(db.lookup(key));
                    if (zset != null && zset.size() > 0) {
                        ScoredMember m = max ? zset.popMax() : zset.popMin();
                        if (zset.size() == 0) {
                            db.remove(key);
                        }
                        server.propagateWrite(conn.db(), new byte[][]{max ? ZPOPMAX : ZPOPMIN, key.array()});
                        return new RespValue.Array(List.of(
                                RespValue.bulk(key.array()), RespValue.bulk(m.member()),
                                RespValue.bulk(Keyspaces.formatScore(m.score()))));
                    }
                }
                return null;
            }
            @Override public RespValue timeoutReply() {
                return RespValue.NULL_ARRAY;
            }
        };
        return blockOrReply(ctx, keys, timeout, op);
    }

    // ---- WAIT ----------------------------------------------------------------

    private static RespValue wait_(CommandContext ctx) {
        long numReplicas = Keyspaces.parseLong(ctx.arg(1));
        long timeoutMillis = Keyspaces.parseLong(ctx.arg(2));
        if (timeoutMillis < 0) {
            throw new CommandException("ERR timeout is negative");
        }
        ReplicationManager replication = ctx.server().replication();
        long target = replication.masterReplOffset();
        if (replication.replicasAckedAtLeast(target) >= numReplicas || !ctx.blockingAllowed()) {
            return RespValue.integer(replication.replicasAckedAtLeast(target));
        }
        // Solicit acknowledgements so replicas report their current offset promptly.
        if (replication.replicaCount() > 0) {
            replication.propagateRaw(new byte[][]{
                    "REPLCONF".getBytes(StandardCharsets.UTF_8),
                    "GETACK".getBytes(StandardCharsets.UTF_8),
                    "*".getBytes(StandardCharsets.UTF_8)});
        }
        BlockingOp op = new BlockingOp() {
            @Override public RespValue attempt(ServerContext server, ClientConnection conn) {
                int acked = replication.replicasAckedAtLeast(target);
                return acked >= numReplicas ? RespValue.integer(acked) : null;
            }
            @Override public RespValue timeoutReply() {
                return RespValue.integer(replication.replicasAckedAtLeast(target));
            }
        };
        ctx.server().blocking().block(ctx.connection(), ctx.connection().db(),
                List.of(), op, timeoutMillis / 1000.0);
        return null;
    }

    // ---- helpers -------------------------------------------------------------

    private static RespValue blockOrReply(CommandContext ctx, List<Bytes> keys, double timeout, BlockingOp op) {
        RespValue immediate = op.attempt(ctx.server(), ctx.connection());
        if (immediate != null) {
            return immediate;
        }
        if (!ctx.blockingAllowed()) {
            return op.timeoutReply();
        }
        ctx.server().blocking().block(ctx.connection(), ctx.connection().db(), keys, op, timeout);
        return null;
    }

    private static List<Bytes> keysBetween(CommandContext ctx, int from, int toExclusive) {
        List<Bytes> keys = new ArrayList<>(toExclusive - from);
        for (int i = from; i < toExclusive; i++) {
            keys.add(new Bytes(ctx.arg(i)));
        }
        return keys;
    }

    private static boolean side(String token) {
        return switch (token) {
            case "LEFT" -> true;
            case "RIGHT" -> false;
            default -> throw CommandException.syntax();
        };
    }

    /**
     * Parses a blocking timeout (seconds, may be fractional; {@code 0} = forever).
     *
     * @param bytes the timeout argument
     * @return the timeout in seconds
     */
    private static double parseTimeout(byte[] bytes) {
        double timeout;
        try {
            timeout = Keyspaces.parseDouble(bytes);
        } catch (CommandException e) {
            throw new CommandException("ERR timeout is not a float or out of range");
        }
        if (Double.isNaN(timeout) || timeout < 0) {
            throw new CommandException("ERR timeout is negative");
        }
        if (Double.isInfinite(timeout)) {
            throw new CommandException("ERR timeout is out of range");
        }
        return timeout;
    }
}
