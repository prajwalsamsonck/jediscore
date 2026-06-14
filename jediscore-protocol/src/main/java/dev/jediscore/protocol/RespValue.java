package dev.jediscore.protocol;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The RESP value model — a closed (sealed) algebraic type covering every RESP2
 * and RESP3 reply type.
 *
 * <p>All variants are declared as nested types so the permitted set is fixed in
 * one place and {@code switch} over a {@code RespValue} is exhaustive without a
 * default branch. Variants backed by {@code byte[]} ({@link BulkString},
 * {@link VerbatimString}, {@link BulkError}) override {@code equals}/{@code
 * hashCode} to give value semantics, which records do not provide for arrays.
 *
 * <p>RESP null is the singleton {@link #NULL}; the codec never uses a Java
 * {@code null} to mean "a RESP null" — a Java {@code null} from a parser means
 * "not enough bytes yet" instead.
 */
public sealed interface RespValue {

    // ---- RESP2 + shared types ------------------------------------------------

    /** A simple string: {@code +OK\r\n}. Cannot contain CR or LF. */
    record SimpleString(String value) implements RespValue {}

    /** An error: {@code -ERR something\r\n}. The message excludes the leading '-'. */
    record SimpleError(String message) implements RespValue {}

    /** A 64-bit signed integer: {@code :42\r\n}. */
    record Integer(long value) implements RespValue {}

    /** A binary-safe bulk string: {@code $3\r\nfoo\r\n}. Never {@code null}; use {@link #NULL}. */
    record BulkString(byte[] data) implements RespValue {
        @Override public boolean equals(Object o) {
            return o instanceof BulkString b && Arrays.equals(data, b.data);
        }
        @Override public int hashCode() {
            return Arrays.hashCode(data);
        }
        @Override public String toString() {
            return "BulkString[" + data.length + "B]";
        }
    }

    /** An array: {@code *2\r\n...}. */
    record Array(List<RespValue> items) implements RespValue {}

    /** RESP null. RESP3 renders {@code _\r\n}; RESP2 renders {@code $-1\r\n}. */
    record Null() implements RespValue {}

    /**
     * A null <em>array</em>. RESP3 renders {@code _\r\n} (unified null); RESP2
     * renders {@code *-1\r\n}, which is distinct on the wire from a null bulk
     * ({@code $-1}). Used where Redis replies with a nil multibulk — {@code EXEC}
     * on a CAS failure, and blocking commands on timeout.
     */
    record NullArray() implements RespValue {}

    // ---- RESP3 additions -----------------------------------------------------

    /** A double: {@code ,3.14\r\n}. Downgrades to a bulk string in RESP2. */
    record Double(double value) implements RespValue {}

    /** A boolean: {@code #t\r\n} / {@code #f\r\n}. Downgrades to {@code :1}/{@code :0} in RESP2. */
    record Boolean(boolean value) implements RespValue {}

    /** A big number: {@code (3492890328409238509324850943850943825024385\r\n}. Bulk string in RESP2. */
    record BigNumber(BigInteger value) implements RespValue {}

    /** A verbatim string with a 3-char format: {@code =15\r\ntxt:Some string\r\n}. Bulk in RESP2. */
    record VerbatimString(String format, byte[] data) implements RespValue {
        @Override public boolean equals(Object o) {
            return o instanceof VerbatimString v && format.equals(v.format) && Arrays.equals(data, v.data);
        }
        @Override public int hashCode() {
            return 31 * format.hashCode() + Arrays.hashCode(data);
        }
        @Override public String toString() {
            return "VerbatimString[" + format + "," + data.length + "B]";
        }
    }

    /** A blob (bulk) error: {@code !21\r\nSYNTAX bad arguments\r\n}. Simple error in RESP2. */
    record BulkError(byte[] data) implements RespValue {
        @Override public boolean equals(Object o) {
            return o instanceof BulkError b && Arrays.equals(data, b.data);
        }
        @Override public int hashCode() {
            return Arrays.hashCode(data);
        }
        @Override public String toString() {
            return "BulkError[" + data.length + "B]";
        }
    }

    /** A single key/value pair within a {@link Map} or {@link Attribute}. */
    record MapEntry(RespValue key, RespValue value) {}

    /** A map: {@code %2\r\n...}. Order-preserving. Flattened to an array in RESP2. */
    record Map(List<MapEntry> entries) implements RespValue {}

    /** A set: {@code ~3\r\n...}. Order-preserving here. Array in RESP2. */
    record Set(List<RespValue> items) implements RespValue {}

    /** An out-of-band push message: {@code >3\r\n...}. Array in RESP2. */
    record Push(List<RespValue> items) implements RespValue {}

    /** Attribute metadata preceding a value: {@code |1\r\n...}. Dropped (attached value only) in RESP2. */
    record Attribute(List<MapEntry> entries, RespValue attached) implements RespValue {}

    // ---- Singletons & factories ---------------------------------------------

    /** The canonical RESP null instance. */
    RespValue NULL = new Null();

    /** The canonical RESP null-array instance (nil multibulk). */
    RespValue NULL_ARRAY = new NullArray();

    /** Pre-built {@code +OK}. */
    RespValue OK = new SimpleString("OK");

    /** Pre-built {@code +PONG}. */
    RespValue PONG = new SimpleString("PONG");

    /**
     * Wraps a UTF-8 string as a bulk string.
     *
     * @param s the string (must not be {@code null})
     * @return the bulk string value
     */
    static RespValue bulk(String s) {
        return new BulkString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Wraps raw bytes as a bulk string.
     *
     * @param b the bytes (must not be {@code null})
     * @return the bulk string value
     */
    static RespValue bulk(byte[] b) {
        return new BulkString(b);
    }

    /**
     * Creates an integer reply.
     *
     * @param v the value
     * @return the integer value
     */
    static RespValue integer(long v) {
        return new Integer(v);
    }

    /**
     * Creates a simple string reply.
     *
     * @param s the status text (no CR/LF)
     * @return the simple string value
     */
    static RespValue simple(String s) {
        return new SimpleString(s);
    }

    /**
     * Creates an error reply. The message should start with an uppercase error
     * code (for example {@code "ERR ..."} or {@code "WRONGTYPE ..."}).
     *
     * @param message the error text without the leading '-'
     * @return the error value
     */
    static RespValue error(String message) {
        return new SimpleError(message);
    }
}
