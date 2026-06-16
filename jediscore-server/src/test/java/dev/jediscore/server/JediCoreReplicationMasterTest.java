package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Master-side replication: the PSYNC full-resync handshake, RDB streaming, live
 * command propagation with deterministic rewriting, REPLCONF/ACK, WAIT, and
 * INFO/ROLE. Exercised by a hand-rolled raw-socket replica so we can inspect the
 * exact bytes the master streams.
 */
class JediCoreReplicationMasterTest {

    private JediCore server;

    @BeforeEach
    void start() throws InterruptedException {
        server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private RespTestClient client() throws IOException {
        return new RespTestClient(server.port());
    }

    /** A minimal replica: does the handshake, then reads the RDB and the command stream. */
    private final class RawReplica implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        long syncOffset;
        String replId;
        boolean continued;

        RawReplica(int listeningPort) throws IOException {
            socket = connectSocket();
            in = socket.getInputStream();
            out = socket.getOutputStream();
            send("REPLCONF", "listening-port", Integer.toString(listeningPort));
            assertThat(readLine()).isEqualTo("+OK");
            send("PSYNC", "?", "-1");
            String full = readLine(); // +FULLRESYNC <replid> <offset>
            assertThat(full).startsWith("+FULLRESYNC ");
            String[] parts = full.substring(1).split(" ");
            assertThat(parts[1]).hasSize(40);
            replId = parts[1];
            syncOffset = Long.parseLong(parts[2]);
            readRdb();
        }

        /** Connects and requests a partial resync ({@code PSYNC <replid> <wireOffset>}). */
        RawReplica(int listeningPort, String partialReplId, long wireOffset) throws IOException {
            socket = connectSocket();
            in = socket.getInputStream();
            out = socket.getOutputStream();
            send("REPLCONF", "listening-port", Integer.toString(listeningPort));
            assertThat(readLine()).isEqualTo("+OK");
            send("PSYNC", partialReplId, Long.toString(wireOffset));
            String reply = readLine();
            continued = reply.startsWith("+CONTINUE");
            // CONTINUE carries the (possibly new) replid; no RDB follows.
        }

        private Socket connectSocket() throws IOException {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
            s.setSoTimeout(4000);
            return s;
        }

        void send(String... args) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (String a : args) {
                sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(a).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Reads a CRLF-terminated line (without the trailing CRLF). */
        private String readLine() throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int prev = -1;
            int b;
            while ((b = in.read()) != -1) {
                if (prev == '\r' && b == '\n') {
                    byte[] bytes = buf.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                }
                buf.write(b);
                prev = b;
            }
            throw new IOException("stream closed");
        }

        private void readRdb() throws IOException {
            String header = readLine(); // $<len>
            assertThat(header).startsWith("$");
            int len = Integer.parseInt(header.substring(1));
            byte[] rdb = in.readNBytes(len);
            assertThat(new String(rdb, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("REDIS");
        }

        /** Reads one propagated command as a list of UTF-8 strings. */
        List<String> readCommand() throws IOException {
            String header = readLine(); // *<n>
            assertThat(header).startsWith("*");
            int n = Integer.parseInt(header.substring(1));
            List<String> args = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String lenLine = readLine(); // $<len>
                int len = Integer.parseInt(lenLine.substring(1));
                byte[] data = in.readNBytes(len);
                readLine(); // trailing CRLF (already consumed by readLine on empty content)
                args.add(new String(data, StandardCharsets.UTF_8));
            }
            return args;
        }

        /** Reads commands until one that is not SELECT, returning it. */
        List<String> readCommandSkippingSelect() throws IOException {
            List<String> cmd = readCommand();
            while (cmd.get(0).equalsIgnoreCase("SELECT")) {
                cmd = readCommand();
            }
            return cmd;
        }

        void ackSyncOffset() throws IOException {
            send("REPLCONF", "ACK", Long.toString(syncOffset));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void fullResyncThenPropagatesWrites() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            c.call("SET", "foo", "bar");
            assertThat(replica.readCommandSkippingSelect()).containsExactly("SET", "foo", "bar");
        }
    }

    @Test
    void expirePropagatedAsPexpireat() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            c.call("SET", "k", "v");
            c.call("EXPIRE", "k", "100");
            assertThat(replica.readCommandSkippingSelect()).containsExactly("SET", "k", "v");
            List<String> expire = replica.readCommand();
            assertThat(expire.get(0)).isEqualTo("PEXPIREAT");
            assertThat(expire.get(1)).isEqualTo("k");
            // absolute ms timestamp, ~100s in the future
            long whenMs = Long.parseLong(expire.get(2));
            assertThat(whenMs).isGreaterThan(System.currentTimeMillis() + 90_000);
        }
    }

    @Test
    void spopPropagatedAsSrem() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            c.call("SADD", "s", "a", "b", "c");
            assertThat(replica.readCommandSkippingSelect().get(0)).isEqualTo("SADD");
            c.call("SPOP", "s");
            List<String> srem = replica.readCommand();
            assertThat(srem.get(0)).isEqualTo("SREM");
            assertThat(srem.get(1)).isEqualTo("s");
            assertThat(srem).hasSize(3); // SREM s <one-member>
        }
    }

    @Test
    void partialResyncContinuesFromTheBacklog() throws Exception {
        String replId;
        try (RawReplica first = new RawReplica(7001); RespTestClient c = client()) {
            replId = first.replId;
            // Generate some stream the backlog will retain.
            c.call("SET", "k1", "v1");
            c.call("SET", "k2", "v2");
            first.readCommandSkippingSelect(); // drain so the master has streamed it
        } // first replica disconnects

        // A replica that had synced at offset 0 reconnects asking to continue.
        try (RawReplica resumer = new RawReplica(7002, replId, 1)) { // wire offset 1 → boundary 0
            assertThat(resumer.continued).isTrue(); // +CONTINUE, not a full resync
            // It replays the retained backlog: SELECT 0, SET k1 v1, SET k2 v2.
            List<String> replayed = resumer.readCommandSkippingSelect();
            assertThat(replayed).containsExactly("SET", "k1", "v1");
            assertThat(resumer.readCommand()).containsExactly("SET", "k2", "v2");
        }
    }

    @Test
    void unknownReplidForcesFullResync() throws Exception {
        try (RawReplica r = new RawReplica(7003,
                "0000000000000000000000000000000000000000", 1)) {
            // Replid mismatch → the master must NOT partial-resync.
            assertThat(r.continued).isFalse();
        }
    }

    @Test
    void setexPropagatedAsAbsoluteSet() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            c.call("SETEX", "k", "100", "v");
            List<String> cmd = replica.readCommandSkippingSelect();
            assertThat(cmd.get(0)).isEqualTo("SET");
            assertThat(cmd.get(1)).isEqualTo("k");
            assertThat(cmd.get(2)).isEqualTo("v");
            assertThat(cmd.get(3)).isEqualTo("PXAT");
            assertThat(Long.parseLong(cmd.get(4))).isGreaterThan(System.currentTimeMillis() + 90_000);
        }
    }

    @Test
    void hincrbyfloatPropagatedAsHset() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            c.call("HINCRBYFLOAT", "h", "f", "3.14");
            List<String> cmd = replica.readCommandSkippingSelect();
            assertThat(cmd).containsExactly("HSET", "h", "f", "3.14");
        }
    }

    @Test
    void getexExpirePropagatedAsPexpireat() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            c.call("SET", "k", "v");
            assertThat(replica.readCommandSkippingSelect()).containsExactly("SET", "k", "v");
            c.call("GETEX", "k", "EX", "100");
            List<String> cmd = replica.readCommand();
            assertThat(cmd.get(0)).isEqualTo("PEXPIREAT");
            assertThat(cmd.get(1)).isEqualTo("k");
            assertThat(Long.parseLong(cmd.get(2))).isGreaterThan(System.currentTimeMillis() + 90_000);
        }
    }

    @Test
    void waitCountsAcknowledgedReplicas() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            replica.ackSyncOffset(); // ack the offset at which we synced
            // No writes since sync, so the WAIT target equals the synced offset.
            RespValue r = c.call("WAIT", "1", "1000");
            assertThat(r).isEqualTo(RespValue.integer(1));
        }
    }

    @Test
    void waitReturnsZeroWithNoReplicas() throws Exception {
        try (RespTestClient c = client()) {
            assertThat(c.call("WAIT", "0", "100")).isEqualTo(RespValue.integer(0));
        }
    }

    @Test
    void infoAndRoleReportMasterState() throws Exception {
        try (RawReplica replica = new RawReplica(7001); RespTestClient c = client()) {
            assertThat(replica.syncOffset).isGreaterThanOrEqualTo(0); // replica attached
            String info = new String(((RespValue.BulkString) c.call("INFO", "replication")).data(),
                    StandardCharsets.UTF_8);
            assertThat(info).contains("role:master");
            assertThat(info).contains("connected_slaves:1");
            assertThat(info).contains("slave0:");
            assertThat(info).contains("port=7001");
            assertThat(info).containsPattern("master_replid:[0-9a-f]{40}");

            RespValue role = c.call("ROLE");
            List<RespValue> r = ((RespValue.Array) role).items();
            assertThat(new String(((RespValue.BulkString) r.get(0)).data(), StandardCharsets.UTF_8))
                    .isEqualTo("master");
            assertThat(r.get(1)).isInstanceOf(RespValue.Integer.class);
            assertThat(((RespValue.Array) r.get(2)).items()).hasSize(1); // one replica
        }
    }
}
