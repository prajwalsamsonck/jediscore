package dev.jediscore.network;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.RespRequest;
import dev.jediscore.protocol.RespValue;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests the decoder inside a real (embedded) Netty pipeline, covering the
 * three behaviours that matter at this layer: decoding a request, surviving
 * fragmentation across reads, and turning a protocol violation into an error
 * reply followed by a channel close.
 */
class RespRequestDecoderTest {

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new RespRequestDecoder());
    }

    @Test
    void decodesAMultibulkRequest() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.copiedBuffer("*1\r\n$4\r\nPING\r\n", StandardCharsets.US_ASCII));
        RespRequest req = ch.readInbound();
        assertThat(req).isNotNull();
        assertThat(new String(req.args()[0], StandardCharsets.UTF_8)).isEqualTo("PING");
        Object none = ch.readInbound();
        assertThat(none).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void handlesFragmentedDelivery() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.copiedBuffer("*2\r\n$4\r\nECHO", StandardCharsets.US_ASCII));
        Object notYet = ch.readInbound();
        assertThat(notYet).as("not yet complete").isNull();
        ch.writeInbound(Unpooled.copiedBuffer("\r\n$2\r\nhi\r\n", StandardCharsets.US_ASCII));
        RespRequest req = ch.readInbound();
        assertThat(req).isNotNull();
        assertThat(req.args().length).isEqualTo(2);
        assertThat(new String(req.args()[1], StandardCharsets.UTF_8)).isEqualTo("hi");
        ch.finishAndReleaseAll();
    }

    @Test
    void protocolErrorWritesErrorAndClosesChannel() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.copiedBuffer("*1\r\n+OK\r\n", StandardCharsets.US_ASCII));
        Object out = ch.readOutbound();
        assertThat(out).isInstanceOf(RespValue.SimpleError.class);
        assertThat(((RespValue.SimpleError) out).message()).contains("Protocol error", "expected '$'");
        assertThat(ch.isOpen()).isFalse();
        ch.finishAndReleaseAll();
    }
}
