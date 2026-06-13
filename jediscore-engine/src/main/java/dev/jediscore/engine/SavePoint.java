package dev.jediscore.engine;

/**
 * An RDB save point: trigger a background save if at least {@code changes} write
 * operations have occurred within {@code seconds} (Redis's {@code save 900 1}).
 *
 * @param seconds the time window
 * @param changes the change threshold
 */
public record SavePoint(long seconds, long changes) {
}
