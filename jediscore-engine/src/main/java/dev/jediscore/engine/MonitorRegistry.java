package dev.jediscore.engine;

import dev.jediscore.protocol.RespValue;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The set of connections in {@code MONITOR} mode, and the feed that streams every
 * executed command to them.
 *
 * <p>Command-thread-confined: monitors are added/fed on the command thread, and
 * disconnect cleanup is submitted there. Each fed line is an inline simple string
 * in Redis's MONITOR format: {@code <unixtime.usec> [<db> <addr>] "CMD" "arg" …}.
 */
public final class MonitorRegistry {

    private final Set<ClientConnection> monitors = new LinkedHashSet<>();

    /** @return whether any client is monitoring (a fast gate for the dispatcher) */
    public boolean hasMonitors() {
        return !monitors.isEmpty();
    }

    /**
     * Adds a connection to the monitor set.
     *
     * @param conn the connection
     */
    public void add(ClientConnection conn) {
        conn.markMonitor();
        monitors.add(conn);
    }

    /**
     * Removes a connection from the monitor set.
     *
     * @param conn the connection
     */
    public void remove(ClientConnection conn) {
        monitors.remove(conn);
    }

    /**
     * Streams an executed command to all monitors.
     *
     * @param db   the database the issuing client had selected
     * @param addr the issuing client's address
     * @param args the command argument vector
     */
    public void feed(int db, String addr, byte[][] args) {
        if (monitors.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(now / 1000).append('.').append(String.format("%06d", (now % 1000) * 1000));
        sb.append(" [").append(db).append(' ').append(addr).append(']');
        for (byte[] arg : args) {
            sb.append(' ').append('"');
            appendEscaped(sb, arg);
            sb.append('"');
        }
        RespValue line = RespValue.simple(sb.toString());
        for (ClientConnection monitor : monitors) {
            monitor.deliver(line);
        }
    }

    /** Escapes an argument the way Redis's MONITOR output does (quotes, backslashes, control bytes). */
    private static void appendEscaped(StringBuilder sb, byte[] arg) {
        for (byte b : arg) {
            int c = b & 0xff;
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c >= 0x7f) {
                        sb.append(String.format("\\x%02x", c));
                    } else {
                        sb.append((char) c);
                    }
                }
            }
        }
    }
}
