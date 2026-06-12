package dev.jediscore.engine;

/**
 * A decoded client request: the raw argument vector of a single command.
 *
 * <p>Arguments are kept as {@code byte[]} rather than {@code String} so that
 * binary-safe keys and values survive untouched and so the hot path avoids
 * decoding work it may not need. The command name is {@code args[0]}.
 *
 * @param args the argument vector; never empty (empty frames are dropped by the
 *             decoder before a request is created)
 */
public record RespRequest(byte[][] args) {
}
