package dev.jediscore.commands;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Persistence;
import dev.jediscore.engine.ReplicationManager;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Master-side replication commands: {@code PSYNC}, {@code REPLCONF}, {@code INFO}
 * (replication section), {@code ROLE}, and a {@code REPLICAOF}/{@code SLAVEOF}
 * stub (the replica engine arrives in Phase 6B).
 *
 * <p>{@code PSYNC} performs a full resync: it replies {@code +FULLRESYNC <replid>
 * <offset>}, streams the current keyspace as an RDB preamble + payload, then
 * registers the connection as a replica so every subsequent write is propagated to
 * it. All of this happens in one command-thread step, so no write can slip between
 * the snapshot and the live stream.
 */
public final class ReplicationCommands {

    private ReplicationCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the replication commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("replconf", -1, ReplicationCommands::replconf));
        registry.register(CommandSpec.of("psync", -1, ReplicationCommands::psync));
        registry.register(CommandSpec.of("sync", 1, ReplicationCommands::psync));
        registry.register(CommandSpec.of("info", -1, ReplicationCommands::info));
        registry.register(CommandSpec.of("role", 1, ReplicationCommands::role));
        registry.register(CommandSpec.of("replicaof", 3, ReplicationCommands::replicaof));
        registry.register(CommandSpec.of("slaveof", 3, ReplicationCommands::replicaof));
    }

    // ---- REPLCONF ------------------------------------------------------------

    private static RespValue replconf(CommandContext ctx) {
        if (ctx.argCount() < 2) {
            return RespValue.OK;
        }
        String sub = ctx.argUpper(1);
        switch (sub) {
            case "LISTENING-PORT" -> {
                ctx.connection().setReplicaListeningPort((int) Keyspaces.parseLong(ctx.arg(2)));
                return RespValue.OK;
            }
            case "ACK" -> {
                // A replica reporting its applied offset; no reply, then nudge WAIT.
                long offset = Keyspaces.parseLong(ctx.arg(2));
                ctx.server().replication().acknowledge(ctx.connection(), offset);
                ctx.server().blocking().signalAll();
                return null;
            }
            case "GETACK", "CAPA", "IP-ADDRESS", "VERSION" -> {
                return RespValue.OK;
            }
            default -> {
                return RespValue.OK; // be lenient with unknown REPLCONF options
            }
        }
    }

    // ---- PSYNC / SYNC --------------------------------------------------------

    private static RespValue psync(CommandContext ctx) {
        ServerContext server = ctx.server();
        Persistence persistence = server.persistence();
        if (persistence == null) {
            throw new CommandException("ERR replication unavailable: no persistence backend");
        }
        ReplicationManager replication = server.replication();
        ClientConnection conn = ctx.connection();
        boolean psync = "psync".equalsIgnoreCase(ctx.argText(0));

        long offset = replication.masterReplOffset();
        // 1) FULLRESYNC line (PSYNC only; legacy SYNC sends just the RDB).
        if (psync) {
            conn.deliver(RespValue.simple("FULLRESYNC " + replication.replId() + " " + offset));
        }
        // 2) The RDB as a bulk header with no trailing CRLF, followed by the payload.
        byte[] rdb = persistence.dumpRdb();
        ByteArrayOutputStream preamble = new ByteArrayOutputStream();
        preamble.writeBytes(("$" + rdb.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        preamble.writeBytes(rdb);
        conn.deliver(new RespValue.Raw(preamble.toByteArray()));
        // 3) Register as a replica so subsequent writes stream to it.
        replication.attachReplica(conn, conn.replicaListeningPort());
        return null; // all output already delivered out-of-band
    }

    // ---- ROLE ----------------------------------------------------------------

    private static RespValue role(CommandContext ctx) {
        ReplicationManager replication = ctx.server().replication();
        List<RespValue> slaves = new ArrayList<>();
        for (ReplicationManager.Replica replica : replication.replicas()) {
            slaves.add(new RespValue.Array(List.of(
                    RespValue.bulk(replica.connection().remoteAddress().split(":")[0]),
                    RespValue.bulk(Integer.toString(replica.listeningPort())),
                    RespValue.bulk(Long.toString(replica.ackOffset())))));
        }
        return new RespValue.Array(List.of(
                RespValue.bulk("master"),
                RespValue.integer(replication.masterReplOffset()),
                new RespValue.Array(slaves)));
    }

    // ---- INFO ----------------------------------------------------------------

    private static RespValue info(CommandContext ctx) {
        String section = ctx.argCount() > 1 ? ctx.argUpper(1) : "DEFAULT";
        boolean all = section.equals("DEFAULT") || section.equals("ALL") || section.equals("EVERYTHING");
        StringBuilder sb = new StringBuilder();
        if (all || section.equals("SERVER")) {
            sb.append("# Server\r\n");
            sb.append("redis_version:").append(ctx.server().config().version()).append("\r\n");
            sb.append("run_id:").append(ctx.server().config().runId()).append("\r\n");
            sb.append("tcp_port:").append(ctx.server().config().port()).append("\r\n");
            sb.append("\r\n");
        }
        if (all || section.equals("REPLICATION")) {
            appendReplication(ctx, sb);
        }
        return new RespValue.BulkString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendReplication(CommandContext ctx, StringBuilder sb) {
        ReplicationManager r = ctx.server().replication();
        sb.append("# Replication\r\n");
        sb.append("role:master\r\n");
        sb.append("connected_slaves:").append(r.replicaCount()).append("\r\n");
        int i = 0;
        for (ReplicationManager.Replica replica : r.replicas()) {
            sb.append("slave").append(i++).append(':')
                    .append("ip=").append(replica.connection().remoteAddress().split(":")[0])
                    .append(",port=").append(replica.listeningPort())
                    .append(",state=online")
                    .append(",offset=").append(replica.ackOffset())
                    .append(",lag=0\r\n");
        }
        sb.append("master_failover_state:no-failover\r\n");
        sb.append("master_replid:").append(r.replId()).append("\r\n");
        sb.append("master_replid2:0000000000000000000000000000000000000000\r\n");
        sb.append("master_repl_offset:").append(r.masterReplOffset()).append("\r\n");
        sb.append("second_repl_offset:-1\r\n");
        sb.append("repl_backlog_active:").append(r.masterReplOffset() > 0 ? 1 : 0).append("\r\n");
        sb.append("repl_backlog_size:1048576\r\n");
        sb.append("repl_backlog_first_byte_offset:0\r\n");
        sb.append("repl_backlog_histlen:").append(r.masterReplOffset()).append("\r\n");
        sb.append("\r\n");
    }

    // ---- REPLICAOF / SLAVEOF (stub) -----------------------------------------

    private static RespValue replicaof(CommandContext ctx) {
        String host = ctx.argText(1);
        String port = ctx.argText(2);
        if (host.equalsIgnoreCase("no") && port.equalsIgnoreCase("one")) {
            return RespValue.OK; // already a master; nothing to detach yet
        }
        // Becoming a replica of another master is implemented in Phase 6B.
        throw new CommandException(
                "ERR REPLICAOF <host> <port> is not yet supported (replica mode arrives in Phase 6B)");
    }
}
