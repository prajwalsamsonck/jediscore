package dev.jediscore.persistence;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.engine.Database;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared snapshot/loader helpers used by both RDB and AOF persistence (an AOF
 * base file is itself an RDB).
 */
final class Snapshots {

    private Snapshots() {
    }

    /**
     * Builds a point-in-time snapshot of the keyspace.
     *
     * @param context  the server context
     * @param deepCopy {@code true} to clone values (for background serialization);
     *                 {@code false} to reference them (safe only on the command thread)
     * @return the snapshot
     */
    static RdbSnapshot of(ServerContext context, boolean deepCopy) {
        List<RdbSnapshot.DatabaseSnapshot> dbs = new ArrayList<>();
        for (int i = 0; i < context.databaseCount(); i++) {
            Database db = context.database(i);
            List<Bytes> keys = db.liveKeys();
            if (keys.isEmpty()) {
                continue;
            }
            List<RdbSnapshot.Entry> entries = new ArrayList<>(keys.size());
            for (Bytes key : keys) {
                RedisValue value = db.peek(key);
                if (value == null) {
                    continue;
                }
                Long expireAt = db.getExpireAt(key);
                entries.add(new RdbSnapshot.Entry(
                        key.copy(),
                        deepCopy ? value.deepCopy() : value,
                        expireAt == null ? -1 : expireAt));
            }
            dbs.add(new RdbSnapshot.DatabaseSnapshot(i, entries));
        }
        return new RdbSnapshot(dbs);
    }

    /** @return encoding thresholds derived from the server config */
    static EncodingLimits limits(ServerConfig cfg) {
        return new EncodingLimits(
                cfg.hashMaxListpackEntries(), cfg.hashMaxListpackValue(),
                cfg.listMaxListpackSize(), cfg.listMaxListpackValue(),
                cfg.setMaxIntsetEntries(), cfg.setMaxListpackEntries(), cfg.setMaxListpackValue(),
                cfg.zsetMaxListpackEntries(), cfg.zsetMaxListpackValue());
    }

    /**
     * Loads an RDB stream into the keyspace, dropping already-expired keys.
     *
     * @param in      the RDB stream
     * @param context the server context
     * @throws IOException on malformed input
     */
    static void loadRdbStream(InputStream in, ServerContext context) throws IOException {
        long now = System.currentTimeMillis();
        new RdbReader(in, limits(context.config())).readInto((dbIndex, key, value, expireAtMs) -> {
            if (expireAtMs >= 0 && expireAtMs <= now) {
                return;
            }
            Database database = context.database(dbIndex);
            Bytes wrapped = new Bytes(key);
            database.putKeepTtl(wrapped, value);
            if (expireAtMs >= 0) {
                database.setExpireAt(wrapped, expireAtMs);
            }
        });
    }
}
