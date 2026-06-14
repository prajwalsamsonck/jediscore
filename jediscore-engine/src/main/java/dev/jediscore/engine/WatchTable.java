package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The optimistic-locking (CAS) table behind {@code WATCH}/{@code EXEC}.
 *
 * <p>It maps each watched {@code (db, key)} to the set of connections watching it.
 * When a key is modified, every watcher is flagged {@link ClientConnection#casDirty
 * dirty}; {@code EXEC} then returns a nil array instead of running the queued
 * commands. This mirrors Redis's {@code watched_keys} dictionary and the
 * {@code CLIENT_DIRTY_CAS} flag.
 *
 * <p><strong>Threading.</strong> Like {@link PubSubRegistry}, this table is
 * confined to the command thread — every {@code WATCH}/{@code UNWATCH}/{@code EXEC}
 * and every modification signal runs there, and disconnect cleanup is submitted to
 * it — so the maps need no locking.
 */
public final class WatchTable {

    /** Per-database map of watched key → watching connections (indexed by db). */
    private final List<Map<Bytes, Set<ClientConnection>>> perDb;

    /**
     * Creates a table sized for the given number of databases.
     *
     * @param databaseCount the number of logical databases
     */
    public WatchTable(int databaseCount) {
        perDb = new ArrayList<>(databaseCount);
        for (int i = 0; i < databaseCount; i++) {
            perDb.add(new HashMap<>());
        }
    }

    /** @return whether any connection is watching any key in the given database */
    public boolean hasWatchers(int db) {
        return !perDb.get(db).isEmpty();
    }

    /**
     * Registers a watch.
     *
     * @param conn the watching connection
     * @param db   the database index
     * @param key  the key bytes
     */
    public void watch(ClientConnection conn, int db, byte[] key) {
        Bytes wrapped = new Bytes(key);
        if (conn.watchedKeys().add(new WatchKey(db, wrapped))) {
            perDb.get(db).computeIfAbsent(wrapped, k -> new LinkedHashSet<>()).add(conn);
        }
    }

    /**
     * Drops every watch held by a connection and clears its CAS-dirty flag.
     *
     * @param conn the connection
     */
    public void unwatchAll(ClientConnection conn) {
        for (WatchKey wk : conn.watchedKeys()) {
            Set<ClientConnection> watchers = perDb.get(wk.db()).get(wk.key());
            if (watchers != null) {
                watchers.remove(conn);
                if (watchers.isEmpty()) {
                    perDb.get(wk.db()).remove(wk.key());
                }
            }
        }
        conn.watchedKeys().clear();
        conn.clearCasDirty();
    }

    /**
     * Flags every connection watching a key as CAS-dirty.
     *
     * @param db  the database index
     * @param key the modified key
     */
    public void touch(int db, Bytes key) {
        Set<ClientConnection> watchers = perDb.get(db).get(key);
        if (watchers != null) {
            for (ClientConnection conn : watchers) {
                conn.markCasDirty();
            }
        }
    }

    /**
     * Flags watchers of any key in a database that appears among a write command's
     * arguments. This closes the in-place-aggregate-mutation gap: commands like
     * {@code LPUSH}/{@code SADD} mutate an existing value without re-storing it, so
     * the {@link KeyspaceListener} never fires — but the key is always an argument.
     *
     * <p>Conservative by design: if a watched key's bytes happen to equal a
     * non-key argument (e.g. a value), it is touched too. That can only abort a
     * transaction more eagerly than Redis, never miss a real modification.
     *
     * @param db   the database index
     * @param args the command argument vector ({@code args[0]} is the command name)
     */
    public void touchByArguments(int db, byte[][] args) {
        Map<Bytes, Set<ClientConnection>> watched = perDb.get(db);
        if (watched.isEmpty()) {
            return;
        }
        for (int i = 1; i < args.length; i++) {
            Bytes arg = new Bytes(args[i]);
            if (watched.containsKey(arg)) {
                touch(db, arg);
            }
        }
    }

    /**
     * Flags every connection watching <em>any</em> key in a database as CAS-dirty
     * (for {@code FLUSHDB}/{@code FLUSHALL}/{@code SWAPDB}).
     *
     * @param db the database index
     */
    public void touchAll(int db) {
        if (perDb.get(db).isEmpty()) {
            return;
        }
        for (Set<ClientConnection> watchers : perDb.get(db).values()) {
            for (ClientConnection conn : watchers) {
                conn.markCasDirty();
            }
        }
    }

    /** A watched key: a database index plus the key bytes. */
    public record WatchKey(int db, Bytes key) { }
}
