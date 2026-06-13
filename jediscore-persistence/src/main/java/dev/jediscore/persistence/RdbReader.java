package dev.jediscore.persistence;

import dev.jediscore.datastructures.HashValue;
import dev.jediscore.datastructures.ListValue;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.datastructures.SetValue;
import dev.jediscore.datastructures.StringValue;
import dev.jediscore.datastructures.ZSetValue;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Redis RDB binary format, reconstructing keys/values and invoking a
 * callback for each.
 *
 * <p>It understands the plain types JediCore writes (string, list, set, hash,
 * ZSET/ZSET_2) and the compact encodings real Redis 7.x emits — {@code intset},
 * {@code listpack}, and {@code quicklist v2} — plus int-encoded and
 * LZF-compressed strings. Truly legacy ziplist encodings and stream/module
 * payloads are rejected with a clear error (Redis 7.x does not write them for the
 * five core types).
 */
public final class RdbReader {

    private final InputStream in;
    private final EncodingLimits limits;

    /**
     * Creates a reader.
     *
     * @param in     the RDB byte stream
     * @param limits encoding thresholds for reconstructing collections
     */
    public RdbReader(InputStream in, EncodingLimits limits) {
        this.in = in;
        this.limits = limits;
    }

    private record Len(long value, boolean encoded) {
    }

    /**
     * Parses the whole stream, invoking {@code callback} for each entry.
     *
     * @param callback receives each loaded entry
     * @throws IOException on malformed input or read failure
     */
    public void readInto(RdbLoadCallback callback) throws IOException {
        byte[] magic = readFully(5);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("REDIS")) {
            throw new IOException("not an RDB file (bad magic)");
        }
        readFully(4); // 4-digit version; we accept any we can parse

