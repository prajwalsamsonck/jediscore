package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test over a real TCP socket against an in-process JediCore server.
 *
 * <p>It speaks RESP on the wire and decodes replies with the project's own
 * parser, so it validates the whole network → decode → dispatch → encode path,
 * including RESP2/RESP3 negotiation, inline commands, pipelining, and QUIT.
 */
class JediCoreSocketIntegrationTest {

    private static JediCore server;
    private static int port;

    @BeforeAll
    static void startServer() throws InterruptedException {
        server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
        port = server.port();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /** A connected client that can send RESP frames and read decoded replies. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;
        private final ByteBuf acc = Unpooled.buffer();

        Client() throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(3000);
            out = socket.getOutputStream();
            in = socket.getInputStream();
        }

        void send(String raw) throws IOException {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        RespValue read() throws IOException {
            while (true) {
                RespValue v = RespParser.parse(acc);
                if (v != null) {
                    return v;
                }
                int b = in.read();
                if (b == -1) {
                    return null;
                }
                acc.writeByte(b);
            }
        }

        int rawRead() throws IOException {
            return in.read();
        }

        @Override
        public void close() throws IOException {
            acc.release();
            socket.close();
        }
    }

    private static String multibulk(String... parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            sb.append('$').append(p.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
    }

    @Test
    void pingPongAndEcho() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("PING"));
            assertThat(c.read()).isEqualTo(RespValue.simple("PONG"));

            c.send(multibulk("PING", "hello"));
            assertThat(c.read()).isEqualTo(RespValue.bulk("hello"));

            c.send(multibulk("ECHO", "world"));
            assertThat(c.read()).isEqualTo(RespValue.bulk("world"));
        }
    }

    @Test
    void inlineCommandWorks() throws IOException {
        try (Client c = new Client()) {
            c.send("PING\r\n");
            assertThat(c.read()).isEqualTo(RespValue.simple("PONG"));
        }
    }

    @Test
    void helloDefaultsToResp2FlatArray() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("HELLO"));
            RespValue reply = c.read();
            assertThat(reply).isInstanceOf(RespValue.Array.class);
            List<RespValue> items = ((RespValue.Array) reply).items();
            assertThat(items).hasSize(14); // 7 map entries flattened
            assertThat(items.get(4)).isEqualTo(RespValue.bulk("proto"));
            assertThat(items.get(5)).isEqualTo(RespValue.integer(2));
        }
    }

    @Test
    void hello3SwitchesToResp3Map() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("HELLO", "3"));
            RespValue reply = c.read();
            assertThat(reply).isInstanceOf(RespValue.Map.class);
            RespValue proto = findValue((RespValue.Map) reply, "proto");
            assertThat(proto).isEqualTo(RespValue.integer(3));
        }
    }

    @Test
    void unknownCommandReturnsError() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("NOPE", "x"));
            RespValue reply = c.read();
            assertThat(reply).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) reply).message()).startsWith("ERR unknown command 'NOPE'");
        }
    }

    @Test
    void wrongArityReturnsError() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("ECHO")); // needs 1 argument
            RespValue reply = c.read();
            assertThat(((RespValue.SimpleError) reply).message())
                    .isEqualTo("ERR wrong number of arguments for 'echo' command");
        }
    }

    @Test
    void pipelinedRequestsRepliedInOrder() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("PING") + multibulk("ECHO", "a") + multibulk("PING", "b"));
            assertThat(c.read()).isEqualTo(RespValue.simple("PONG"));
            assertThat(c.read()).isEqualTo(RespValue.bulk("a"));
            assertThat(c.read()).isEqualTo(RespValue.bulk("b"));
        }
    }

    @Test
    void clientSetNameAndGetName() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("CLIENT", "SETNAME", "worker-1"));
            assertThat(c.read()).isEqualTo(RespValue.simple("OK"));
            c.send(multibulk("CLIENT", "GETNAME"));
            assertThat(c.read()).isEqualTo(RespValue.bulk("worker-1"));
            c.send(multibulk("CLIENT", "SETNAME", "bad name"));
            assertThat(((RespValue.SimpleError) c.read()).message()).contains("cannot contain spaces");
        }
    }

    @Test
    void quitClosesConnectionAfterOk() throws IOException {
        try (Client c = new Client()) {
            c.send(multibulk("QUIT"));
            assertThat(c.read()).isEqualTo(RespValue.simple("OK"));
            // The server should close the socket after the OK is flushed.
            assertThat(c.rawRead()).isEqualTo(-1);
        }
    }

    private static RespValue findValue(RespValue.Map map, String key) {
        for (RespValue.MapEntry entry : map.entries()) {
            if (entry.key().equals(RespValue.bulk(key))) {
                return entry.value();
            }
        }
        return null;
    }
}
