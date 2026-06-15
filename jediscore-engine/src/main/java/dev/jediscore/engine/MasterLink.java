package dev.jediscore.engine;

/**
 * The replica-side connection to a master. Implemented in {@code
 * jediscore-replication} (it needs an outbound socket) and wired into the {@link
 * ServerContext} at startup, so the engine can ask to start or stop replicating
 * without depending on the network details.
 *
 * <p>{@code REPLICAOF host port} calls {@link #connect}; {@code REPLICAOF NO ONE}
 * calls {@link #disconnect}.
 */
public interface MasterLink {

    /**
     * Begins (or retargets) replication from the given master, asynchronously: the
     * handshake, RDB load, and stream application happen on the link's own thread,
     * submitting keyspace changes to the command thread.
     *
     * @param host the master host
     * @param port the master port
     */
    void connect(String host, int port);

    /** Stops replicating and closes the link (the dataset is retained). */
    void disconnect();
}
