package dev.jediscore.replication;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.MasterLink;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespParser;
import dev.jediscore.protocol.RespValue;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The replica-side link to a master: connects out, performs the {@code PSYNC}
 * handshake, loads the master's RDB, then reads the replication stream and applies
 * each command.
 *
 * <p><strong>Threading.</strong> A single daemon thread owns the socket and parses
 * the stream. The RDB load and every applied command are <em>submitted to the
 * command thread</em>, so the single-writer invariant holds — the link thread never
 * touches the keyspace directly. The thread reconnects with a full resync if the
 * link drops (partial resync arrives in Phase 6C).
 */
public final class ReplicaLink implements MasterLink {

    private static final Logger log = LoggerFactory.getLogger(ReplicaLink.class);
    private static final long RECONNECT_DELAY_MS = 500;

    private final ServerContext server;
    private final AtomicLong epoch = new AtomicLong(); // bumps to retire the active link

    private volatile Thread thread;
    private volatile Socket socket;

    /**
     * Creates a link bound to the server context.
     *
     * @param server the server context
     */
    public ReplicaLink(ServerContext server) {
        this.server = server;
    }

    @Override
    public synchronized void connect(String host, int port) {
        disconnect();
        long myEpoch = epoch.incrementAndGet();
        Thread t = new Thread(() -> runLoop(host, port, myEpoch), "jedicore-replica-link");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public synchronized void disconnect() {
        epoch.incrementAndGet(); // retire any running loop
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing to unblock the reader
            }
        }
        thread = null;
    }

    // ---- the link loop -------------------------------------------------------

    private void runLoop(String host, int port, long myEpoch) {
        while (epoch.get() == myEpoch) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 5000);
                socket = s;
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();

                server.replication().setLinkStatus("connecting");
                long offset = handshake(in, out);
                server.replication().setLinkStatus("sync");
                byte[] rdb = readRdb(in);
                loadRdb(rdb);
                server.replication().setReplicaOffset(offset);
                server.replication().setLinkStatus("connected");
                sendAck(out, offset);
                log.info("Replica link to {}:{} synced at offset {}", host, port, offset);

                streamLoop(in, out, offset, myEpoch);
            } catch (IOException e) {
                if (epoch.get() == myEpoch) {
                    log.warn("Replica link to {}:{} lost: {}", host, port, e.getMessage());
                    server.replication().setLinkStatus("down");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                socket = null;
            }
            if (epoch.get() != myEpoch) {
                return;
            }
            sleep(RECONNECT_DELAY_MS); // back off, then retry with a full resync
        }
    }

    /** Performs PING / REPLCONF / PSYNC and returns the FULLRESYNC offset. */
    private long handshake(InputStream in, OutputStream out) throws IOException {
        send(out, "PING");
        expectLineStart(readReplyLine(in), "+");
        send(out, "REPLCONF", "listening-port", Integer.toString(server.config().port()));
        expectLineStart(readReplyLine(in), "+");
        send(out, "REPLCONF", "capa", "eof", "capa", "psync2");
        expectLineStart(readReplyLine(in), "+");
        send(out, "PSYNC", "?", "-1");
        String full = readReplyLine(in); // +FULLRESYNC <replid> <offset>
        if (!full.startsWith("+FULLRESYNC")) {
            throw new IOException("expected FULLRESYNC, got: " + full);
        }
        String[] parts = full.substring(1).split(" ");
        server.replication().setMasterReplIdSeen(parts[1]);
        return Long.parseLong(parts[2]);
    }

    private byte[] readRdb(InputStream in) throws IOException {
        String header = readReplyLine(in); // $<len>  OR  $EOF:<40-byte marker>
        if (!header.startsWith("$")) {
            throw new IOException("expected RDB bulk header, got: " + header);
        }
        if (header.startsWith("$EOF:")) {
            return readDisklessRdb(in, header.substring(5).getBytes(StandardCharsets.US_ASCII));
        }
        int len = Integer.parseInt(header.substring(1));
        byte[] rdb = in.readNBytes(len);
        if (rdb.length != len) {
            throw new IOException("short RDB: expected " + len + " got " + rdb.length);
        }
        return rdb;
    }

    /**
     * Reads a diskless (EOF-delimited) RDB: the payload runs until the random
     * marker reappears. Read byte-by-byte so we stop exactly at the marker and
     * never consume the command stream that follows.
     */
    private byte[] readDisklessRdb(InputStream in, byte[] marker) throws IOException {
        java.io.ByteArrayOutputStream rdb = new java.io.ByteArrayOutputStream();
        byte[] tail = new byte[marker.length];
        int tailLen = 0;
        int tailPos = 0;
        int b;
        while ((b = in.read()) != -1) {
            rdb.write(b);
            tail[tailPos] = (byte) b;
            tailPos = (tailPos + 1) % marker.length;
            if (tailLen < marker.length) {
                tailLen++;
            }
            if (tailLen == marker.length && tailMatches(tail, tailPos, marker)) {
                byte[] all = rdb.toByteArray();
                return java.util.Arrays.copyOf(all, all.length - marker.length); // strip the marker
            }
        }
        throw new IOException("master closed before the diskless RDB EOF marker");
    }

    /** Compares the ring buffer (oldest byte at {@code oldest}) against the marker. */
    private static boolean tailMatches(byte[] ring, int oldest, byte[] marker) {
        for (int i = 0; i < marker.length; i++) {
            if (ring[(oldest + i) % ring.length] != marker[i]) {
                return false;
            }
        }
        return true;
    }

    private void loadRdb(byte[] rdb) throws InterruptedException {
        CountDownLatch loaded = new CountDownLatch(1);
        server.executor().submit(() -> {
            try {
                server.persistence().loadReplicaRdb(rdb);
            } finally {
                loaded.countDown();
            }
        });
        loaded.await();
    }

    private void streamLoop(InputStream in, OutputStream out, long startOffset, long myEpoch)
            throws IOException {
        ClientConnection link = new ClientConnection(-1, "master-link", "master-link", true);
        link.markMasterLink();
        ByteBuf acc = Unpooled.buffer(8192);
        long offset = startOffset;
        try {
            byte[] chunk = new byte[8192];
            while (epoch.get() == myEpoch) {
                // Skip bare '\n' keepalives the master interleaves in the stream;
                // they count toward the replication offset, as in Redis.
                while (acc.isReadable() && acc.getByte(acc.readerIndex()) == '\n') {
                    acc.skipBytes(1);
                    offset++;
                    server.replication().setReplicaOffset(offset);
                }
                int before = acc.readerIndex();
                RespValue value = RespParser.parse(acc);
                if (value == null) {
                    int n = in.read(chunk);
                    if (n == -1) {
                        throw new IOException("master closed the stream");
                    }
                    acc.writeBytes(chunk, 0, n);
                    continue;
                }
                offset += acc.readerIndex() - before;
                server.replication().setReplicaOffset(offset);
                acc.discardReadBytes();
                applyOrAck(value, link, out, offset);
            }
        } finally {
            acc.release();
        }
    }

    private void applyOrAck(RespValue value, ClientConnection link, OutputStream out, long offset)
            throws IOException {
        if (!(value instanceof RespValue.Array array) || array.items().isEmpty()) {
            return; // inline pings / unexpected frames: counted, not applied
        }
        byte[][] command = toCommand(array);
        String name = new String(command[0], StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        if (name.equals("PING")) {
            return; // keepalive
        }
        if (name.equals("REPLCONF")) {
            if (command.length >= 2 && "GETACK".equalsIgnoreCase(new String(command[1], StandardCharsets.UTF_8))) {
                sendAck(out, offset);
            }
            return;
        }
        // Apply on the command thread, preserving the single-writer model.
        server.executor().submit(() ->
                server.dispatcher().dispatch(new CommandContext(server, link, command, false)));
    }

    // ---- wire helpers --------------------------------------------------------

    private static byte[][] toCommand(RespValue.Array array) {
        List<RespValue> items = array.items();
        byte[][] command = new byte[items.size()][];
        for (int i = 0; i < items.size(); i++) {
            command[i] = ((RespValue.BulkString) items.get(i)).data();
        }
        return command;
    }

    private static void send(OutputStream out, String... args) throws IOException {
        out.write(encode(args));
        out.flush();
    }

    private void sendAck(OutputStream out, long offset) throws IOException {
        send(out, "REPLCONF", "ACK", Long.toString(offset));
    }

    private static byte[] encode(String... args) {
        StringBuilder sb = new StringBuilder().append('*').append(args.length).append("\r\n");
        for (String a : args) {
            sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Reads the next non-empty reply line, skipping bare-'\n' keepalives. */
    private static String readReplyLine(InputStream in) throws IOException {
        String line;
        do {
            line = readLine(in);
        } while (line.isEmpty());
        return line;
    }

    /** Reads a line terminated by '\n' (or '\r\n'); returns it without the terminator. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                    sb.setLength(sb.length() - 1); // drop a trailing CR
                }
                return sb.toString();
            }
            sb.append((char) b);
        }
        throw new IOException("master closed during handshake");
    }

    private static void expectLineStart(String line, String prefix) throws IOException {
        if (line == null || !line.startsWith(prefix)) {
            throw new IOException("unexpected handshake reply: " + line);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
