package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.Glob;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server-side pub/sub fan-out tables: channel, pattern, and shard-channel
 * inverted indexes mapping a subscription key to the connections subscribed to
 * it.
 *
 * <p><strong>Threading.</strong> This registry is <em>confined to the command
 * thread</em>. Every mutation (subscribe/unsubscribe) and read (publish,
 * introspection) happens while a command runs, and disconnect cleanup is
 * submitted to the same thread. That confinement is what lets the maps be plain
 * {@link LinkedHashMap}s with no locking — the same single-writer discipline the
 * keyspace itself relies on. Insertion order is preserved so {@code PUBSUB
 * CHANNELS} and message fan-out are deterministic.
 *
 * <p>Keys are the binary-safe {@link Bytes} wrapper, so channel and pattern names
 * are treated as opaque byte strings exactly as Redis does.
 */
public final class PubSubRegistry {

    private static final byte[] MESSAGE = "message".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PMESSAGE = "pmessage".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SMESSAGE = "smessage".getBytes(StandardCharsets.UTF_8);

    private final Map<Bytes, Set<ClientConnection>> channels = new LinkedHashMap<>();
    private final Map<Bytes, Set<ClientConnection>> patterns = new LinkedHashMap<>();
    private final Map<Bytes, Set<ClientConnection>> shardChannels = new LinkedHashMap<>();

    // ---- subscribe / unsubscribe --------------------------------------------

    /**
     * Subscribes a connection to a channel.
     *
     * @param conn    the connection
     * @param channel the channel name
     * @return the connection's regular subscription count after the change
     */
    public int subscribe(ClientConnection conn, byte[] channel) {
        Bytes key = new Bytes(channel);
        if (conn.subscribedChannels().add(key)) {
            channels.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(conn);
        }
        return conn.regularSubscriptionCount();
    }

    /**
     * Unsubscribes a connection from a channel.
     *
     * @param conn    the connection
     * @param channel the channel name
     * @return the connection's regular subscription count after the change
     */
    public int unsubscribe(ClientConnection conn, byte[] channel) {
        Bytes key = new Bytes(channel);
        if (conn.subscribedChannels().remove(key)) {
            removeFromIndex(channels, key, conn);
        }
        return conn.regularSubscriptionCount();
    }

    /**
     * Subscribes a connection to a pattern.
     *
     * @param conn    the connection
     * @param pattern the glob pattern
     * @return the connection's regular subscription count after the change
     */
    public int psubscribe(ClientConnection conn, byte[] pattern) {
        Bytes key = new Bytes(pattern);
        if (conn.subscribedPatterns().add(key)) {
            patterns.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(conn);
        }
        return conn.regularSubscriptionCount();
    }

    /**
     * Unsubscribes a connection from a pattern.
     *
     * @param conn    the connection
     * @param pattern the glob pattern
     * @return the connection's regular subscription count after the change
     */
    public int punsubscribe(ClientConnection conn, byte[] pattern) {
        Bytes key = new Bytes(pattern);
        if (conn.subscribedPatterns().remove(key)) {
            removeFromIndex(patterns, key, conn);
        }
        return conn.regularSubscriptionCount();
    }

    /**
     * Subscribes a connection to a shard channel.
     *
     * @param conn    the connection
     * @param channel the shard channel name
     * @return the connection's shard subscription count after the change
     */
    public int ssubscribe(ClientConnection conn, byte[] channel) {
        Bytes key = new Bytes(channel);
        if (conn.subscribedShardChannels().add(key)) {
            shardChannels.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(conn);
        }
        return conn.shardSubscriptionCount();
    }

    /**
     * Unsubscribes a connection from a shard channel.
     *
     * @param conn    the connection
     * @param channel the shard channel name
     * @return the connection's shard subscription count after the change
     */
    public int sunsubscribe(ClientConnection conn, byte[] channel) {
        Bytes key = new Bytes(channel);
        if (conn.subscribedShardChannels().remove(key)) {
            removeFromIndex(shardChannels, key, conn);
        }
        return conn.shardSubscriptionCount();
    }

    /** @return a snapshot of this connection's subscribed channels (for unsubscribe-all) */
    public List<byte[]> subscribedChannelsOf(ClientConnection conn) {
        return snapshot(conn.subscribedChannels());
    }

    /** @return a snapshot of this connection's subscribed patterns (for punsubscribe-all) */
    public List<byte[]> subscribedPatternsOf(ClientConnection conn) {
        return snapshot(conn.subscribedPatterns());
    }

    /** @return a snapshot of this connection's subscribed shard channels (for sunsubscribe-all) */
    public List<byte[]> subscribedShardChannelsOf(ClientConnection conn) {
        return snapshot(conn.subscribedShardChannels());
    }

    // ---- publish -------------------------------------------------------------

