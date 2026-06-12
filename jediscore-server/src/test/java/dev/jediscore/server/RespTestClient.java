package dev.jediscore.server;

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

/**
 * A minimal blocking RESP client for integration tests. It sends commands as
 * RESP multibulk frames and decodes replies with the project's own parser, so a
 * test exercises the full server stack end-to-end over a real socket.
 */
final class RespTestClient implements AutoCloseable {

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;
    private final ByteBuf acc = Unpooled.buffer();

    RespTestClient(int port) throws IOException {
        this("127.0.0.1", port);
    }

    RespTestClient(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 2000);
        socket.setSoTimeout(3000);
        out = socket.getOutputStream();
        in = socket.getInputStream();
    }

    /**
     * Sends a command and returns its decoded reply.
     *
     * @param args the command and its arguments
     * @return the decoded reply
     * @throws IOException on socket failure or premature close
     */
    RespValue call(String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String a : args) {
            byte[] bytes = a.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n").append(a).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        return read();
    }

    private RespValue read() throws IOException {
        while (true) {
            RespValue v = RespParser.parse(acc);
            if (v != null) {
                return v;
            }
            int b = in.read();
            if (b == -1) {
                throw new IOException("connection closed before a full reply was read");
            }
            acc.writeByte(b);
        }
    }

    @Override
    public void close() throws IOException {
        acc.release();
        socket.close();
    }
}
