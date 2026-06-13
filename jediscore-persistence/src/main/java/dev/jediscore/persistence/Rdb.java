package dev.jediscore.persistence;

/**
 * RDB format constants: the magic, version, type bytes, special opcodes, and
 * length/string encodings, as defined by Redis's {@code rdb.h}.
 *
 * <p>JediCore <em>writes</em> the plain (non-compact) type encodings — string,
 * list-of-strings, set, hash, and ZSET_2 — which every {@code redis-server}
 * version loads. It <em>reads</em> both those and the compact encodings real
 * Redis 7.x emits (intset, listpack, quicklist v2), plus int-encoded and
 * LZF-compressed strings.
 */
final class Rdb {

    private Rdb() {
    }

    static final byte[] MAGIC = "REDIS".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /** RDB version JediCore writes (Redis 7.x uses 11). */
    static final int VERSION = 11;

    // Value type bytes.
    static final int TYPE_STRING = 0;
    static final int TYPE_LIST = 1;
    static final int TYPE_SET = 2;
    static final int TYPE_ZSET = 3;
    static final int TYPE_HASH = 4;
    static final int TYPE_ZSET_2 = 5;
    static final int TYPE_SET_INTSET = 11;
    static final int TYPE_ZSET_ZIPLIST = 12;
    static final int TYPE_HASH_ZIPLIST = 13;
    static final int TYPE_LIST_QUICKLIST = 14;
    static final int TYPE_HASH_LISTPACK = 16;
    static final int TYPE_ZSET_LISTPACK = 17;
    static final int TYPE_LIST_QUICKLIST_2 = 18;
    static final int TYPE_SET_LISTPACK = 20;

    // Special opcodes (in the key stream).
    static final int OP_SLOT_INFO = 244;
    static final int OP_FUNCTION2 = 245;
    static final int OP_FUNCTION = 246;
    static final int OP_MODULE_AUX = 247;
    static final int OP_IDLE = 248;
    static final int OP_FREQ = 249;
    static final int OP_AUX = 250;
    static final int OP_RESIZEDB = 251;
    static final int OP_EXPIRETIME_MS = 252;
    static final int OP_EXPIRETIME = 253;
    static final int OP_SELECTDB = 254;
    static final int OP_EOF = 255;

    // Length encoding (top two bits of the first byte).
    static final int LEN_6BIT = 0;
    static final int LEN_14BIT = 1;
    static final int LEN_ENCVAL = 3;
    static final int LEN_32BIT = 0x80;
    static final int LEN_64BIT = 0x81;

    // Special string encodings (when LEN_ENCVAL).
    static final int ENC_INT8 = 0;
    static final int ENC_INT16 = 1;
    static final int ENC_INT32 = 2;
    static final int ENC_LZF = 3;

    // Quicklist v2 node container types.
    static final int QUICKLIST_NODE_PLAIN = 1;
    static final int QUICKLIST_NODE_PACKED = 2;
}
