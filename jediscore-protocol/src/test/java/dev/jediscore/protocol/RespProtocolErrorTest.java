package dev.jediscore.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Malformed input must raise {@link ProtocolException} (which the network layer
 * turns into {@code -ERR Protocol error: …}) rather than corrupting state or
 * throwing something unexpected.
 */
class RespProtocolErrorTest {

    private static ByteBuf buf(String s) {
        return Unpooled.copiedBuffer(s, StandardCharsets.US_ASCII);
    }

    @Test
    void unknownTypeByteIsRejected() {
        assertThatThrownBy(() -> RespParser.parse(buf("?nope\r\n")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("expected a RESP type byte");
    }

    @Test
    void nonNumericIntegerIsRejected() {
        assertThatThrownBy(() -> RespParser.parse(buf(":notanumber\r\n")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("invalid integer");
    }

    @Test
    void bulkBodyNotTerminatedByCrlfIsRejected() {
        // Length 1 but the byte after the single body byte is not CR.
        assertThatThrownBy(() -> RespParser.parse(buf("$1\r\nabX")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("CRLF");
    }

    @Test
    void invalidBooleanIsRejected() {
        assertThatThrownBy(() -> RespParser.parse(buf("#x\r\n")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("invalid boolean");
    }

    @Test
    void requestWithWrongElementMarkerIsRejected() {
        assertThatThrownBy(() -> RespParser.parseRequest(buf("*1\r\n+OK\r\n")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("expected '$'");
    }

    @Test
    void requestWithNonNumericMultibulkCountIsRejected() {
        assertThatThrownBy(() -> RespParser.parseRequest(buf("*abc\r\n")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("invalid multibulk length");
    }

    @Test
    void inlineUnbalancedQuotesAreRejected() {
        assertThatThrownBy(() -> RespParser.parseRequest(buf("\"foo\r\n")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unbalanced quotes");
    }

    @Test
    void oversizedMultibulkCountIsRejected() {
        assertThatThrownBy(() -> RespParser.parseRequest(buf("*99999999\r\n")))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("invalid multibulk length");
    }

    @Test
    void incompleteInputReturnsNullNotError() {
        // A half-delivered bulk string is "need more bytes", not malformed.
        assertThat(RespParser.parse(buf("$10\r\nabc"))).isNull();
        assertThat(RespParser.parseRequest(buf("*2\r\n$3\r\nGET\r\n"))).isNull();
    }
}
