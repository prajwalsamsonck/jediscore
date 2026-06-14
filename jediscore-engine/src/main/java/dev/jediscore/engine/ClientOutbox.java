package dev.jediscore.engine;

import dev.jediscore.protocol.RespValue;

/**
 * A sink for out-of-band messages pushed to a single client outside the normal
 * request/reply flow — pub/sub messages today, key-ready notifications later.
 *
 * <p>This is the engine's one-way door to the network layer: the engine stays
 * free of any Netty dependency, and the network layer supplies an implementation
 * (typically {@code channel::writeAndFlush}) when a connection is established.
 *
 * <p><strong>Threading.</strong> {@link #send} is invoked on the command thread
 * (for example by {@code PUBLISH} delivering to subscribers). Implementations
 * must be safe to call from there; Netty's {@code writeAndFlush} is, and it
 * preserves per-channel ordering with the connection's own command replies
 * because those are emitted from the same command thread.
 */
@FunctionalInterface
public interface ClientOutbox {

    /**
     * Pushes a message to the client.
     *
     * @param message the RESP value to send
     */
    void send(RespValue message);
}
