package dev.jediscore.persistence;

/**
 * CRC-64 using the Jones polynomial in reflected form, matching Redis's
 * {@code crc64.c} (which is what the RDB trailer checksum uses).
 *
 * <p>The algorithm is a standard reflected, table-driven CRC with polynomial
 * {@code 0xad93d23594c935a9}, initial value 0, and no final XOR — identical to
 * Redis, so the checksum we write is the one {@code redis-server} verifies on
 * load (and vice versa).
 */
public final class Crc64 {

    // CRC-64/redis is a *reflected* CRC: the table is built from the bit-reversed
    // polynomial. The catalogue (normal) polynomial is 0xad93d23594c935a9.
    private static final long POLY = Long.reverse(0xad93d23594c935a9L);
    private static final long[] TABLE = new long[256];

    static {
        for (int n = 0; n < 256; n++) {
            long crc = n;
            for (int k = 0; k < 8; k++) {
                crc = ((crc & 1) != 0) ? (crc >>> 1) ^ POLY : (crc >>> 1);
            }
            TABLE[n] = crc;
        }
    }

    private Crc64() {
        // Static utility; not instantiable.
    }

    /**
     * Updates a running CRC-64 with a range of bytes.
     *
     * @param crc the current CRC (0 to start)
     * @param data the bytes
     * @param offset the start offset
     * @param length the number of bytes
     * @return the updated CRC
     */
    public static long update(long crc, byte[] data, int offset, int length) {
        long c = crc;
        for (int i = 0; i < length; i++) {
            c = TABLE[(int) ((c ^ data[offset + i]) & 0xff)] ^ (c >>> 8);
        }
        return c;
    }

    /**
     * Updates a running CRC-64 with a single byte.
     *
     * @param crc the current CRC
     * @param b   the byte
     * @return the updated CRC
     */
    public static long update(long crc, int b) {
        return TABLE[(int) ((crc ^ b) & 0xff)] ^ (crc >>> 8);
    }
}
