package dev.jediscore.network;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.protocol.RespEncoder;
import dev.jediscore.protocol.RespValue;
import dev.jediscore.protocol.RespVersion;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Encodes {@link RespValue} replies onto the wire at the connection's negotiated
 * protocol version.
 *
 * <p>Runs on the I/O thread. It reads the negotiated {@link RespVersion} from the
 * {@link ConnectionAttributes#CONNECTION} channel attribute (a {@code volatile}
 * field on {@link ClientConnection}), which is why that field is safely visible
 * even though the version is set on the command thread by {@code HELLO}.
 */
public final class RespResponseEncoder extends MessageToByteEncoder<RespValue> {

    @Override
    protected void encode(ChannelHandlerContext ctx, RespValue msg, ByteBuf out) {
        ClientConnection conn = ctx.channel().attr(ConnectionAttributes.CONNECTION).get();
        RespVersion version = (conn != null) ? conn.protocol() : RespVersion.RESP2;
        RespEncoder.encode(msg, out, version);
    }
}
