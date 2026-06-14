package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;

/**
 * A hook notified when a {@link Database} mutates, so cross-cutting concerns can
 * react to keyspace changes without each command having to call them explicitly.
 *
 * <p>Today it drives WATCH/CAS invalidation (see {@link WatchTable}); it is the
 * natural place to add keyspace notifications later. Calls happen on the command
 * thread, synchronously inside the mutation.
 */
public interface KeyspaceListener {

    /** A listener that ignores everything; the default before wiring. */
    KeyspaceListener NONE = new KeyspaceListener() {
        @Override public void onKeyModified(int db, Bytes key) { }
        @Override public void onFlushed(int db) { }
    };

    /**
     * Signals that a single key was created, overwritten, deleted, expired, or had
     * its TTL changed.
     *
     * @param db  the database index
     * @param key the affected key
     */
    void onKeyModified(int db, Bytes key);

    /**
     * Signals that an entire database was cleared ({@code FLUSHDB}/{@code FLUSHALL}).
     *
     * @param db the database index
     */
    void onFlushed(int db);
}
