/*
 * jediscore-protocol — RESP2/RESP3 wire codec.
 *
 * Depends on `netty-buffer` ONLY (not transport/codec/handler) so the codec can
 * parse and encode directly against Netty's ByteBuf with minimal allocation. It
 * is `api` because ByteBuf appears in the codec's public signatures. The channel
 * pipeline and event-loop machinery live in jediscore-network; this module stays
 * free of that. Tests exercise the codec against plain byte arrays via
 * Unpooled.wrappedBuffer, so testability without a server is preserved.
 */
plugins {
    id("jediscore.java-conventions")
}

dependencies {
    api(libs.netty.buffer)
}
