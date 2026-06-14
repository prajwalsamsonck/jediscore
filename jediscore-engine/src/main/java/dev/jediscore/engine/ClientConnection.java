package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.protocol.RespValue;
import dev.jediscore.protocol.RespVersion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-side state for a single client connection.
 *
 * <p>This type is deliberately free of any Netty dependency: it holds protocol
 * and session state only, so the engine never reaches into the network layer.
 *
 * <p><strong>Threading.</strong> Mutating commands run on the single command
 * thread, so most fields are touched by that one thread. Two fields —
 * {@link #protocol} and {@link #name} — are also read by the I/O thread (the
 * encoder needs the protocol version; {@code CLIENT INFO} on another connection
 * may read the name), so they are {@code volatile} to publish those writes
 * safely across threads. The per-command {@link #closeAfterReply} flag is only
 * ever touched on the command thread.
 */
public final class ClientConnection {

    private final long id;
    private final String remoteAddress;
    private final String localAddress;
    private final long createdAtMillis;

    private volatile RespVersion protocol = RespVersion.RESP2;
    private volatile String name = "";
    private volatile boolean authenticated;

    private boolean closeAfterReply;
    private String lastCommand = "";
    private int db;

    /**
     * Out-of-band message sink, supplied by the network layer. {@code volatile}
     * because it is attached on the I/O thread (on connect) and read on the
     * command thread (when {@code PUBLISH} delivers to this connection).
     */
    private volatile ClientOutbox outbox;

    /**
     * Pub/sub subscriptions for this connection. Insertion-ordered to match
     * Redis's confirmation ordering, and confined to the command thread (mutated
     * by pub/sub commands and disconnect cleanup, both of which run there).
     */
    private final Set<Bytes> channels = new LinkedHashSet<>();
    private final Set<Bytes> patterns = new LinkedHashSet<>();
    private final Set<Bytes> shardChannels = new LinkedHashSet<>();

    /**
     * Transaction state (MULTI/EXEC). All command-thread-confined: {@link #inMulti}
     * is set by {@code MULTI}; {@link #queuedCommands} accumulates the queued
     * argument vectors; {@link #transactionError} records a queue-time error so
     * {@code EXEC} aborts with {@code EXECABORT}; {@link #casDirty} records that a
     * watched key changed so {@code EXEC} returns a nil array.
     */
    private boolean inMulti;
    private final List<byte[][]> queuedCommands = new ArrayList<>();
    private boolean transactionError;
    private boolean casDirty;

    /** The set of keys this connection is watching (for cleanup), keyed by (db, key). */
    private final Set<WatchTable.WatchKey> watchedKeys = new LinkedHashSet<>();

    /**
     * Creates a connection record.
     *
     * @param id            the unique, monotonically increasing client id
     * @param remoteAddress the peer address as {@code ip:port}
     * @param localAddress  the local accept address as {@code ip:port}
     * @param authenticated whether the connection starts authenticated (true
     *                      when the server has no password configured)
     */
    public ClientConnection(long id, String remoteAddress, String localAddress, boolean authenticated) {
        this.id = id;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.authenticated = authenticated;
        this.createdAtMillis = System.currentTimeMillis();
    }

    /** @return the unique client id */
    public long id() {
        return id;
    }

    /** @return the peer address as {@code ip:port} */
    public String remoteAddress() {
        return remoteAddress;
    }

    /** @return the local accept address as {@code ip:port} */
    public String localAddress() {
        return localAddress;
    }

    /** @return seconds since this connection was established */
    public long ageSeconds() {
        return (System.currentTimeMillis() - createdAtMillis) / 1000;
    }

    /** @return the negotiated protocol version */
    public RespVersion protocol() {
        return protocol;
    }

    /**
     * Sets the negotiated protocol version (via {@code HELLO}).
     *
     * @param protocol the new version
     */
    public void setProtocol(RespVersion protocol) {
        this.protocol = protocol;
    }

    /** @return the client name set via {@code CLIENT SETNAME}, or "" if unset */
    public String name() {
        return name;
    }

    /**
     * Sets the client name.
     *
     * @param name the new name (already validated by the caller)
     */
    public void setName(String name) {
        this.name = name;
    }

    /** @return whether the connection has authenticated */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Marks the connection authenticated (or not).
     *
     * @param authenticated the new state
     */
    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    /** @return whether the connection should be closed after the current reply is flushed */
    public boolean isCloseAfterReply() {
        return closeAfterReply;
    }

    /** Requests that the connection be closed once the current reply is flushed (used by {@code QUIT}). */
    public void requestClose() {
        this.closeAfterReply = true;
    }

    /** @return the index of the database this connection has selected */
    public int db() {
        return db;
    }

    /**
     * Selects a database for this connection (via {@code SELECT}).
     *
     * @param db the database index
     */
    public void selectDb(int db) {
        this.db = db;
    }

    /** @return the name of the most recently dispatched command (for {@code CLIENT INFO}) */
    public String lastCommand() {
        return lastCommand;
    }

    /**
     * Records the most recently dispatched command name.
     *
     * @param lastCommand the command (and subcommand) name, e.g. {@code client|info}
     */
    public void setLastCommand(String lastCommand) {
        this.lastCommand = lastCommand;
    }

    /**
     * Attaches the out-of-band message sink (called once by the network layer on
     * connect).
     *
     * @param outbox the sink
     */
    public void attachOutbox(ClientOutbox outbox) {
        this.outbox = outbox;
    }

    /**
     * Pushes an out-of-band message to this client, if a sink is attached.
     *
     * @param message the message to deliver
     */
    public void deliver(RespValue message) {
        ClientOutbox sink = outbox;
        if (sink != null) {
            sink.send(message);
        }
    }

    /** @return this connection's subscribed channels (live, mutable; command-thread only) */
    public Set<Bytes> subscribedChannels() {
        return channels;
    }

    /** @return this connection's subscribed patterns (live, mutable; command-thread only) */
    public Set<Bytes> subscribedPatterns() {
        return patterns;
    }

    /** @return this connection's subscribed shard channels (live, mutable; command-thread only) */
    public Set<Bytes> subscribedShardChannels() {
        return shardChannels;
    }

    // ---- transactions (MULTI/EXEC) ------------------------------------------

    /** @return whether a transaction is open ({@code MULTI} issued, no {@code EXEC}/{@code DISCARD} yet) */
    public boolean inMulti() {
        return inMulti;
    }

    /** Opens a transaction; subsequent non-control commands are queued. */
    public void beginMulti() {
        this.inMulti = true;
    }

    /**
     * Queues a command's argument vector for later execution by {@code EXEC}.
     *
     * @param args the argument vector
     */
    public void queueCommand(byte[][] args) {
        queuedCommands.add(args);
    }

    /** @return the queued commands (live list; the caller copies before clearing) */
    public List<byte[][]> queuedCommands() {
        return queuedCommands;
    }

    /** Flags that a queued command failed to queue (unknown command / bad arity) → {@code EXECABORT}. */
    public void markTransactionError() {
        this.transactionError = true;
    }

    /** @return whether a queue-time error occurred during the current transaction */
    public boolean hasTransactionError() {
        return transactionError;
    }

    /** Ends the transaction: clears the MULTI flag, the queue, and the queue-time error flag. */
    public void clearMulti() {
        this.inMulti = false;
        this.transactionError = false;
        this.queuedCommands.clear();
    }

    // ---- WATCH / CAS ---------------------------------------------------------

    /** @return the set of (db, key) pairs this connection watches (managed by {@link WatchTable}) */
    public Set<WatchTable.WatchKey> watchedKeys() {
        return watchedKeys;
    }

    /** Marks the connection's transaction as CAS-dirty (a watched key was modified). */
    public void markCasDirty() {
        this.casDirty = true;
    }

    /** @return whether a watched key was modified since {@code WATCH} */
    public boolean isCasDirty() {
        return casDirty;
    }

    /** Clears the CAS-dirty flag (on {@code UNWATCH}/{@code EXEC}/{@code DISCARD}). */
    public void clearCasDirty() {
        this.casDirty = false;
    }

    /** @return whether the connection holds any subscription (channel, pattern, or shard) */
    public boolean inSubscribeMode() {
        return !channels.isEmpty() || !patterns.isEmpty() || !shardChannels.isEmpty();
    }

    /** @return the count reported in (P)SUBSCRIBE confirmations: channels + patterns */
    public int regularSubscriptionCount() {
        return channels.size() + patterns.size();
    }

    /** @return the count reported in S(SUBSCRIBE) confirmations: shard channels */
    public int shardSubscriptionCount() {
        return shardChannels.size();
    }

    /**
     * Resets per-session state to defaults, as required by the {@code RESET}
     * command: drop back to RESP2, clear the name, and re-evaluate auth.
     *
     * <p>Subscriptions are cleared here too, but the {@code RESET} handler must
     * first remove this connection from the {@link PubSubRegistry} so the
     * server-side inverted indexes stay consistent.
     *
     * @param authenticatedAfterReset the auth state to apply (true if no password is configured)
     */
    public void reset(boolean authenticatedAfterReset) {
        this.protocol = RespVersion.RESP2;
        this.name = "";
        this.authenticated = authenticatedAfterReset;
        this.closeAfterReply = false;
        this.db = 0;
        this.channels.clear();
        this.patterns.clear();
        this.shardChannels.clear();
        this.inMulti = false;
        this.transactionError = false;
        this.casDirty = false;
        this.queuedCommands.clear();
        this.watchedKeys.clear();
    }
}
