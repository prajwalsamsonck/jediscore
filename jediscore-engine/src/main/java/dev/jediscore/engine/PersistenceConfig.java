package dev.jediscore.engine;

import java.util.List;

/**
 * Persistence configuration, kept separate from {@link ServerConfig} so the core
 * server config stays focused. Covers RDB (dir, filename, save points) and the
 * AOF settings used in a later phase.
 *
 * @param dir          the working directory for RDB/AOF files
 * @param dbFilename   the RDB filename (Redis {@code dbfilename}, default dump.rdb)
 * @param savePoints   the RDB save points ({@code save 900 1 …}); empty disables auto-save
 * @param appendOnly   whether AOF is enabled (used in the AOF phase)
 * @param appendFsync  the AOF fsync policy: {@code always}/{@code everysec}/{@code no}
 */
public record PersistenceConfig(
        String dir,
        String dbFilename,
        List<SavePoint> savePoints,
        boolean appendOnly,
        String appendFsync) {

    /** Redis's default save points and an RDB-only (no AOF) configuration. */
    public static PersistenceConfig defaults() {
        return new PersistenceConfig(".", "dump.rdb",
                List.of(new SavePoint(900, 1), new SavePoint(300, 100), new SavePoint(60, 10000)),
                false, "everysec");
    }

    /**
     * Returns a copy targeting a different working directory (used by tests).
     *
     * @param newDir the directory
     * @return the updated config
     */
    public PersistenceConfig withDir(String newDir) {
        return new PersistenceConfig(newDir, dbFilename, savePoints, appendOnly, appendFsync);
    }

    /**
     * Returns a copy with the given save points.
     *
     * @param points the save points
     * @return the updated config
     */
    public PersistenceConfig withSavePoints(List<SavePoint> points) {
        return new PersistenceConfig(dir, dbFilename, points, appendOnly, appendFsync);
    }
}
