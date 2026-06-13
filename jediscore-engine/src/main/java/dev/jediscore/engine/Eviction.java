package dev.jediscore.engine;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Approximated maxmemory eviction, mirroring Redis's sampling approach.
 *
 * <p>When {@code used_memory} exceeds {@code maxmemory}, a victim is chosen by
 * <em>sampling</em> {@code maxmemory-samples} keys and evicting the best one for
 * the policy — not by maintaining a global LRU/LFU order, which would be too
 * expensive. This is exactly Redis's tradeoff: eviction is approximate (a sampled
 * key, not necessarily the globally-best one), and accuracy improves with the
 * sample size. Redis additionally keeps a 16-entry candidate pool across calls
 * for slightly better approximation; we use the simpler per-call sampling and
 * document the difference.
 *
 * <p>The scoring is unified so "higher score = more evictable": LRU uses idle
 * time, LFU uses {@code 255 - frequency}, TTL uses the negated expiry (soonest
 * first), and random uses a random number. {@code volatile-*} policies only
 * sample keys that have a TTL, so persistent keys are never evicted by them.
 */
public final class Eviction {

    /** Safety cap on eviction iterations per command, to bound worst-case work. */
    private static final int HARD_LIMIT = 1_000_000;

    /**
     * Commands that add data and are therefore refused with an OOM error when the
     * memory limit can't be honoured (Redis's {@code denyoom} flag). Read-only and
     * memory-freeing commands are intentionally absent.
     */
    private static final Set<String> DENY_OOM = Set.of(
            "SET", "SETNX", "SETEX", "PSETEX", "MSET", "MSETNX", "APPEND", "SETRANGE",
            "INCR", "DECR", "INCRBY", "DECRBY", "INCRBYFLOAT", "GETSET",
            "HSET", "HSETNX", "HMSET", "HINCRBY", "HINCRBYFLOAT",
            "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LINSERT", "LSET", "RPOPLPUSH", "LMOVE",
            "SADD", "SMOVE", "SUNIONSTORE", "SINTERSTORE", "SDIFFSTORE",
            "ZADD", "ZINCRBY", "ZRANGESTORE", "ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE",
            "COPY", "RENAMENX");

    private Eviction() {
        // Static utility; not instantiable.
    }

    /**
     * @param upperName the upper-cased command name
     * @return whether the command must be refused on OOM
     */
    public static boolean isDenyOom(String upperName) {
        return DENY_OOM.contains(upperName);
    }

    /**
     * Evicts keys until memory is within {@code maxmemory}.
     *
     * @param ctx the server context
     * @return {@code true} if usage is now within the limit; {@code false} if it
     *         cannot be (noeviction, or nothing left to evict)
     */
    public static boolean evictToFit(ServerContext ctx) {
        long max = ctx.config().maxMemory();
        if (ctx.usedMemory() <= max) {
            return true;
        }
        MaxmemoryPolicy policy = ctx.config().maxMemoryPolicy();
        if (policy == MaxmemoryPolicy.NOEVICTION) {
            return false;
        }
        int samples = ctx.config().maxMemorySamples();
        int guard = 0;
        while (ctx.usedMemory() > max) {
            if (guard++ > HARD_LIMIT) {
                return false;
            }
            Victim victim = selectVictim(ctx, policy, samples);
            if (victim == null) {
                return false; // no evictable key under this policy
            }
            ctx.database(victim.dbIndex).remove(victim.key);
        }
        return true;
    }

    private record Victim(int dbIndex, Bytes key) {
    }

    private static Victim selectVictim(ServerContext ctx, MaxmemoryPolicy policy, int samples) {
        long now = System.currentTimeMillis();
        boolean volatileOnly = policy.isVolatileOnly();
        Victim best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int d = 0; d < ctx.databaseCount(); d++) {
            Database db = ctx.database(d);
            List<Bytes> sample = volatileOnly ? db.sampleVolatileKeys(samples) : db.sampleKeys(samples);
            for (Bytes key : sample) {
                RedisValue value = db.peek(key);
                if (value == null) {
                    continue; // lazily expired during sampling
                }
                double score = score(policy, db, key, value, now);
                if (best == null || score > bestScore) {
                    best = new Victim(d, key);
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static double score(MaxmemoryPolicy policy, Database db, Bytes key, RedisValue value, long now) {
        return switch (policy) {
            case ALLKEYS_LRU, VOLATILE_LRU -> value.idleMillis(now);     // most idle = best victim
            case ALLKEYS_LFU, VOLATILE_LFU -> 255.0 - value.frequency(); // least frequent = best victim
            case ALLKEYS_RANDOM, VOLATILE_RANDOM -> ThreadLocalRandom.current().nextDouble();
            case VOLATILE_TTL -> {
                Long when = db.getExpireAt(key);
                yield when == null ? Double.NEGATIVE_INFINITY : -(double) when; // soonest to expire
            }
            case NOEVICTION -> 0;
        };
    }
}
