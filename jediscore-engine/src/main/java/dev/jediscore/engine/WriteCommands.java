package dev.jediscore.engine;

import java.util.Set;

/**
 * The set of commands that modify the dataset.
 *
 * <p>Used to drive RDB save-point dirtiness and AOF propagation: after one of
 * these executes successfully, the dispatcher bumps the dirty counter and feeds
 * the command to the AOF.
 *
 * <p><strong>Note.</strong> Commands are propagated to the AOF verbatim. Redis
 * rewrites some non-deterministic or relative-time commands for replay safety
 * (e.g. {@code SPOP}→{@code SREM}, {@code EXPIRE}→{@code PEXPIREAT}); JediCore does
 * not yet, so those replay differently — a documented limitation.
 */
public final class WriteCommands {

    private static final Set<String> WRITE = Set.of(
            // strings
            "SET", "SETNX", "SETEX", "PSETEX", "MSET", "MSETNX", "APPEND", "SETRANGE",
            "INCR", "DECR", "INCRBY", "DECRBY", "INCRBYFLOAT", "GETSET", "GETDEL", "GETEX",
            // generic / keyspace
            "DEL", "UNLINK", "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST",
            "RENAME", "RENAMENX", "COPY", "FLUSHDB", "FLUSHALL", "SWAPDB",
            // hashes
            "HSET", "HSETNX", "HMSET", "HDEL", "HINCRBY", "HINCRBYFLOAT",
            // lists
            "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LPOP", "RPOP", "LSET", "LINSERT",
            "LREM", "LTRIM", "RPOPLPUSH", "LMOVE",
            // sets
            "SADD", "SREM", "SPOP", "SMOVE", "SUNIONSTORE", "SINTERSTORE", "SDIFFSTORE",
            // sorted sets
            "ZADD", "ZINCRBY", "ZREM", "ZPOPMIN", "ZPOPMAX", "ZRANGESTORE",
            "ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE");

    private WriteCommands() {
        // Static utility; not instantiable.
    }

    /**
     * @param upperName the upper-cased command name
     * @return whether the command modifies the dataset
     */
    public static boolean isWrite(String upperName) {
        return WRITE.contains(upperName);
    }
}
