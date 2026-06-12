package dev.jediscore.datastructures;

/**
 * The five fundamental Redis value types. The {@link #typeName()} is what the
 * {@code TYPE} command reports.
 */
public enum RedisType {

    /** A binary-safe string. */
    STRING("string"),
    /** A list of elements. */
    LIST("list"),
    /** An unordered set of unique members. */
    SET("set"),
    /** A map of field → value. */
    HASH("hash"),
    /** A sorted set of members ordered by score. */
    ZSET("zset");

    private final String typeName;

    RedisType(String typeName) {
        this.typeName = typeName;
    }

    /** @return the lowercase name reported by the {@code TYPE} command */
    public String typeName() {
        return typeName;
    }
}
