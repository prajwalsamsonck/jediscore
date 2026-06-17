package dev.jediscore.engine;

/**
 * The persistence service (RDB now; AOF later). Implemented outside the engine
 * (in {@code jediscore-persistence}) and attached to the {@link ServerContext} so
 * the persistence commands can reach it without the engine depending on the file
 * format.
 *
 * <p>All methods are invoked on the command thread, except the actual background
 * serialization a {@link #backgroundSave()} starts.
 */
public interface Persistence {

    /**
     * Synchronously writes an RDB snapshot (blocks the command thread until done).
     *
     * @return {@code true} on success
     */
    boolean save();

    /**
     * Starts a background RDB save: snapshots the keyspace now (the brief pause)
     * and serializes it off the command thread.
     *
     * @return {@code true} if a save was started, {@code false} if one is already running
     */
    boolean backgroundSave();

    /** @return the epoch-seconds time of the last successful save */
    long lastSaveSeconds();

    /** @return whether a background save is currently running */
    boolean backgroundSaveInProgress();

    /**
     * {@code DEBUG RELOAD}: serialize the keyspace and load it back, round-tripping
     * through the RDB format.
     */
    void reload();

    /**
     * Loads persisted data at startup: the AOF (if enabled) takes precedence over
     * the RDB; otherwise the RDB is loaded if present.
     */
    void loadOnStartup();

    /** Invoked on every cron tick (command thread): RDB save points and AOF everysec fsync. */
    void onCron();

    /** @return whether AOF is enabled */
    boolean appendOnlyEnabled();

    /** @return the working directory where RDB/AOF files are stored */
    String dir();

    /**
     * Appends a write command to the AOF (no-op if AOF is disabled or during load).
     *
     * @param database the database the command targeted
     * @param args     the command argument vector
     */
    void feedAppendOnly(int database, byte[][] args);

    /**
     * Starts an AOF rewrite (compaction): a fresh base capturing current state plus
     * a new, empty incr file.
     *
     * @return {@code true} if a rewrite was started, {@code false} if one is running
     *         or AOF is disabled
     */
    boolean rewriteAppendOnly();

    /** @return whether an AOF rewrite is currently running */
    boolean appendRewriteInProgress();

    /**
     * Serializes the current keyspace to an in-memory RDB image, for a replication
     * full resync. Runs synchronously on the command thread.
     *
     * @return the RDB bytes
     */
    byte[] dumpRdb();

    /**
     * Replaces the keyspace with a master's RDB image during a replication full
     * resync: flushes every database, then loads the bytes. Runs on the command
     * thread.
     *
     * @param rdb the RDB image received from the master
     */
    void loadReplicaRdb(byte[] rdb);

    /** Stops background workers and flushes/closes the AOF. */
    void shutdown();
}
