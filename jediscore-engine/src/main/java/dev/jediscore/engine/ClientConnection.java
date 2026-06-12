package dev.jediscore.engine;

import dev.jediscore.protocol.RespVersion;

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
     * Resets per-session state to defaults, as required by the {@code RESET}
     * command: drop back to RESP2, clear the name, and re-evaluate auth.
     *
     * @param authenticatedAfterReset the auth state to apply (true if no password is configured)
     */
    public void reset(boolean authenticatedAfterReset) {
        this.protocol = RespVersion.RESP2;
        this.name = "";
        this.authenticated = authenticatedAfterReset;
        this.closeAfterReply = false;
    }
}
