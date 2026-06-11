/*
 * jediscore-datastructures — the core data types (dict, list, set, zset, hash).
 *
 * A leaf module with single-threaded semantics and no locking; thread-safety is
 * provided by the engine's single-writer command loop, not by these structures.
 */
plugins {
    id("jediscore.java-conventions")
}
