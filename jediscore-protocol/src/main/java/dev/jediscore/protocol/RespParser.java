package dev.jediscore.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ByteProcessor;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental RESP parser that reads directly from a Netty {@link ByteBuf}.
 *
 * <p>There are two entry points:
 * <ul>
 *   <li>{@link #parse(ByteBuf)} decodes <em>any</em> RESP2/RESP3 value. It is
 *       used for replies, round-trip tests, and (later) the replication/client
 *       paths.</li>
 *   <li>{@link #parseRequest(ByteBuf)} decodes a <em>client request</em> — either
 *       a {@code *}-prefixed multibulk or a plain inline line — into raw argument
 *       bytes, with no {@link RespValue} allocation. This is the server hot path.</li>
 * </ul>
 *
 * <p><strong>Fragmentation contract.</strong> Both methods are all-or-nothing:
 * if the buffer does not yet hold a complete frame they return {@code null} and
 * leave the reader index unchanged, so the caller can simply wait for more bytes
 * and retry. A Java {@code null} therefore always means "incomplete"; a genuine
 * RESP null value is {@link RespValue#NULL}. Malformed input throws
 * {@link ProtocolException}.
 */
public final class RespParser {

    /** Hard cap on a multibulk element count, matching Redis's 1M default. */
    public static final int MAX_MULTIBULK = 1024 * 1024;

    /** Hard cap on a bulk string length: 512 MB, matching Redis's proto-max-bulk-len. */
    public static final long MAX_BULK = 512L * 1024 * 1024;

    /** Hard cap on an inline request line: 64 KB, matching Redis. */
    public static final int MAX_INLINE = 64 * 1024;

    private static final byte[][] EMPTY_ARGS = new byte[0][];

    private RespParser() {
        // Static utility; not instantiable.
    }

    // =========================================================================
    // Request path (server-side): multibulk or inline -> byte[][]
    // =========================================================================

    /**
     * Parses a single client request.
     *
     * @param in the input buffer
     * @return the request arguments; a zero-length array for an empty frame
     *         (which the caller should treat as "no command"); or {@code null}
     *         if more bytes are needed
     * @throws ProtocolException if the bytes violate the RESP request grammar
     */
    public static byte[][] parseRequest(ByteBuf in) {
        if (!in.isReadable()) {
            return null;
        }
        int start = in.readerIndex();
        byte first = in.getByte(start);
        byte[][] result = (first == '*') ? parseMultibulk(in) : parseInline(in);
        if (result == null) {
            in.readerIndex(start);
        }
        return result;
    }

    private static byte[][] parseMultibulk(ByteBuf in) {
        in.skipBytes(1); // consume '*'
        Long count = readIntegerLine(in, "invalid multibulk length");
        if (count == null) {
            return null;
        }
        if (count <= 0) {
            // *0 or *-1: a well-formed but empty request; Redis treats it as a no-op.
            return EMPTY_ARGS;
        }
        if (count > MAX_MULTIBULK) {
            throw new ProtocolException("invalid multibulk length");
        }
        byte[][] args = new byte[count.intValue()][];
        for (int i = 0; i < args.length; i++) {
            if (!in.isReadable()) {
                return null;
            }
            byte marker = in.getByte(in.readerIndex());
            if (marker != '$') {
                throw new ProtocolException("expected '$', got '" + (char) marker + "'");
            }
            in.skipBytes(1);
            Long len = readIntegerLine(in, "invalid bulk length");
            if (len == null) {
                return null;
            }
            if (len < 0 || len > MAX_BULK) {
                throw new ProtocolException("invalid bulk length");
            }
            byte[] data = readBulkBody(in, len.intValue());
            if (data == null) {
                return null;
            }
            args[i] = data;
        }
        return args;
    }

    private static byte[][] parseInline(ByteBuf in) {
        int lf = in.forEachByte(ByteProcessor.FIND_LF);
        if (lf == -1) {
            // No line terminator yet. Guard against an unbounded inline line.
            if (in.readableBytes() > MAX_INLINE) {
                throw new ProtocolException("too big inline request");
            }
            return null;
        }
        int start = in.readerIndex();
        int lineLen = lf - start;
        if (lineLen > MAX_INLINE) {
            throw new ProtocolException("too big inline request");
        }
        // Strip a trailing '\r' if present (tolerate bare '\n' line endings).
        int contentLen = lineLen;
        if (contentLen > 0 && in.getByte(start + contentLen - 1) == '\r') {
            contentLen--;
        }
        String line = in.toString(start, contentLen, StandardCharsets.UTF_8);
        in.readerIndex(lf + 1);

        List<String> tokens = splitInlineArgs(line);
        if (tokens == null) {
            throw new ProtocolException("unbalanced quotes in request");
        }
        if (tokens.isEmpty()) {
            return EMPTY_ARGS;
        }
        byte[][] args = new byte[tokens.size()][];
        for (int i = 0; i < args.length; i++) {
            args[i] = tokens.get(i).getBytes(StandardCharsets.UTF_8);
        }
        return args;
    }

    /**
     * Splits an inline command line into arguments, mirroring Redis's
     * {@code sdssplitargs}: whitespace-separated tokens, with double-quote and
     * single-quote handling and the usual escape sequences inside double quotes.
     *
     * @param line the line (without its terminator)
     * @return the tokens, or {@code null} if a quote is left unbalanced
     */
    static List<String> splitInlineArgs(String line) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            // Skip leading whitespace between tokens.
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            StringBuilder cur = new StringBuilder();
            boolean done = false;
            while (i < n && !done) {
                char c = line.charAt(i);
                if (c == '"') {
                    i++;
                    while (i < n) {
                        char q = line.charAt(i);
                        if (q == '\\' && i + 1 < n) {
                            char e = line.charAt(i + 1);
                            switch (e) {
                                case 'n' -> cur.append('\n');
                                case 'r' -> cur.append('\r');
                                case 't' -> cur.append('\t');
                                case 'b' -> cur.append('\b');
                                case 'a' -> cur.append((char) 7);
                                case 'x' -> {
                                    if (i + 3 < n && isHex(line.charAt(i + 2)) && isHex(line.charAt(i + 3))) {
                                        cur.append((char) ((hex(line.charAt(i + 2)) << 4) | hex(line.charAt(i + 3))));
                                        i += 2;
                                    } else {
                                        cur.append('x');
                                    }
                                }
                                default -> cur.append(e);
                            }
                            i += 2;
                        } else if (q == '"') {
                            i++;
                            // A closing quote must be followed by whitespace or end.
                            if (i < n && !Character.isWhitespace(line.charAt(i))) {
                                return null;
                            }
                            done = true;
                            break;
                        } else {
                            cur.append(q);
                            i++;
                        }
                    }
                    if (!done && (i >= n)) {
                        return null; // unterminated double quote
                    }
                } else if (c == '\'') {
                    i++;
                    while (i < n) {
                        char q = line.charAt(i);
                        if (q == '\\' && i + 1 < n && line.charAt(i + 1) == '\'') {
                            cur.append('\'');
                            i += 2;
                        } else if (q == '\'') {
                            i++;
                            if (i < n && !Character.isWhitespace(line.charAt(i))) {
                                return null;
                            }
                            done = true;
                            break;
                        } else {
                            cur.append(q);
                            i++;
                        }
                    }
                    if (!done && (i >= n)) {
                        return null; // unterminated single quote
                    }
                } else if (Character.isWhitespace(c)) {
                    done = true;
                } else {
                    cur.append(c);
                    i++;
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return c - 'A' + 10;
    }

    // =========================================================================
    // General path: any RESP2/RESP3 value
    // =========================================================================

    /**
     * Parses any RESP value.
     *
     * @param in the input buffer
     * @return the parsed value, or {@code null} if more bytes are needed
     * @throws ProtocolException if the bytes are not valid RESP
     */
    public static RespValue parse(ByteBuf in) {
        int start = in.readerIndex();
        RespValue v = parseValue(in);
        if (v == null) {
            in.readerIndex(start);
        }
        return v;
    }

    private static RespValue parseValue(ByteBuf in) {
        if (!in.isReadable()) {
            return null;
        }
        byte type = in.readByte();
        return switch (type) {
            case '+' -> {
                String s = readLine(in);
                yield s == null ? null : new RespValue.SimpleString(s);
            }
            case '-' -> {
                String s = readLine(in);
                yield s == null ? null : new RespValue.SimpleError(s);
            }
            case ':' -> {
                Long v = readIntegerLine(in, "invalid integer");
                yield v == null ? null : new RespValue.Integer(v);
            }
            case '$' -> parseBulk(in, false);
            case '=' -> parseBulk(in, true);
            case '!' -> parseBulkError(in);
            case '*' -> parseAggregate(in, AggregateKind.ARRAY);
            case '~' -> parseAggregate(in, AggregateKind.SET);
            case '>' -> parseAggregate(in, AggregateKind.PUSH);
            case '%' -> parseMap(in, false);
            case '|' -> parseMap(in, true);
            case '_' -> {
                String s = readLine(in); // consume the (empty) line
                yield s == null ? null : RespValue.NULL;
            }
            case ',' -> {
                String s = readLine(in);
                yield s == null ? null : new RespValue.Double(parseDouble(s));
            }
            case '#' -> {
                String s = readLine(in);
                if (s == null) {
                    yield null;
                }
                if (s.length() != 1 || (s.charAt(0) != 't' && s.charAt(0) != 'f')) {
                    throw new ProtocolException("invalid boolean");
                }
                yield new RespValue.Boolean(s.charAt(0) == 't');
            }
            case '(' -> {
                String s = readLine(in);
                if (s == null) {
                    yield null;
                }
                try {
                    yield new RespValue.BigNumber(new BigInteger(s));
                } catch (NumberFormatException e) {
                    throw new ProtocolException("invalid big number");
                }
            }
            default -> throw new ProtocolException("expected a RESP type byte, got '" + (char) type + "'");
        };
    }

    private enum AggregateKind { ARRAY, SET, PUSH }

    private static RespValue parseAggregate(ByteBuf in, AggregateKind kind) {
        Long count = readIntegerLine(in, "invalid multibulk length");
        if (count == null) {
            return null;
        }
        if (count == -1) {
            return RespValue.NULL;
        }
        if (count < 0 || count > MAX_MULTIBULK) {
            throw new ProtocolException("invalid multibulk length");
        }
        List<RespValue> items = new ArrayList<>(count.intValue());
        for (int i = 0; i < count; i++) {
            RespValue item = parseValue(in);
            if (item == null) {
                return null;
            }
            items.add(item);
        }
        return switch (kind) {
            case ARRAY -> new RespValue.Array(items);
            case SET -> new RespValue.Set(items);
            case PUSH -> new RespValue.Push(items);
        };
    }

    private static RespValue parseMap(ByteBuf in, boolean attribute) {
        Long pairs = readIntegerLine(in, "invalid map length");
        if (pairs == null) {
            return null;
        }
        if (pairs < 0 || pairs > MAX_MULTIBULK) {
            throw new ProtocolException("invalid map length");
        }
        List<RespValue.MapEntry> entries = new ArrayList<>(pairs.intValue());
        for (int i = 0; i < pairs; i++) {
            RespValue key = parseValue(in);
            if (key == null) {
                return null;
            }
            RespValue value = parseValue(in);
            if (value == null) {
                return null;
            }
            entries.add(new RespValue.MapEntry(key, value));
        }
        if (!attribute) {
            return new RespValue.Map(entries);
        }
        // Attributes are followed by the value they annotate.
        RespValue attached = parseValue(in);
        if (attached == null) {
            return null;
        }
        return new RespValue.Attribute(entries, attached);
    }

    private static RespValue parseBulk(ByteBuf in, boolean verbatim) {
        Long len = readIntegerLine(in, "invalid bulk length");
        if (len == null) {
            return null;
        }
        if (len == -1) {
            return RespValue.NULL;
        }
        if (len < 0 || len > MAX_BULK) {
            throw new ProtocolException("invalid bulk length");
        }
        byte[] data = readBulkBody(in, len.intValue());
        if (data == null) {
            return null;
        }
        if (!verbatim) {
            return new RespValue.BulkString(data);
        }
        // Verbatim payload is "fmt:content"; split off the 3-char format.
        if (data.length < 4 || data[3] != ':') {
            throw new ProtocolException("invalid verbatim string");
        }
        String format = new String(data, 0, 3, StandardCharsets.US_ASCII);
        byte[] content = new byte[data.length - 4];
        System.arraycopy(data, 4, content, 0, content.length);
        return new RespValue.VerbatimString(format, content);
    }

    private static RespValue parseBulkError(ByteBuf in) {
        Long len = readIntegerLine(in, "invalid bulk length");
        if (len == null) {
            return null;
        }
        if (len < 0 || len > MAX_BULK) {
            throw new ProtocolException("invalid bulk length");
        }
        byte[] data = readBulkBody(in, len.intValue());
        if (data == null) {
            return null;
        }
        return new RespValue.BulkError(data);
    }

    // =========================================================================
    // Shared low-level helpers
    // =========================================================================

    /**
     * Reads {@code len} body bytes followed by a mandatory CRLF.
     *
     * @return the body, or {@code null} if the full body+CRLF isn't buffered yet
     */
    private static byte[] readBulkBody(ByteBuf in, int len) {
        if (in.readableBytes() < (long) len + 2) {
            return null;
        }
        byte[] data = new byte[len];
        in.readBytes(data);
        if (in.readByte() != '\r' || in.readByte() != '\n') {
            throw new ProtocolException("bulk body not terminated by CRLF");
        }
        return data;
    }

    /**
     * Reads a CRLF-terminated line and returns its text (without the terminator),
     * or {@code null} if no line terminator is buffered yet. A trailing '\r' is
     * stripped; a bare '\n' is tolerated.
     */
    private static String readLine(ByteBuf in) {
        int lf = in.forEachByte(ByteProcessor.FIND_LF);
        if (lf == -1) {
            return null;
        }
        int start = in.readerIndex();
        int contentLen = lf - start;
        if (contentLen > 0 && in.getByte(start + contentLen - 1) == '\r') {
            contentLen--;
        }
        String s = in.toString(start, contentLen, StandardCharsets.UTF_8);
        in.readerIndex(lf + 1);
        return s;
    }

    /**
     * Reads a CRLF-terminated signed integer line straight off the buffer with no
     * String allocation.
     *
     * @param in     the buffer
     * @param errMsg the protocol-error reason to use on a malformed number
     * @return the value, or {@code null} if the line isn't fully buffered
     */
    private static Long readIntegerLine(ByteBuf in, String errMsg) {
        int lf = in.forEachByte(ByteProcessor.FIND_LF);
        if (lf == -1) {
            return null;
        }
        int p = in.readerIndex();
        int end = lf;
        boolean negative = false;
        if (p < end && in.getByte(p) == '-') {
            negative = true;
            p++;
        }
        // Accumulate as a negative magnitude (like Long.parseLong) so that
        // Long.MIN_VALUE — whose positive magnitude overflows — round-trips.
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10;
        long result = 0;
        boolean anyDigit = false;
        for (; p < end; p++) {
            int c = in.getByte(p);
            if (c == '\r') {
                break;
            }
            if (c < '0' || c > '9') {
                throw new ProtocolException(errMsg);
            }
            if (result < multmin) {
                throw new ProtocolException(errMsg);
            }
            result *= 10;
            int digit = c - '0';
            if (result < limit + digit) {
                throw new ProtocolException(errMsg);
            }
            result -= digit;
            anyDigit = true;
        }
        if (!anyDigit) {
            throw new ProtocolException(errMsg);
        }
        in.readerIndex(lf + 1);
        return negative ? result : -result;
    }

    private static double parseDouble(String s) {
        return switch (s) {
            case "inf", "+inf" -> java.lang.Double.POSITIVE_INFINITY;
            case "-inf" -> java.lang.Double.NEGATIVE_INFINITY;
            case "nan", "-nan" -> java.lang.Double.NaN;
            default -> {
                try {
                    yield java.lang.Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new ProtocolException("invalid double");
                }
            }
        };
    }
}
