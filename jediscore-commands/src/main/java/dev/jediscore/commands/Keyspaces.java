package dev.jediscore.commands;

import dev.jediscore.datastructures.HashValue;
import dev.jediscore.datastructures.ListValue;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.datastructures.SetValue;
import dev.jediscore.datastructures.StringValue;
import dev.jediscore.datastructures.ZSetValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;

/**
 * Shared helpers for command handlers: type-checked value access, strict
 * integer/float parsing with Redis-compatible error messages, and Redis-style
 * float formatting.
 */
final class Keyspaces {

    private Keyspaces() {
        // Static utility; not instantiable.
    }

    /**
     * Returns the value as a {@link StringValue}, or throws {@code WRONGTYPE}.
     *
     * @param value the value (may be {@code null})
     * @return the string value, or {@code null} if {@code value} was {@code null}
     */
    static StringValue asString(RedisValue value) {
        if (value == null) {
            return null;
        }
        if (value instanceof StringValue s) {
            return s;
        }
        throw CommandException.wrongType();
    }

    /**
     * Returns the value as a {@link HashValue}, or throws {@code WRONGTYPE}.
     *
     * @param value the value (may be {@code null})
     * @return the hash value, or {@code null} if {@code value} was {@code null}
     */
    static HashValue asHash(RedisValue value) {
        if (value == null) {
            return null;
        }
        if (value instanceof HashValue h) {
            return h;
        }
        throw CommandException.wrongType();
    }

    /** @return a new, config-sized hash value */
    static HashValue newHash(CommandContext ctx) {
        return new HashValue(
                ctx.server().config().hashMaxListpackEntries(),
                ctx.server().config().hashMaxListpackValue());
    }

    /**
     * Returns the value as a {@link ListValue}, or throws {@code WRONGTYPE}.
     *
     * @param value the value (may be {@code null})
     * @return the list value, or {@code null} if {@code value} was {@code null}
     */
    static ListValue asList(RedisValue value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ListValue l) {
            return l;
        }
        throw CommandException.wrongType();
    }

    /**
     * Returns the value as a {@link SetValue}, or throws {@code WRONGTYPE}.
     *
     * @param value the value (may be {@code null})
     * @return the set value, or {@code null} if {@code value} was {@code null}
     */
    static SetValue asSet(RedisValue value) {
        if (value == null) {
            return null;
        }
        if (value instanceof SetValue s) {
            return s;
        }
        throw CommandException.wrongType();
    }

    /** @return a new, config-sized list value */
    static ListValue newList(CommandContext ctx) {
        return new ListValue(
                ctx.server().config().listMaxListpackSize(),
                ctx.server().config().listMaxListpackValue());
    }

    /**
     * Returns the value as a {@link ZSetValue}, or throws {@code WRONGTYPE}.
     *
     * @param value the value (may be {@code null})
     * @return the sorted set value, or {@code null} if {@code value} was {@code null}
     */
    static ZSetValue asZSet(RedisValue value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ZSetValue z) {
            return z;
        }
        throw CommandException.wrongType();
    }

    /** @return a new, config-sized sorted set value */
    static ZSetValue newZSet(CommandContext ctx) {
        return new ZSetValue(
                ctx.server().config().zsetMaxListpackEntries(),
                ctx.server().config().zsetMaxListpackValue());
    }

    /**
     * Parses a sorted-set score: like {@link #parseDouble} but rejects NaN (Redis
     * scores may be {@code +inf}/{@code -inf} but never NaN).
     *
     * @param bytes the bytes
     * @return the score
     * @throws CommandException {@code ERR value is not a valid float}
     */
    static double parseScore(byte[] bytes) {
        double d = parseDouble(bytes);
        if (Double.isNaN(d)) {
            throw CommandException.notFloat();
        }
        return d;
    }

    /**
     * Formats a sorted-set score the way Redis replies: {@code inf}/{@code -inf}
     * for infinities, an integer with no decimal point when the value is integral,
     * and otherwise the shortest round-tripping representation.
     *
     * @param score the score
     * @return the formatted score
     */
    static String formatScore(double score) {
        if (Double.isInfinite(score)) {
            return score > 0 ? "inf" : "-inf";
        }
        if (score == Math.rint(score) && Math.abs(score) < 1e17) {
            return Long.toString((long) score);
        }
        return Double.toString(score);
    }

    /** @return a new, config-sized set value */
    static SetValue newSet(CommandContext ctx) {
        return new SetValue(
                ctx.server().config().setMaxIntsetEntries(),
                ctx.server().config().setMaxListpackEntries(),
                ctx.server().config().setMaxListpackValue());
    }

    /**
     * Parses bytes as a strict 64-bit integer the way Redis's {@code string2ll}
     * does: an optional leading {@code -}, no leading zeros, no whitespace.
     *
     * @param bytes the bytes
     * @return the value
     * @throws CommandException {@code ERR value is not an integer or out of range}
     */
    static long parseLong(byte[] bytes) {
        if (!StringValue.isCanonicalLong(bytes)) {
            throw CommandException.notInteger();
        }
        return Long.parseLong(new String(bytes, StandardCharsets.US_ASCII));
    }

    /**
     * Parses a textual integer argument (e.g. a count or index), reusing Redis's
     * not-an-integer error on failure.
     *
     * @param text the argument text
     * @return the value
     */
    static long parseLong(String text) {
        return parseLong(text.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Parses bytes as a double, accepting Redis's special tokens
     * ({@code inf}/{@code +inf}/{@code -inf}/{@code nan}, case-insensitively).
     *
     * @param bytes the bytes
     * @return the value
     * @throws CommandException {@code ERR value is not a valid float}
     */
    static double parseDouble(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.US_ASCII);
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        switch (lower) {
            case "inf", "+inf", "infinity", "+infinity" -> {
                return Double.POSITIVE_INFINITY;
            }
            case "-inf", "-infinity" -> {
                return Double.NEGATIVE_INFINITY;
            }
            case "nan" -> {
                return Double.NaN;
            }
            default -> {
                // Reject forms Java would accept but Redis would not (hex floats,
                // trailing f/d type suffixes, leading/trailing whitespace).
                if (!s.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")) {
                    throw CommandException.notFloat();
                }
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw CommandException.notFloat();
                }
            }
        }
    }

    /**
     * Like {@link #parseDouble} but rejects non-finite values, matching Redis's
     * {@code string2ld} (used by {@code INCRBYFLOAT}/{@code HINCRBYFLOAT}, where
     * {@code inf}/{@code nan} inputs are invalid floats).
     *
     * @param bytes the bytes
     * @return the finite value
     * @throws CommandException {@code ERR value is not a valid float}
     */
    static double parseFiniteDouble(byte[] bytes) {
        double d = parseDouble(bytes);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw CommandException.notFloat();
        }
        return d;
    }

    /**
     * Formats a double the way Redis's {@code ld2string(..., LD_STR_HUMAN)} does:
     * fixed-point with trailing zeros (and any trailing dot) removed.
     *
     * <p><strong>Caveat (documented honestly):</strong> Redis computes
     * {@code INCRBYFLOAT}/{@code HINCRBYFLOAT} in C {@code long double} (80-bit on
     * x86); the JVM has only IEEE-754 {@code double}, so for decimals that are not
     * exactly representable the least-significant digits can differ from Redis.
     * Exactly-representable values (integers, halves, etc.) match.
     *
     * @param value the value
     * @return the formatted string
     */
    static String formatDouble(double value) {
        if (value == (long) value && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        BigDecimal bd = new BigDecimal(value, MathContext.DECIMAL64).stripTrailingZeros();
        return bd.toPlainString();
    }
}
