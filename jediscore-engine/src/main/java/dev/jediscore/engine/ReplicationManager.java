package dev.jediscore.engine;

import dev.jediscore.protocol.RespValue;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Master-side replication state: the replication ID and offset, the set of
 * attached replicas, and the command-propagation stream.
 *
 * <p><strong>Threading.</strong> Confined to the command thread, like the other
 * registries — replicas are attached during {@code PSYNC}, commands propagate
 * after each write, ACKs arrive as {@code REPLCONF ACK}, and disconnect cleanup is
 * submitted to the command thread. So no locking is needed.
 *
 * <p>The replication stream is a single shared byte sequence: each propagated
 * command is encoded once, the {@code master_repl_offset} advances by its length,
 * it is appended to the backlog, and the same bytes are pushed to every replica.
 * A {@code SELECT} is injected whenever the target database changes; attaching a
 * replica resets that state so the first command re-selects, exactly as Redis
 * does.
 */
public final class ReplicationManager {

    private static final byte[] SELECT = "SELECT".getBytes(StandardCharsets.UTF_8);

    /** One attached replica and the offset it has acknowledged. */
    public static final class Replica {
        private final ClientConnection connection;
        private int listeningPort;
        private long ackOffset;

        Replica(ClientConnection connection) {
            this.connection = connection;
        }

        /** @return the replica's connection */
        public ClientConnection connection() {
            return connection;
        }

        /** @return the replica's announced listening port (0 if not announced) */
        public int listeningPort() {
            return listeningPort;
        }

        /** @return the highest replication offset the replica has acknowledged */
        public long ackOffset() {
            return ackOffset;
        }
    }

    private final String replId;
    private final ReplicationBacklog backlog = new ReplicationBacklog();
    private final List<Replica> replicas = new ArrayList<>();
    private long masterReplOffset;
    private int streamSelectedDb = -1; // forces a SELECT before the first command
    private boolean active;            // true once any replica has ever attached

    /**
     * Creates a manager with a fresh replication ID derived from the run id.
     *
     * @param runId the server run id (a 40-char hex string)
     */
    public ReplicationManager(String runId) {
        this.replId = (runId != null && runId.length() == 40) ? runId : randomHex();
    }

    /** @return the replication ID (40-char hex) */
    public String replId() {
        return replId;
    }

    /** @return the current master replication offset */
    public long masterReplOffset() {
        return masterReplOffset;
    }

    /** @return the number of attached replicas */
    public int replicaCount() {
        return replicas.size();
    }

    /** @return the attached replicas (live view; command-thread only) */
    public List<Replica> replicas() {
        return replicas;
    }

    /**
     * Attaches a replica at the current offset (called when answering {@code
     * PSYNC} with {@code FULLRESYNC}). Resets the stream's selected-db state so the
     * next propagated command re-selects, guarding a freshly synced replica that
     * assumes db 0.
     *
     * @param connection    the replica connection
     * @param listeningPort the replica's announced listening port (0 if unknown)
     * @return the replica handle, registered at the current master offset
     */
    public Replica attachReplica(ClientConnection connection, int listeningPort) {
        Replica replica = new Replica(connection);
        replica.listeningPort = listeningPort;
        replica.ackOffset = masterReplOffset;
        connection.markReplica();
        replicas.add(replica);
        streamSelectedDb = -1;
        active = true; // from now on the backlog/offset advance, even across reconnects
        return replica;
    }

    /**
     * Removes a replica (on disconnect).
     *
     * @param connection the replica connection
     */
    public void removeReplica(ClientConnection connection) {
        replicas.removeIf(r -> r.connection == connection);
    }

    /**
     * Records a replica's acknowledged offset (from {@code REPLCONF ACK}).
     *
     * @param connection the replica connection
     * @param offset     the offset it has applied
     */
    public void acknowledge(ClientConnection connection, long offset) {
        for (Replica replica : replicas) {
            if (replica.connection == connection) {
                replica.ackOffset = Math.max(replica.ackOffset, offset);
                return;
            }
        }
    }

    /**
     * Counts replicas that have acknowledged at least the given offset.
     *
     * @param offset the target offset
     * @return the number of replicas caught up to {@code offset}
     */
    public int replicasAckedAtLeast(long offset) {
        int count = 0;
        for (Replica replica : replicas) {
            if (replica.ackOffset >= offset) {
                count++;
            }
        }
        return count;
    }

    /**
     * Propagates a write command to the replication stream: injects {@code SELECT}
     * on a database change, advances the offset, appends to the backlog, and pushes
     * the bytes to every replica.
     *
     * @param db   the database the command targeted
     * @param args the (already rewritten, deterministic) command argument vector
     */
    public void propagate(int db, byte[][] args) {
        if (!active) {
            // No replica has ever connected: nothing to stream and no backlog to
            // maintain, so a non-replicated server pays nothing here.
            return;
        }
        if (replicas.isEmpty()) {
            // A replica has disconnected but may reconnect for a partial resync, so
            // keep advancing the offset/backlog.
            if (db != streamSelectedDb) {
                feed(encodeCommand(new byte[][]{SELECT, Integer.toString(db).getBytes(StandardCharsets.UTF_8)}));
                streamSelectedDb = db;
            }
            feed(encodeCommand(args));
            return;
        }
        if (db != streamSelectedDb) {
            byte[] select = encodeCommand(new byte[][]{
                    SELECT, Integer.toString(db).getBytes(StandardCharsets.UTF_8)});
            send(select);
            streamSelectedDb = db;
        }
        send(encodeCommand(args));
    }

    /**
     * Sends a raw, already-encoded chunk to every replica without advancing the
     * offset by the {@code SELECT}/command path — used for {@code REPLCONF GETACK},
     * which must itself count toward the offset.
     *
     * @param command the command argument vector
     */
    public void propagateRaw(byte[][] command) {
        send(encodeCommand(command));
    }

    private void send(byte[] bytes) {
        backlog.append(bytes);
        masterReplOffset += bytes.length;
        RespValue frame = new RespValue.Raw(bytes);
        for (Replica replica : replicas) {
            replica.connection.deliver(frame);
        }
    }

    private void feed(byte[] bytes) {
        backlog.append(bytes);
        masterReplOffset += bytes.length;
    }

    /** Encodes a command as a RESP multibulk frame. */
    private static byte[] encodeCommand(byte[][] args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "*" + args.length + "\r\n");
        for (byte[] arg : args) {
            writeAscii(out, "$" + arg.length + "\r\n");
            out.writeBytes(arg);
            writeAscii(out, "\r\n");
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String randomHex() {
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            sb.append(Character.forDigit(ThreadLocalRandom.current().nextInt(16), 16));
        }
        return sb.toString();
    }
}
