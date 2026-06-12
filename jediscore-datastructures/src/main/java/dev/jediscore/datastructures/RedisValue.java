package dev.jediscore.datastructures;

/**
 * Base type for every value stored in the keyspace.
 *
 * <p>It is a sealed abstract class (rather than an interface) so it can carry the
 * small amount of state Redis keeps on every object — currently the last-access
 * time used by {@code OBJECT IDLETIME} and (later) eviction. Concrete subtypes
 * report their {@link RedisType} and their current {@link #encoding()} (e.g.
 * {@code listpack} vs {@code hashtable}), which {@code OBJECT ENCODING} exposes.
 *
 * <p>All instances are confined to the single command thread, so no field here
 * is synchronised.
 */
public sealed abstract class RedisValue permits StringValue, HashValue, ListValue, SetValue {

    private long lastAccessMillis = System.currentTimeMillis();

    /** @return the logical type of this value */
    public abstract RedisType type();

    /** @return the current internal encoding name, as {@code OBJECT ENCODING} reports it */
    public abstract String encoding();

    /**
     * Returns an independent deep copy of this value (for {@code COPY}).
     *
     * @return a copy that shares no mutable state with this value
     */
    public abstract RedisValue deepCopy();

    /** @return the wall-clock time of the last access, in milliseconds */
    public long lastAccessMillis() {
        return lastAccessMillis;
    }

    /**
     * Records an access at the given time (for idle-time tracking).
     *
     * @param nowMillis the current time in milliseconds
     */
    public void recordAccess(long nowMillis) {
        this.lastAccessMillis = nowMillis;
    }
}
