package dev.jediscore.commands;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactions: {@code MULTI}, {@code EXEC}, {@code DISCARD}, {@code WATCH},
 * {@code UNWATCH}.
 *
 * <p>The dispatcher does the queuing: once {@code MULTI} sets the connection's
 * transaction flag, every non-control command is validated and appended to the
 * queue with a {@code +QUEUED} reply. {@code EXEC} here drives the rest of the
 * optimistic-locking protocol:
 *
 * <ul>
 *   <li>a queue-time error (unknown command / bad arity) ⇒ {@code -EXECABORT};</li>
 *   <li>a watched key modified since {@code WATCH} ⇒ a nil array (CAS failure);</li>
 *   <li>otherwise the queued commands run in order, each through the normal
 *       dispatch path, and their replies are returned as an array.</li>
 * </ul>
 */
public final class TransactionCommands {

    private TransactionCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the transaction commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("multi", 1, TransactionCommands::multi));
        registry.register(CommandSpec.of("exec", 1, TransactionCommands::exec));
        registry.register(CommandSpec.of("discard", 1, TransactionCommands::discard));
        registry.register(CommandSpec.of("watch", -2, TransactionCommands::watch));
        registry.register(CommandSpec.of("unwatch", 1, TransactionCommands::unwatch));
    }

    private static RespValue multi(CommandContext ctx) {
        ClientConnection conn = ctx.connection();
        if (conn.inMulti()) {
            throw new CommandException("ERR MULTI calls can not be nested");
        }
        conn.beginMulti();
        return RespValue.OK;
    }

    private static RespValue discard(CommandContext ctx) {
        ClientConnection conn = ctx.connection();
        if (!conn.inMulti()) {
            throw new CommandException("ERR DISCARD without MULTI");
        }
        conn.clearMulti();
        ctx.server().watchTable().unwatchAll(conn);
        return RespValue.OK;
    }

    private static RespValue watch(CommandContext ctx) {
        ClientConnection conn = ctx.connection();
        if (conn.inMulti()) {
            throw new CommandException("ERR WATCH inside MULTI is not allowed");
        }
        for (int i = 1; i < ctx.argCount(); i++) {
            ctx.server().watchTable().watch(conn, conn.db(), ctx.arg(i));
        }
        return RespValue.OK;
    }

    private static RespValue unwatch(CommandContext ctx) {
        ctx.server().watchTable().unwatchAll(ctx.connection());
        return RespValue.OK;
    }

    private static RespValue exec(CommandContext ctx) {
        ServerContext server = ctx.server();
        ClientConnection conn = ctx.connection();
        if (!conn.inMulti()) {
            throw new CommandException("ERR EXEC without MULTI");
        }

        // Snapshot the transaction's outcome flags, then end the transaction and
        // release the watches before running anything (EXEC always unwatches).
        boolean aborted = conn.hasTransactionError();
        boolean casFailed = conn.isCasDirty();
        List<byte[][]> queued = new ArrayList<>(conn.queuedCommands());
        conn.clearMulti();
        server.watchTable().unwatchAll(conn);

        if (aborted) {
            throw new CommandException("EXECABORT Transaction discarded because of previous errors.");
        }
        if (casFailed) {
            return RespValue.NULL_ARRAY; // a watched key changed: the CAS nil-array reply
        }

        List<RespValue> replies = new ArrayList<>(queued.size());
        for (byte[][] args : queued) {
            // Replay through the dispatcher; the transaction flag is already
            // cleared, so each command takes the normal (non-queueing) path and
            // its write-tracking / CAS / AOF side effects run as usual. Blocking is
            // disallowed: a BLPOP inside a transaction must not park — it returns
            // its timeout reply if it cannot be satisfied immediately.
            replies.add(server.dispatcher().dispatch(new CommandContext(server, conn, args, false)));
        }
        return new RespValue.Array(replies);
    }
}
