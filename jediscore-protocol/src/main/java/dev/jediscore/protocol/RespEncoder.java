package dev.jediscore.protocol;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serialises {@link RespValue}s onto a Netty {@link ByteBuf}, honouring the
 * target {@link RespVersion}.
 *
 * <p>RESP3-only types are written natively when the connection negotiated RESP3
 * and otherwise <em>downgraded</em> to their RESP2 representation — the same
 * thing real Redis does so that a RESP2 client always receives something it can
 * parse:
 * <ul>
 *   <li>{@code Null} → {@code $-1} (RESP2) / {@code _} (RESP3)</li>
 *   <li>{@code Boolean} → {@code :1}/{@code :0} (RESP2) / {@code #t}/{@code #f}</li>
 *   <li>{@code Double}, {@code BigNumber}, {@code VerbatimString} → bulk string (RESP2)</li>
 *   <li>{@code Map} → flattened array (RESP2)</li>
 *   <li>{@code Set}, {@code Push} → array (RESP2)</li>
 *   <li>{@code Attribute} → only the attached value (RESP2)</li>
 * </ul>
 *
 * <p>Integers and bulk lengths are written digit-by-digit straight into the
 * buffer to avoid the intermediate {@code String}/{@code byte[]} allocation a
 * {@code Long.toString} would incur on the reply hot path.
 */
public final class RespEncoder {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_RESP2 = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_RESP3 = "_\r\n".getBytes(StandardCharsets.US_ASCII);

    private RespEncoder() {
        // Static utility; not instantiable.
    }

    /**
     * Encodes a value at the given protocol version.
     *
     * @param value   the value to encode (must not be {@code null})
     * @param out     the destination buffer
     * @param version the negotiated protocol version
     */
    public static void encode(RespValue value, ByteBuf out, RespVersion version) {
        boolean resp3 = version == RespVersion.RESP3;
        switch (value) {
            case RespValue.SimpleString s -> {
                out.writeByte('+');
                writeText(out, s.value());
                out.writeBytes(CRLF);
            }
            case RespValue.SimpleError e -> {
                out.writeByte('-');
                writeText(out, e.message());
                out.writeBytes(CRLF);
            }
            case RespValue.Integer i -> {
                out.writeByte(':');
                writeLong(out, i.value());
                out.writeBytes(CRLF);
            }
            case RespValue.BulkString b -> writeBulk(out, b.data());
            case RespValue.Array a -> writeAggregate(out, '*', a.items(), version);
            case RespValue.Null ignored -> out.writeBytes(resp3 ? NULL_RESP3 : NULL_RESP2);
            case RespValue.Double d -> {
                if (resp3) {
                    out.writeByte(',');
                    writeText(out, formatDouble(d.value()));
                    out.writeBytes(CRLF);
                } else {
                    writeBulkText(out, formatDouble(d.value()));
                }
            }
            case RespValue.Boolean bool -> {
                if (resp3) {
                    out.writeByte('#');
                    out.writeByte(bool.value() ? 't' : 'f');
                    out.writeBytes(CRLF);
                } else {
                    out.writeByte(':');
                    out.writeByte(bool.value() ? '1' : '0');
                    out.writeBytes(CRLF);
                }
            }
            case RespValue.BigNumber n -> {
                if (resp3) {
                    out.writeByte('(');
                    writeText(out, n.value().toString());
                    out.writeBytes(CRLF);
                } else {
                    writeBulkText(out, n.value().toString());
                }
            }
            case RespValue.VerbatimString v -> {
                if (resp3) {
                    // The 3-char format + ':' prefix counts toward the length.
                    out.writeByte('=');
                    writeLong(out, v.data().length + 4L);
                    out.writeBytes(CRLF);
                    writeText(out, v.format());
                    out.writeByte(':');
                    out.writeBytes(v.data());
                    out.writeBytes(CRLF);
                } else {
                    writeBulk(out, v.data());
                }
            }
            case RespValue.BulkError be -> {
                if (resp3) {
                    out.writeByte('!');
                    writeLong(out, be.data().length);
                    out.writeBytes(CRLF);
                    out.writeBytes(be.data());
                    out.writeBytes(CRLF);
                } else {
                    out.writeByte('-');
                    out.writeBytes(be.data());
                    out.writeBytes(CRLF);
                }
            }
            case RespValue.Map m -> writeMap(out, m.entries(), version);
            case RespValue.Set s -> writeAggregate(out, resp3 ? '~' : '*', s.items(), version);
            case RespValue.Push p -> writeAggregate(out, resp3 ? '>' : '*', p.items(), version);
            case RespValue.Attribute attr -> {
                if (resp3) {
                    out.writeByte('|');
                    writeLong(out, attr.entries().size());
                    out.writeBytes(CRLF);
                    for (RespValue.MapEntry entry : attr.entries()) {
                        encode(entry.key(), out, version);
                        encode(entry.value(), out, version);
                    }
                }
                // In RESP2 attributes are invisible; emit only the attached value.
                encode(attr.attached(), out, version);
            }
        }
    }

    /** Convenience overload that defaults to RESP2. */
    public static void encode(RespValue value, ByteBuf out) {
        encode(value, out, RespVersion.RESP2);
    }

    private static void writeAggregate(ByteBuf out, char marker, List<RespValue> items, RespVersion version) {
        out.writeByte(marker);
        writeLong(out, items.size());
        out.writeBytes(CRLF);
        for (RespValue item : items) {
            encode(item, out, version);
        }
    }

    private static void writeMap(ByteBuf out, List<RespValue.MapEntry> entries, RespVersion version) {
        if (version == RespVersion.RESP3) {
            out.writeByte('%');
            writeLong(out, entries.size());
            out.writeBytes(CRLF);
        } else {
            // RESP2 has no map type: flatten to a 2N-element array.
            out.writeByte('*');
            writeLong(out, entries.size() * 2L);
            out.writeBytes(CRLF);
        }
        for (RespValue.MapEntry entry : entries) {
            encode(entry.key(), out, version);
            encode(entry.value(), out, version);
        }
    }

    private static void writeBulk(ByteBuf out, byte[] data) {
        out.writeByte('$');
        writeLong(out, data.length);
        out.writeBytes(CRLF);
        out.writeBytes(data);
        out.writeBytes(CRLF);
    }

    private static void writeBulkText(ByteBuf out, String text) {
        writeBulk(out, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeText(ByteBuf out, String text) {
        // Simple strings/errors are line-oriented; callers guarantee no CR/LF.
        out.writeCharSequence(text, StandardCharsets.UTF_8);
    }

    /**
     * Writes a signed long as ASCII digits with no intermediate allocation.
     */
    private static void writeLong(ByteBuf out, long value) {
        if (value == 0) {
            out.writeByte('0');
            return;
        }
        if (value < 0) {
            out.writeByte('-');
            // Long.MIN_VALUE cannot be negated; defer to the JDK for that one case.
            if (value == Long.MIN_VALUE) {
                out.writeCharSequence(Long.toString(value).substring(1), StandardCharsets.US_ASCII);
                return;
            }
            value = -value;
        }
        // Render digits into a small stack buffer, most-significant first.
        byte[] tmp = new byte[20];
        int pos = tmp.length;
        while (value > 0) {
            tmp[--pos] = (byte) ('0' + (int) (value % 10));
            value /= 10;
        }
        out.writeBytes(tmp, pos, tmp.length - pos);
    }

    /**
     * Formats a double the way RESP3 expects, with the {@code inf}/{@code -inf}/
     * {@code nan} special cases. For finite values we use {@link java.lang.Double#toString}
     * which round-trips exactly; Redis's precise {@code %.17Lg} formatting for
     * sorted-set scores is a Phase-4 concern and noted in COMPATIBILITY.md.
     */
    static String formatDouble(double value) {
        if (java.lang.Double.isNaN(value)) {
            return "nan";
        }
        if (value == java.lang.Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (value == java.lang.Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        return java.lang.Double.toString(value);
    }
}
