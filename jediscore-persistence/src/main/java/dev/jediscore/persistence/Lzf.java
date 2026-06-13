package dev.jediscore.persistence;

/**
 * LZF decompression, compatible with the liblzf variant Redis uses to compress
 * RDB strings (when {@code rdbcompression} is on). Only decompression is needed:
 * JediCore writes uncompressed strings, but must read compressed ones from real
 * Redis dumps.
 */
final class Lzf {

    private Lzf() {
    }

    /**
     * Decompresses an LZF block.
     *
     * @param in            the compressed bytes
     * @param compressedLen the number of valid compressed bytes
     * @param expandedLen   the known decompressed length
     * @return the decompressed bytes
     */
    static byte[] decompress(byte[] in, int compressedLen, int expandedLen) {
        byte[] out = new byte[expandedLen];
        int ip = 0;
        int op = 0;
        while (ip < compressedLen) {
            int ctrl = in[ip++] & 0xff;
            if (ctrl < 32) {
                // Literal run of (ctrl + 1) bytes.
                int len = ctrl + 1;
                System.arraycopy(in, ip, out, op, len);
                ip += len;
                op += len;
            } else {
                // Back-reference: length in the top 3 bits, distance in the rest.
                int len = ctrl >> 5;
                if (len == 7) {
                    len += in[ip++] & 0xff;
                }
                int ref = op - ((ctrl & 0x1f) << 8) - (in[ip++] & 0xff) - 1;
                int count = len + 2;
                for (int i = 0; i < count; i++) {
                    out[op++] = out[ref++]; // may overlap; copy byte-by-byte
                }
            }
        }
        return out;
    }
}
