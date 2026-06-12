package dev.jediscore.network;

import dev.jediscore.engine.RespRequest;
import dev.jediscore.protocol.ProtocolException;
import dev.jediscore.protocol.RespParser;
import dev.jediscore.protocol.RespValue;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Decodes the inbound byte stream into {@link RespRequest}s.
 *
 * <p>{@link ByteToMessageDecoder} owns a cumulation buffer and re-invokes
 * {@link #decode} as bytes arrive, which is what makes pipelining and
 * fragmentation handling fall out naturally: we drain as many complete requests
 * as the buffer holds, and stop as soon as {@link RespParser#parseRequest}
 * reports it needs more bytes (leaving the partial frame buffered for next time).
 *
 * <p>On a protocol violation we reply with {@code -ERR Protocol error: …} and
 * close the connection, exactly as Redis does. The reply is written at the
 * channel level so it traverses the response encoder, which sits closer to the
 * tail than this decoder.
 */
public final class RespRequestDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            byte[][] args;
            try {
                args = RespParser.parseRequest(in);
            } catch (ProtocolException e) {
                // Discard the rest of the input first: closing the channel may
                // release this buffer synchronously (it does under EmbeddedChannel),
                // so we must not touch `in` afterwards.
                in.skipBytes(in.readableBytes());
                ctx.channel()
                        .writeAndFlush(RespValue.error("ERR Protocol error: " + e.getMessage()))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (args == null) {
                break; // incomplete frame; wait for more bytes
            }
            if (args.length == 0) {
                continue; // empty frame (blank inline line or *0); ignore
            }
            out.add(new RespRequest(args));
        }
    }
}
