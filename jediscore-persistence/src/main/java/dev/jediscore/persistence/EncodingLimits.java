package dev.jediscore.persistence;

/**
 * The encoding thresholds used when reconstructing values loaded from RDB, so a
 * loaded collection adopts the same listpack/hashtable/intset/skiplist encoding
 * it would have if built by commands.
 *
 * @param hashMaxEntries hash listpack entry threshold
 * @param hashMaxValue   hash listpack value-size threshold
 * @param listMaxSize    list listpack size threshold
 * @param listMaxValue   list listpack value-size threshold
 * @param setMaxIntset   set intset entry threshold
 * @param setMaxListpack set listpack entry threshold
 * @param setMaxValue    set listpack value-size threshold
 * @param zsetMaxEntries sorted-set listpack entry threshold
 * @param zsetMaxValue   sorted-set listpack value-size threshold
 */
public record EncodingLimits(
        int hashMaxEntries, int hashMaxValue,
        int listMaxSize, int listMaxValue,
        int setMaxIntset, int setMaxListpack, int setMaxValue,
        int zsetMaxEntries, int zsetMaxValue) {
}
