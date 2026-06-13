package dev.jediscore.persistence;

import dev.jediscore.datastructures.HashValue;
import dev.jediscore.datastructures.ListValue;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.datastructures.ScoredMember;
import dev.jediscore.datastructures.SetValue;
import dev.jediscore.datastructures.StringValue;
import dev.jediscore.datastructures.ZSetValue;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes a {@link RdbSnapshot} to the Redis RDB binary format.
 *
 * <p>It writes the <em>plain</em> type encodings (string, list-of-strings, set,
 * hash, ZSET_2 with binary scores), which every {@code redis-server} loads, and
 * the standard {@code "REDIS0011"} header, per-key expiry opcodes, the {@code EOF}
 * opcode, and the trailing little-endian CRC-64 that Redis verifies on load.
 */
public final class RdbWriter {

    private RdbWriter() {
        // Static utility; not instantiable.
    }

    /**
     * Writes a snapshot as an RDB stream (header, body, EOF, CRC).
     *
     * @param snapshot the snapshot
     * @param target   the destination stream
     * @throws IOException on write failure
     */
    public static void write(RdbSnapshot snapshot, OutputStream target) throws IOException {
        Crc64OutputStream out = new Crc64OutputStream(target);

        out.write(Rdb.MAGIC);
        out.write(String.format("%04d", Rdb.VERSION).getBytes(StandardCharsets.US_ASCII));
        writeAux(out, "redis-ver", "7.4.0");
        writeAux(out, "redis-bits", "64");

        for (RdbSnapshot.DatabaseSnapshot db : snapshot.databases()) {
            if (db.entries().isEmpty()) {
                continue;
            }
            out.write(Rdb.OP_SELECTDB);
            writeLen(out, db.index());
            out.write(Rdb.OP_RESIZEDB);
            writeLen(out, db.entries().size());
            writeLen(out, countWithExpiry(db.entries()));
            for (RdbSnapshot.Entry entry : db.entries()) {
                if (entry.expireAtMs() >= 0) {
                    out.write(Rdb.OP_EXPIRETIME_MS);
                    writeLe64(out, entry.expireAtMs());
                }
                writeValue(out, entry.key(), entry.value());
            }
        }

        out.write(Rdb.OP_EOF);
        // The CRC itself is written raw (not folded into the running CRC).
        long crc = out.crc();
        writeLe64(target, crc);
        target.flush();
    }

    private static int countWithExpiry(List<RdbSnapshot.Entry> entries) {
        int n = 0;
        for (RdbSnapshot.Entry e : entries) {
            if (e.expireAtMs() >= 0) {
                n++;
            }
        }
        return n;
    }

    private static void writeAux(OutputStream out, String key, String value) throws IOException {
        out.write(Rdb.OP_AUX);
        writeString(out, key.getBytes(StandardCharsets.US_ASCII));
        writeString(out, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeValue(OutputStream out, byte[] key, RedisValue value) throws IOException {
        switch (value) {
            case StringValue s -> {
                out.write(Rdb.TYPE_STRING);
                writeString(out, key);
                writeString(out, s.get());
            }
            case ListValue l -> {
                out.write(Rdb.TYPE_LIST);
                writeString(out, key);
                List<byte[]> elements = l.range(0, -1);
                writeLen(out, elements.size());
                for (byte[] e : elements) {
                    writeString(out, e);
                }
            }
            case SetValue set -> {
                out.write(Rdb.TYPE_SET);
                writeString(out, key);
                List<byte[]> members = set.members();
                writeLen(out, members.size());
                for (byte[] m : members) {
                    writeString(out, m);
                }
            }
            case HashValue h -> {
                out.write(Rdb.TYPE_HASH);
                writeString(out, key);
                List<byte[]> flat = h.entriesFlattened();
                writeLen(out, flat.size() / 2);
                for (byte[] piece : flat) {
                    writeString(out, piece);
                }
            }
            case ZSetValue z -> {
                out.write(Rdb.TYPE_ZSET_2);
                writeString(out, key);
                List<ScoredMember> members = z.ascending();
                writeLen(out, members.size());
                for (ScoredMember m : members) {
                    writeString(out, m.member());
                    writeBinaryDouble(out, m.score());
                }
            }
        }
    }

    static void writeLen(OutputStream out, long len) throws IOException {
        if (len < (1 << 6)) {
            out.write((Rdb.LEN_6BIT << 6) | (int) len);
        } else if (len < (1 << 14)) {
            out.write((Rdb.LEN_14BIT << 6) | (int) (len >> 8));
            out.write((int) (len & 0xff));
        } else if (len <= 0xFFFFFFFFL) {
            out.write(Rdb.LEN_32BIT);
            out.write((int) (len >>> 24));
            out.write((int) (len >>> 16));
            out.write((int) (len >>> 8));
            out.write((int) len);
        } else {
            out.write(Rdb.LEN_64BIT);
            writeBe64(out, len);
        }
    }

    static void writeString(OutputStream out, byte[] s) throws IOException {
        writeLen(out, s.length);
        out.write(s);
    }

    private static void writeBinaryDouble(OutputStream out, double d) throws IOException {
        writeLe64(out, Double.doubleToLongBits(d));
    }

    private static void writeLe64(OutputStream out, long v) throws IOException {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((v >>> (8 * i)) & 0xff));
        }
    }

    private static void writeBe64(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((v >>> (8 * i)) & 0xff));
        }
    }
}
