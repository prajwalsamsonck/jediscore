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

    /** Loads the on-disk RDB into the keyspace at startup, if a file exists. */
    void loadOnStartup();

    /** Invoked on every cron tick (command thread) to evaluate RDB save points. */
    void onCron();

    /** Stops background workers. */
    void shutdown();
}
