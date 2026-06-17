package dev.jediscore.commands;

import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.engine.Persistence;
import dev.jediscore.engine.ReplicationManager;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.engine.ServerStats;
import dev.jediscore.protocol.RespValue;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Server-introspection commands. {@code INFO} reports live metrics across the
 * Server, Clients, Memory, Persistence, Stats, Replication, CPU, Cluster, and
 * Keyspace sections, backed by {@link ServerStats} and the live registries.
 */
public final class ServerCommands {

    private ServerCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the server-introspection commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("info", -1, ServerCommands::info));
        registry.register(CommandSpec.of("shutdown", -1, ServerCommands::shutdown));
    }

    /**
     * {@code SHUTDOWN [NOSAVE|SAVE]} — persists per the (possibly overridden) policy,
     * then terminates the standalone server. Embedded/test instances never exit the
     * JVM; they simply reply {@code +OK} after persisting.
     */
    private static RespValue shutdown(CommandContext ctx) {
        if (ctx.argCount() > 1) {
            String arg = ctx.argUpper(1);
            if (arg.equals("NOSAVE")) {
                ctx.server().setSaveOnShutdown(false);
            } else if (arg.equals("SAVE")) {
                ctx.server().setSaveOnShutdown(true);
            } else {
                throw new dev.jediscore.engine.CommandException("ERR syntax error");
            }
        }
        if (ctx.server().saveOnShutdown() && ctx.server().persistence() != null) {
            ctx.server().persistence().save();
        }
        if (ctx.server().isStandalone()) {
            System.exit(0); // the shutdown hook then releases resources
        }
        return RespValue.OK; // embedded mode: persisted, but the JVM keeps running
    }

    private static RespValue info(CommandContext ctx) {
        String section = ctx.argCount() > 1 ? ctx.argUpper(1) : "DEFAULT";
        boolean all = section.equals("DEFAULT") || section.equals("ALL") || section.equals("EVERYTHING");
        StringBuilder sb = new StringBuilder(1024);
        if (all || section.equals("SERVER")) {
            server(ctx, sb);
        }
        if (all || section.equals("CLIENTS")) {
            clients(ctx, sb);
        }
        if (all || section.equals("MEMORY")) {
            memory(ctx, sb);
        }
        if (all || section.equals("PERSISTENCE")) {
            persistence(ctx, sb);
        }
        if (all || section.equals("STATS")) {
            stats(ctx, sb);
        }
        if (all || section.equals("REPLICATION")) {
            replication(ctx, sb);
        }
        if (all || section.equals("CPU")) {
            cpu(sb);
        }
        if (all || section.equals("CLUSTER")) {
            sb.append("# Cluster\r\ncluster_enabled:0\r\n\r\n");
        }
        if (all || section.equals("KEYSPACE")) {
            keyspace(ctx, sb);
        }
        return new RespValue.BulkString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void server(CommandContext ctx, StringBuilder sb) {
        ServerConfig config = ctx.server().config();
        sb.append("# Server\r\n");
        line(sb, "redis_version", config.version());
        line(sb, "redis_mode", "standalone");
        line(sb, "os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        line(sb, "arch_bits", "64");
        line(sb, "multiplexing_api", "netty");
        line(sb, "process_id", Long.toString(ProcessHandle.current().pid()));
        line(sb, "run_id", config.runId());
        line(sb, "tcp_port", Integer.toString(config.port()));
        line(sb, "uptime_in_seconds", Long.toString(ctx.server().stats().uptimeSeconds()));
        line(sb, "uptime_in_days", Long.toString(ctx.server().stats().uptimeSeconds() / 86400));
        line(sb, "executable", System.getProperty("user.dir") + "/jediscore");
        line(sb, "config_file", "");
        sb.append("\r\n");
    }

    private static void clients(CommandContext ctx, StringBuilder sb) {
        sb.append("# Clients\r\n");
        line(sb, "connected_clients", Integer.toString(ctx.server().connectionCount()));
        line(sb, "cluster_connections", "0");
        line(sb, "maxclients", Integer.toString(ctx.server().maxClients()));
        line(sb, "blocked_clients", Integer.toString(ctx.server().blocking().blockedCount()));
        line(sb, "tracking_clients", "0");
        line(sb, "clients_in_timeout_table", "0");
        sb.append("\r\n");
    }

    private static void memory(CommandContext ctx, StringBuilder sb) {
        long used = ctx.server().usedMemory();
        Runtime rt = Runtime.getRuntime();
        long rss = rt.totalMemory() - rt.freeMemory(); // JVM heap in use ~ our RSS proxy
        long maxMemory = ctx.server().config().maxMemory();
        sb.append("# Memory\r\n");
        line(sb, "used_memory", Long.toString(used));
        line(sb, "used_memory_human", human(used));
        line(sb, "used_memory_rss", Long.toString(rss));
        line(sb, "used_memory_peak", Long.toString(used));
        line(sb, "maxmemory", Long.toString(maxMemory));
        line(sb, "maxmemory_human", human(maxMemory));
        line(sb, "maxmemory_policy", ctx.server().config().maxMemoryPolicy().configName());
        line(sb, "mem_fragmentation_ratio",
                used == 0 ? "0" : String.format(Locale.ROOT, "%.2f", (double) rss / used));
        line(sb, "mem_allocator", "jvm");
        sb.append("\r\n");
    }

    private static void persistence(CommandContext ctx, StringBuilder sb) {
        Persistence p = ctx.server().persistence();
        sb.append("# Persistence\r\n");
        line(sb, "loading", "0");
        line(sb, "rdb_changes_since_last_save", Long.toString(ctx.server().dirty()));
        line(sb, "rdb_bgsave_in_progress", bool(p != null && p.backgroundSaveInProgress()));
        line(sb, "rdb_last_save_time", Long.toString(p == null ? 0 : p.lastSaveSeconds()));
        line(sb, "rdb_last_bgsave_status", "ok");
        line(sb, "aof_enabled", bool(p != null && p.appendOnlyEnabled()));
        line(sb, "aof_rewrite_in_progress", bool(p != null && p.appendRewriteInProgress()));
        line(sb, "aof_last_bgrewrite_status", "ok");
        line(sb, "aof_last_write_status", "ok");
        sb.append("\r\n");
    }

    private static void stats(CommandContext ctx, StringBuilder sb) {
        ServerStats s = ctx.server().stats();
        ReplicationManager r = ctx.server().replication();
        sb.append("# Stats\r\n");
        line(sb, "total_connections_received", Long.toString(s.connectionsReceived()));
        line(sb, "total_commands_processed", Long.toString(s.commandsProcessed()));
        line(sb, "instantaneous_ops_per_sec", Long.toString(s.instantaneousOps()));
        line(sb, "total_net_input_bytes", "0");
        line(sb, "total_net_output_bytes", "0");
        line(sb, "rejected_connections", Long.toString(s.rejectedConnections()));
        line(sb, "sync_full", Long.toString(r.syncFullServed()));
        line(sb, "sync_partial_ok", Long.toString(r.syncPartialServed()));
        line(sb, "sync_partial_err", "0");
        line(sb, "expired_keys", Long.toString(s.expiredKeys()));
        line(sb, "evicted_keys", Long.toString(s.evictedKeys()));
        line(sb, "keyspace_hits", Long.toString(s.keyspaceHits()));
        line(sb, "keyspace_misses", Long.toString(s.keyspaceMisses()));
        line(sb, "pubsub_channels", Integer.toString(ctx.server().pubsub().channelCount()));
        line(sb, "pubsub_patterns", Integer.toString(ctx.server().pubsub().patternCount()));
        line(sb, "total_forks", "0");
        sb.append("\r\n");
    }

    private static void replication(CommandContext ctx, StringBuilder sb) {
        ReplicationManager r = ctx.server().replication();
        sb.append("# Replication\r\n");
        if (r.isReplica()) {
            line(sb, "role", "slave");
            line(sb, "master_host", r.masterHost() == null ? "" : r.masterHost());
            line(sb, "master_port", Integer.toString(r.masterPort()));
            line(sb, "master_link_status", "connected".equals(r.linkStatus()) ? "up" : "down");
            line(sb, "master_sync_in_progress", bool("sync".equals(r.linkStatus())));
            line(sb, "slave_read_only", "1");
            line(sb, "slave_repl_offset", Long.toString(r.replicaOffset()));
        } else {
            line(sb, "role", "master");
        }
        line(sb, "connected_slaves", Integer.toString(r.replicaCount()));
        int i = 0;
        for (ReplicationManager.Replica replica : r.replicas()) {
            line(sb, "slave" + (i++),
                    "ip=" + replica.connection().remoteAddress().split(":")[0]
                            + ",port=" + replica.listeningPort()
                            + ",state=online,offset=" + replica.ackOffset() + ",lag=0");
        }
        line(sb, "master_failover_state", "no-failover");
        line(sb, "master_replid", r.replId());
        line(sb, "master_replid2", "0000000000000000000000000000000000000000");
        line(sb, "master_repl_offset", Long.toString(r.masterReplOffset()));
        line(sb, "second_repl_offset", "-1");
        line(sb, "repl_backlog_active", bool(r.masterReplOffset() > 0));
        line(sb, "repl_backlog_size", "1048576");
        line(sb, "repl_backlog_first_byte_offset", "0");
        line(sb, "repl_backlog_histlen", Long.toString(r.masterReplOffset()));
        sb.append("\r\n");
    }

    private static void cpu(StringBuilder sb) {
        sb.append("# CPU\r\n");
        double cpuSeconds = 0;
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long nanos = sun.getProcessCpuTime();
                if (nanos >= 0) {
                    cpuSeconds = nanos / 1_000_000_000.0;
                }
            }
        } catch (RuntimeException ignored) {
            // CPU metrics are best-effort; not all JVMs expose process CPU time.
        }
        // The JVM reports total process CPU; we attribute it to user time and leave
        // sys at 0 (documented approximation — we cannot split user/sys portably).
        line(sb, "used_cpu_sys", "0.000000");
        line(sb, "used_cpu_user", String.format(Locale.ROOT, "%.6f", cpuSeconds));
        sb.append("\r\n");
    }

    private static void keyspace(CommandContext ctx, StringBuilder sb) {
        sb.append("# Keyspace\r\n");
        for (int i = 0; i < ctx.server().databaseCount(); i++) {
            Database db = ctx.server().database(i);
            if (db.size() > 0) {
                line(sb, "db" + i, "keys=" + db.size() + ",expires=" + db.volatileCount() + ",avg_ttl=0");
            }
        }
        sb.append("\r\n");
    }

    // ---- helpers -------------------------------------------------------------

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append(':').append(value).append("\r\n");
    }

    private static String bool(boolean b) {
        return b ? "1" : "0";
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.2fK", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.2fM", mb);
        }
        return String.format(Locale.ROOT, "%.2fG", mb / 1024.0);
    }
}
