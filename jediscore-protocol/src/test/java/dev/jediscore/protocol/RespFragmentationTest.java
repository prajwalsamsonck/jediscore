package dev.jediscore.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Parsers must be resilient to TCP fragmentation: a frame may arrive split
 * across any number of reads, even one byte at a time. Until the final byte the
 * parser reports "incomplete" ({@code null}) and consumes nothing; on the last
 * byte it yields the whole frame.
 */
class RespFragmentationTest {

    @Test
    void requestParsedWhenFedOneByteAtATime() {
        byte[] frame = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nhello\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        ByteBuf acc = Unpooled.buffer();
        try {
            byte[][] result = null;
            for (int i = 0; i < frame.length; i++) {
                acc.writeByte(frame[i]);
                result = RespParser.parseRequest(acc);
                if (i < frame.length - 1) {
                    assertThat(result).as("incomplete after %d/%d bytes", i + 1, frame.length).isNull();
                }
            }
            assertThat(result).isNotNull();
            assertThat(result.length).isEqualTo(3);
            assertThat(new String(result[0], StandardCharsets.UTF_8)).isEqualTo("SET");
            assertThat(new String(result[1], StandardCharsets.UTF_8)).isEqualTo("key");
            assertThat(new String(result[2], StandardCharsets.UTF_8)).isEqualTo("hello");
        } finally {
            acc.release();
        }
    }

    @Test
    void generalValueParsedWhenFedOneByteAtATime() {
        byte[] frame = "*2\r\n:42\r\n$2\r\nhi\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuf acc = Unpooled.buffer();
        try {
            RespValue value = null;
            for (int i = 0; i < frame.length; i++) {
                acc.writeByte(frame[i]);
                value = RespParser.parse(acc);
                if (i < frame.length - 1) {
                    assertThat(value).isNull();
                }
            }
            assertThat(value).isInstanceOf(RespValue.Array.class);
            RespValue.Array array = (RespValue.Array) value;
            assertThat(array.items()).containsExactly(new RespValue.Integer(42), RespValue.bulk("hi"));
        } finally {
            acc.release();
        }
    }

    @Test
    void twoPipelinedRequestsDrainOneByOne() {
        byte[] frame = "*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        ByteBuf acc = Unpooled.wrappedBuffer(frame);
        try {
            byte[][] first = RespParser.parseRequest(acc);
            assertThat(first.length).isEqualTo(1);
            assertThat(new String(first[0], StandardCharsets.UTF_8)).isEqualTo("PING");
            byte[][] second = RespParser.parseRequest(acc);
            assertThat(second.length).isEqualTo(2);
            assertThat(new String(second[0], StandardCharsets.UTF_8)).isEqualTo("ECHO");
            assertThat(RespParser.parseRequest(acc)).isNull();
        } finally {
            acc.release();
        }
    }
}