        int database = 0;
        long expireAt = -1;
        while (true) {
            int op = readByte();
            switch (op) {
                case Rdb.OP_EOF -> {
                    return; // trailing CRC follows but verification is optional
                }
                case Rdb.OP_SELECTDB -> database = (int) readLength().value;
                case Rdb.OP_RESIZEDB -> {
                    readLength();
                    readLength();
                }
                case Rdb.OP_AUX -> {
                    readString();
                    readString();
                }
                case Rdb.OP_EXPIRETIME_MS -> expireAt = readLe64();
                case Rdb.OP_EXPIRETIME -> expireAt = readLe32() * 1000L;
                case Rdb.OP_IDLE -> readLength();
                case Rdb.OP_FREQ -> readByte();
                case Rdb.OP_FUNCTION2 -> readString();
                case Rdb.OP_MODULE_AUX, Rdb.OP_FUNCTION, Rdb.OP_SLOT_INFO ->
                        throw new IOException("unsupported RDB opcode: " + op);
                default -> {
                    byte[] key = readString();
                    RedisValue value = readObject(op);
                    callback.load(database, key, value, expireAt);
                    expireAt = -1;
                }
            }
        }
    }

    // ---- value decoders -----------------------------------------------------

    private RedisValue readObject(int type) throws IOException {
        return switch (type) {
            case Rdb.TYPE_STRING -> new StringValue(readString());
            case Rdb.TYPE_LIST -> {
                long n = readLength().value;
                ListValue l = newList();
                for (long i = 0; i < n; i++) {
                    l.pushTail(readString());
                }
                yield l;
            }
            case Rdb.TYPE_SET -> {
                long n = readLength().value;
                SetValue s = newSet();
                for (long i = 0; i < n; i++) {
                    s.add(readString());
                }
                yield s;
            }
            case Rdb.TYPE_HASH -> {
                long n = readLength().value;
                HashValue h = newHash();
                for (long i = 0; i < n; i++) {
                    h.put(readString(), readString());
                }
                yield h;
            }
            case Rdb.TYPE_ZSET -> {
                long n = readLength().value;
                ZSetValue z = newZSet();
                for (long i = 0; i < n; i++) {
                    byte[] member = readString();
                    z.put(member, readOldDouble());
                }
                yield z;
            }
            case Rdb.TYPE_ZSET_2 -> {
                long n = readLength().value;
                ZSetValue z = newZSet();
                for (long i = 0; i < n; i++) {
                    byte[] member = readString();
                    z.put(member, readBinaryDouble());
                }
                yield z;
            }
            case Rdb.TYPE_SET_INTSET -> {
                SetValue s = newSet();
                for (byte[] m : parseIntset(readString())) {
                    s.add(m);
                }
                yield s;
            }
            case Rdb.TYPE_SET_LISTPACK -> {
                SetValue s = newSet();
                for (byte[] m : parseListpack(readString())) {
                    s.add(m);
                }
                yield s;
            }
            case Rdb.TYPE_HASH_LISTPACK -> {
                HashValue h = newHash();
                List<byte[]> lp = parseListpack(readString());
                for (int i = 0; i + 1 < lp.size(); i += 2) {
                    h.put(lp.get(i), lp.get(i + 1));
                }
                yield h;
            }
            case Rdb.TYPE_ZSET_LISTPACK -> {
                ZSetValue z = newZSet();
                List<byte[]> lp = parseListpack(readString());
                for (int i = 0; i + 1 < lp.size(); i += 2) {
                    z.put(lp.get(i), Double.parseDouble(new String(lp.get(i + 1), StandardCharsets.US_ASCII)));
                }
                yield z;
            }
            case Rdb.TYPE_LIST_QUICKLIST_2 -> {
                long nodes = readLength().value;
                ListValue l = newList();
                for (long i = 0; i < nodes; i++) {
                    long container = readLength().value;
                    byte[] node = readString();
                    if (container == Rdb.QUICKLIST_NODE_PLAIN) {
                        l.pushTail(node);
                    } else {
                        for (byte[] e : parseListpack(node)) {
                            l.pushTail(e);
                        }
                    }
                }
                yield l;
            }
            default -> throw new IOException("unsupported RDB value type: " + type);
        };
    }

    private ListValue newList() {
        return new ListValue(limits.listMaxSize(), limits.listMaxValue());
    }

    private SetValue newSet() {
        return new SetValue(limits.setMaxIntset(), limits.setMaxListpack(), limits.setMaxValue());
    }

    private HashValue newHash() {
        return new HashValue(limits.hashMaxEntries(), limits.hashMaxValue());
    }

    private ZSetValue newZSet() {
        return new ZSetValue(limits.zsetMaxEntries(), limits.zsetMaxValue());
    }

    // ---- compact-encoding parsers -------------------------------------------

    private static List<byte[]> parseIntset(byte[] blob) {
        int encoding = (int) le(blob, 0, 4);
        int length = (int) le(blob, 4, 4);
        List<byte[]> out = new ArrayList<>(length);
        int pos = 8;
        for (int i = 0; i < length; i++) {
            long v = signedLe(blob, pos, encoding);
            out.add(Long.toString(v).getBytes(StandardCharsets.US_ASCII));
            pos += encoding;
        }
        return out;
    }

    private static List<byte[]> parseListpack(byte[] lp) {
        List<byte[]> out = new ArrayList<>();
        int pos = 6; // 4-byte total-bytes + 2-byte element-count header
        while ((lp[pos] & 0xff) != 0xFF) {
            int b = lp[pos] & 0xff;
            byte[] value;
            int entryLen;
            if ((b & 0x80) == 0) { // 7-bit uint
                value = Long.toString(b & 0x7f).getBytes(StandardCharsets.US_ASCII);
                entryLen = 1;
            } else if ((b & 0xC0) == 0x80) { // 6-bit string
                int len = b & 0x3f;
                value = slice(lp, pos + 1, len);
                entryLen = 1 + len;
            } else if ((b & 0xE0) == 0xC0) { // 13-bit int
                int raw = ((b & 0x1f) << 8) | (lp[pos + 1] & 0xff);
                if (raw >= (1 << 12)) {
                    raw -= (1 << 13);
                }
                value = Long.toString(raw).getBytes(StandardCharsets.US_ASCII);
                entryLen = 2;
            } else if (b == 0xF1) { // 16-bit int
                value = Long.toString((short) le(lp, pos + 1, 2)).getBytes(StandardCharsets.US_ASCII);
                entryLen = 3;
            } else if (b == 0xF2) { // 24-bit int
                value = Long.toString(signedLe(lp, pos + 1, 3)).getBytes(StandardCharsets.US_ASCII);
                entryLen = 4;
            } else if (b == 0xF3) { // 32-bit int
                value = Long.toString(signedLe(lp, pos + 1, 4)).getBytes(StandardCharsets.US_ASCII);
                entryLen = 5;
            } else if (b == 0xF4) { // 64-bit int
                value = Long.toString(le(lp, pos + 1, 8)).getBytes(StandardCharsets.US_ASCII);
                entryLen = 9;
            } else if ((b & 0xF0) == 0xE0) { // 12-bit string
                int len = ((b & 0x0f) << 8) | (lp[pos + 1] & 0xff);
                value = slice(lp, pos + 2, len);
                entryLen = 2 + len;
            } else if (b == 0xF0) { // 32-bit string
                int len = (int) le(lp, pos + 1, 4);
                value = slice(lp, pos + 5, len);
                entryLen = 5 + len;
            } else {
                throw new IllegalStateException("bad listpack encoding byte: " + b);
            }
            out.add(value);
            pos += entryLen + backlenSize(entryLen);
        }
        return out;
    }

    private static int backlenSize(int entryLen) {
        if (entryLen < 128) {
            return 1;
        }
        if (entryLen < 16384) {
            return 2;
        }
        if (entryLen < (1 << 21)) {
            return 3;
        }
        if (entryLen < (1 << 28)) {
            return 4;
        }
        return 5;
    }

    private static long le(byte[] b, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= (long) (b[off + i] & 0xff) << (8 * i);
        }
        return v;
    }

    private static long signedLe(byte[] b, int off, int n) {
        long v = le(b, off, n);
        long signBit = 1L << (8 * n - 1);
        if ((v & signBit) != 0) {
            v -= (1L << (8 * n));
        }
        return v;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    // ---- primitive readers --------------------------------------------------

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("unexpected end of RDB stream");
        }
        return b;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) {
                throw new EOFException("unexpected end of RDB stream");
            }
            read += r;
        }
        return buf;
    }

    private Len readLength() throws IOException {
        int b = readByte();
        int type = (b & 0xC0) >> 6;
        if (type == Rdb.LEN_6BIT) {
            return new Len(b & 0x3f, false);
        }
        if (type == Rdb.LEN_14BIT) {
            return new Len(((long) (b & 0x3f) << 8) | readByte(), false);
        }
        if (type == Rdb.LEN_ENCVAL) {
            return new Len(b & 0x3f, true);
        }
        if (b == Rdb.LEN_32BIT) {
            byte[] x = readFully(4);
            return new Len(((long) (x[0] & 0xff) << 24) | ((x[1] & 0xff) << 16)
                    | ((x[2] & 0xff) << 8) | (x[3] & 0xff), false);
        }
        if (b == Rdb.LEN_64BIT) {
            byte[] x = readFully(8);
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (x[i] & 0xff);
            }
            return new Len(v, false);
        }
        throw new IOException("bad length encoding: " + b);
    }

    private byte[] readString() throws IOException {
        Len len = readLength();
        if (!len.encoded) {
            return readFully((int) len.value);
        }
        return switch ((int) len.value) {
            case Rdb.ENC_INT8 -> Long.toString((byte) readByte()).getBytes(StandardCharsets.US_ASCII);
            case Rdb.ENC_INT16 -> {
                byte[] x = readFully(2);
                yield Long.toString((short) ((x[0] & 0xff) | ((x[1] & 0xff) << 8)))
                        .getBytes(StandardCharsets.US_ASCII);
            }
            case Rdb.ENC_INT32 -> {
                byte[] x = readFully(4);
                int v = (x[0] & 0xff) | ((x[1] & 0xff) << 8) | ((x[2] & 0xff) << 16) | ((x[3] & 0xff) << 24);
                yield Long.toString(v).getBytes(StandardCharsets.US_ASCII);
            }
            case Rdb.ENC_LZF -> {
                int compressedLen = (int) readLength().value;
                int expandedLen = (int) readLength().value;
                byte[] compressed = readFully(compressedLen);
                yield Lzf.decompress(compressed, compressedLen, expandedLen);
            }
            default -> throw new IOException("bad string encoding: " + len.value);
        };
    }

    private long readLe64() throws IOException {
        byte[] x = readFully(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (x[i] & 0xff) << (8 * i);
        }
        return v;
    }

    private long readLe32() throws IOException {
        byte[] x = readFully(4);
        return (x[0] & 0xffL) | ((x[1] & 0xffL) << 8) | ((x[2] & 0xffL) << 16) | ((x[3] & 0xffL) << 24);
    }

    private double readBinaryDouble() throws IOException {
        return Double.longBitsToDouble(readLe64());
    }

    private double readOldDouble() throws IOException {
        int len = readByte();
        return switch (len) {
            case 255 -> Double.NEGATIVE_INFINITY;
            case 254 -> Double.POSITIVE_INFINITY;
            case 253 -> Double.NaN;
            default -> Double.parseDouble(new String(readFully(len), StandardCharsets.US_ASCII));
        };
    }
}
