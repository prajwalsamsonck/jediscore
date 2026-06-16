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
        registry.register(CommandSpec.of("sentinel", -1, ReplicationCommands::sentinel));
        registry.register(CommandSpec.of("failover", -1, ReplicationCommands::failover));
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
        ReplicationManager replication = server.replication();
        ClientConnection conn = ctx.connection();
        boolean psync = "psync".equalsIgnoreCase(ctx.argText(0));

        // Partial resync: PSYNC <replid> <offset>. The wire offset is the next byte
        // the replica wants (1-based), so the boundary it already has is offset - 1.
        if (psync && ctx.argCount() >= 3 && !"?".equals(ctx.argText(1))) {
            long wireOffset;
            try {
                wireOffset = Long.parseLong(ctx.argText(2));
            } catch (NumberFormatException e) {
                wireOffset = -1;
            }
            long boundary = wireOffset - 1;
            if (wireOffset > 0 && replication.canPartialResync(ctx.argText(1), boundary)) {
                conn.deliver(RespValue.simple("CONTINUE " + replication.replId()));
                byte[] missing = replication.backlogSince(boundary);
                if (missing.length > 0) {
                    conn.deliver(new RespValue.Raw(missing));
                }
                replication.attachReplica(conn, conn.replicaListeningPort());
                return null;
            }
        }

        // Full resync.
        Persistence persistence = server.persistence();
        if (persistence == null) {
            throw new CommandException("ERR replication unavailable: no persistence backend");
        }
        long offset = replication.masterReplOffset();
        if (psync) {
            conn.deliver(RespValue.simple("FULLRESYNC " + replication.replId() + " " + offset));
        }
        // The RDB as a bulk header with no trailing CRLF, followed by the payload.
        byte[] rdb = persistence.dumpRdb();
        ByteArrayOutputStream preamble = new ByteArrayOutputStream();
        preamble.writeBytes(("$" + rdb.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        preamble.writeBytes(rdb);
        conn.deliver(new RespValue.Raw(preamble.toByteArray()));
        // Register as a replica so subsequent writes stream to it.
        replication.attachReplica(conn, conn.replicaListeningPort());
        return null; // all output already delivered out-of-band
    }

    // ---- ROLE ----------------------------------------------------------------

    private static RespValue role(CommandContext ctx) {
        ReplicationManager replication = ctx.server().replication();
        if (replication.isReplica()) {
            return new RespValue.Array(List.of(
                    RespValue.bulk("slave"),
                    RespValue.bulk(replication.masterHost() == null ? "" : replication.masterHost()),
                    RespValue.integer(replication.masterPort()),
                    RespValue.bulk(replicaState(replication.linkStatus())),
                    RespValue.integer(replication.replicaOffset())));
        }
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

    /** Maps the link status to the ROLE state token Redis uses. */
    private static String replicaState(String linkStatus) {
        return switch (linkStatus) {
            case "connected" -> "connected";
            case "sync" -> "sync";
            default -> "connect";
        };
    }

    private static void appendReplication(CommandContext ctx, StringBuilder sb) {
        ReplicationManager r = ctx.server().replication();
        sb.append("# Replication\r\n");
        if (r.isReplica()) {
            sb.append("role:slave\r\n");
            sb.append("master_host:").append(r.masterHost() == null ? "" : r.masterHost()).append("\r\n");
            sb.append("master_port:").append(r.masterPort()).append("\r\n");
            sb.append("master_link_status:").append("connected".equals(r.linkStatus()) ? "up" : "down").append("\r\n");
            sb.append("master_sync_in_progress:").append("sync".equals(r.linkStatus()) ? 1 : 0).append("\r\n");
            sb.append("slave_read_only:1\r\n");
            sb.append("slave_repl_offset:").append(r.replicaOffset()).append("\r\n");
        } else {
            sb.append("role:master\r\n");
        }
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
        ServerContext server = ctx.server();
        if (host.equalsIgnoreCase("no") && port.equalsIgnoreCase("one")) {
            server.replication().becomeMaster();
            if (server.masterLink() != null) {
                server.masterLink().disconnect();
            }
            return RespValue.OK;
        }
        int p;
        try {
            p = (int) Keyspaces.parseLong(port);
        } catch (CommandException e) {
            throw new CommandException("ERR Invalid master port");
        }
        if (server.masterLink() == null) {
            throw new CommandException("ERR replica mode is not available on this server");
        }
        server.replication().becomeReplica(host, p);
        server.masterLink().connect(host, p);
        return RespValue.OK;
    }

    // ---- Sentinel / failover (documented stretch — not implemented) ----------

    private static RespValue sentinel(CommandContext ctx) {
        // Automatic failover (Sentinel) is documented as a design in ARCHITECTURE.md
        // but not implemented; manual failover is REPLICAOF NO ONE on the chosen
        // replica plus REPLICAOF <new-master> on the others.
        throw new CommandException(
                "ERR Sentinel is not implemented; see the failover design in ARCHITECTURE.md. "
                        + "Promote manually with REPLICAOF NO ONE.");
    }

    private static RespValue failover(CommandContext ctx) {
        throw new CommandException(
                "ERR FAILOVER (coordinated failover) is not implemented; "
                        + "promote a replica manually with REPLICAOF NO ONE.");
    }
}
