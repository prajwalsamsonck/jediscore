package dev.jediscore.network;

import dev.jediscore.engine.ClientConnection;
import io.netty.util.AttributeKey;

/**
 * Channel attribute keys shared across the pipeline handlers.
 *
 * <p>The {@link #CONNECTION} attribute carries the per-connection engine state
 * so the response encoder (on the I/O thread) and the command handler can both
 * reach it without coupling to each other.
 */
public final class ConnectionAttributes {

    /** The engine-side {@link ClientConnection} bound to a Netty channel. */
    public static final AttributeKey<ClientConnection> CONNECTION =
            AttributeKey.valueOf("jedicore.connection");

    private ConnectionAttributes() {
        // Constants holder; not instantiable.
    }
}