    /**
     * Publishes a message to a channel, delivering to direct channel subscribers
     * and to every connection whose pattern matches the channel.
     *
     * @param channel the target channel
     * @param payload the message payload
     * @return the number of clients the message was delivered to
     */
    public int publish(byte[] channel, byte[] payload) {
        int receivers = 0;
        Set<ClientConnection> direct = channels.get(new Bytes(channel));
        if (direct != null) {
            RespValue frame = new RespValue.Push(List.of(
                    RespValue.bulk(MESSAGE), RespValue.bulk(channel), RespValue.bulk(payload)));
            for (ClientConnection conn : direct) {
                conn.deliver(frame);
                receivers++;
            }
        }
        for (Map.Entry<Bytes, Set<ClientConnection>> entry : patterns.entrySet()) {
            byte[] pattern = entry.getKey().array();
            if (Glob.match(pattern, channel)) {
                RespValue frame = new RespValue.Push(List.of(
                        RespValue.bulk(PMESSAGE), RespValue.bulk(pattern),
                        RespValue.bulk(channel), RespValue.bulk(payload)));
                for (ClientConnection conn : entry.getValue()) {
                    conn.deliver(frame);
                    receivers++;
                }
            }
        }
        return receivers;
    }

    /**
     * Publishes a message to a shard channel.
     *
     * @param channel the target shard channel
     * @param payload the message payload
     * @return the number of clients the message was delivered to
     */
    public int spublish(byte[] channel, byte[] payload) {
        int receivers = 0;
        Set<ClientConnection> direct = shardChannels.get(new Bytes(channel));
        if (direct != null) {
            RespValue frame = new RespValue.Push(List.of(
                    RespValue.bulk(SMESSAGE), RespValue.bulk(channel), RespValue.bulk(payload)));
            for (ClientConnection conn : direct) {
                conn.deliver(frame);
                receivers++;
            }
        }
        return receivers;
    }

    // ---- introspection (PUBSUB) ---------------------------------------------

    /**
     * Lists active channels (those with at least one subscriber), optionally
     * filtered by a glob pattern.
     *
     * @param pattern the filter pattern, or {@code null} for all
     * @return the matching active channel names
     */
    public List<byte[]> activeChannels(byte[] pattern) {
        return activeOf(channels, pattern);
    }

    /**
     * Lists active shard channels, optionally filtered by a glob pattern.
     *
     * @param pattern the filter pattern, or {@code null} for all
     * @return the matching active shard channel names
     */
    public List<byte[]> activeShardChannels(byte[] pattern) {
        return activeOf(shardChannels, pattern);
    }

    /**
     * Counts subscribers to a channel.
     *
     * @param channel the channel name
     * @return the subscriber count (0 if none)
     */
    public int subscriberCount(byte[] channel) {
        Set<ClientConnection> subs = channels.get(new Bytes(channel));
        return subs == null ? 0 : subs.size();
    }

    /**
     * Counts subscribers to a shard channel.
     *
     * @param channel the shard channel name
     * @return the subscriber count (0 if none)
     */
    public int shardSubscriberCount(byte[] channel) {
        Set<ClientConnection> subs = shardChannels.get(new Bytes(channel));
        return subs == null ? 0 : subs.size();
    }

    /** @return the number of patterns with at least one subscriber */
    public int patternCount() {
        return patterns.size();
    }

    /** @return the number of channels with at least one subscriber */
    public int channelCount() {
        return channels.size();
    }

    /** @return the number of shard channels with at least one subscriber */
    public int shardChannelCount() {
        return shardChannels.size();
    }

    // ---- lifecycle -----------------------------------------------------------

    /**
     * Removes a connection from every index (called on disconnect or {@code
     * RESET}). Does not touch the connection's own subscription sets when called
     * for a disconnect; callers resetting a live connection clear those via
     * {@link ClientConnection#reset(boolean)}.
     *
     * @param conn the departing connection
     */
    public void removeAll(ClientConnection conn) {
        for (Bytes key : conn.subscribedChannels()) {
            removeFromIndex(channels, key, conn);
        }
        for (Bytes key : conn.subscribedPatterns()) {
            removeFromIndex(patterns, key, conn);
        }
        for (Bytes key : conn.subscribedShardChannels()) {
            removeFromIndex(shardChannels, key, conn);
        }
        conn.subscribedChannels().clear();
        conn.subscribedPatterns().clear();
        conn.subscribedShardChannels().clear();
    }

    // ---- internals -----------------------------------------------------------

    private static void removeFromIndex(Map<Bytes, Set<ClientConnection>> index, Bytes key,
                                        ClientConnection conn) {
        Set<ClientConnection> subs = index.get(key);
        if (subs != null) {
            subs.remove(conn);
            if (subs.isEmpty()) {
                index.remove(key);
            }
        }
    }

    private static List<byte[]> activeOf(Map<Bytes, Set<ClientConnection>> index, byte[] pattern) {
        List<byte[]> result = new ArrayList<>();
        for (Bytes key : index.keySet()) {
            if (pattern == null || Glob.match(pattern, key.array())) {
                result.add(key.array());
            }
        }
        return result;
    }

    private static List<byte[]> snapshot(Set<Bytes> set) {
        List<byte[]> result = new ArrayList<>(set.size());
        for (Bytes b : set) {
            result.add(b.array());
        }
        return result;
    }
}
