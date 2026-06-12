package dev.jediscore.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for the server request path: multibulk and inline commands. */
class RespRequestParserTest {

    private static ByteBuf buf(String s) {
        return Unpooled.copiedBuffer(s, StandardCharsets.UTF_8);
    }

    private static String[] strings(byte[][] args) {
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = new String(args[i], StandardCharsets.UTF_8);
        }
        return out;
    }

    @Test
    void parsesMultibulkRequest() {
        byte[][] args = RespParser.parseRequest(buf("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n"));
        assertThat(strings(args)).containsExactly("SET", "k", "v");
    }

    @Test
    void parsesBinarySafeBulk() {
        // A value containing CR, LF and a NUL must survive intact.
        byte[][] args = RespParser.parseRequest(
                Unpooled.wrappedBuffer("*1\r\n$3\r\n".getBytes(StandardCharsets.US_ASCII),
                        new byte[] {'\r', '\n', 0},
                        "\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertThat(args.length).isEqualTo(1);
        assertThat(args[0]).containsExactly('\r', '\n', 0);
    }

    @Test
    void emptyMultibulkIsANoOp() {
        assertThat(RespParser.parseRequest(buf("*0\r\n"))).isEmpty();
        assertThat(RespParser.parseRequest(buf("*-1\r\n"))).isEmpty();
    }

    @Test
    void parsesInlineCommand() {
        byte[][] args = RespParser.parseRequest(buf("PING hello world\r\n"));
        assertThat(strings(args)).containsExactly("PING", "hello", "world");
    }

    @Test
    void inlineWithBareNewlineIsTolerated() {
        byte[][] args = RespParser.parseRequest(buf("PING\n"));
        assertThat(strings(args)).containsExactly("PING");
    }

    @Test
    void blankInlineLineIsANoOp() {
        assertThat(RespParser.parseRequest(buf("\r\n"))).isEmpty();
    }

    @Test
    void inlineRespectsDoubleQuotes() {
        byte[][] args = RespParser.parseRequest(buf("SET key \"hello world\"\r\n"));
        assertThat(strings(args)).containsExactly("SET", "key", "hello world");
    }

    @Test
    void inlineHandlesHexEscapesInDoubleQuotes() {
        byte[][] args = RespParser.parseRequest(buf("SET k \"a\\x00b\"\r\n"));
        assertThat(args.length).isEqualTo(3);
        assertThat(args[2]).containsExactly('a', 0, 'b');
    }

    @Test
    void inlineRespectsSingleQuotes() {
        byte[][] args = RespParser.parseRequest(buf("ECHO 'hello world'\r\n"));
        assertThat(strings(args)).containsExactly("ECHO", "hello world");
    }

    @Test
    void inlineHandlesEscapedQuoteInSingleQuotes() {
        // Inside single quotes, only \' is an escape (for a literal quote).
        byte[][] args = RespParser.parseRequest(buf("ECHO 'a\\'b'\r\n"));
        assertThat(strings(args)).containsExactly("ECHO", "a'b");
    }

    @Test
    void splitInlineArgsDirectly() {
        assertThat(RespParser.splitInlineArgs("  a  b   c ")).isEqualTo(List.of("a", "b", "c"));
        assertThat(RespParser.splitInlineArgs("")).isEmpty();
        assertThat(RespParser.splitInlineArgs("\"unterminated")).isNull();
    }
}
