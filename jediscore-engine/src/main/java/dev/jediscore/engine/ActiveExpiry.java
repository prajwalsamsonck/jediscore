package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import java.util.List;

/**
 * Redis's probabilistic active-expiration cycle.
 *
 * <p>Lazy expiration alone leaks memory for keys that are never accessed again,
 * so Redis also runs a background cycle: for each database it repeatedly samples
 * a batch of keys that have a TTL, deletes the expired ones, and — if more than a
 * quarter of the batch was expired — samples again, on the assumption that many
 * stale keys remain. It stops a database once a batch comes back mostly-live (or
 * a per-database iteration cap is hit), which bounds the work per cycle.
 *
 * <p>This runs <em>on the command thread</em> (the cron thread merely submits it),
 * so it shares the keyspace safely with command execution.
 */
public final class ActiveExpiry {

    /** Keys sampled per batch (Redis ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP). */
    private static final int KEYS_PER_LOOP = 20;
    /** Repeat a database while more than this fraction of a batch was expired. */
    private static final double CONTINUE_THRESHOLD = 0.25;
    /** Cap on batches per database per cycle, to bound the work. */
    private static final int MAX_ITERATIONS = 16;

    private ActiveExpiry() {
        // Static utility; not instantiable.
    }

    /**
     * Runs one active-expiry cycle across all databases.
     *
     * @param context the server context
     * @return the total number of keys expired this cycle
     */
    public static int run(ServerContext context) {
        int totalExpired = 0;
        for (int dbIndex = 0; dbIndex < context.databaseCount(); dbIndex++) {
            totalExpired += runForDatabase(context.database(dbIndex));
        }
        return totalExpired;
    }

    private static int runForDatabase(Database db) {
        int expiredInDb = 0;
        int iterations = 0;
        int sampled;
        int expiredInBatch;
        do {
            if (db.volatileCount() == 0) {
                break;
            }
            List<Bytes> batch = db.sampleVolatileKeys(KEYS_PER_LOOP);
            sampled = batch.size();
            expiredInBatch = 0;
            long now = System.currentTimeMillis();
            for (Bytes key : batch) {
                Long when = db.getExpireAt(key);
                if (when != null && when <= now) {
                    db.remove(key);
                    expiredInBatch++;
                }
            }
            expiredInDb += expiredInBatch;
            iterations++;
        } while (sampled > 0 && expiredInBatch > sampled * CONTINUE_THRESHOLD && iterations < MAX_ITERATIONS);
        return expiredInDb;
    }
}
