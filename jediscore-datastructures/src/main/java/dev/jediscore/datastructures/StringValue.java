package dev.jediscore.datastructures;

/**
 * A Redis string value: a mutable, binary-safe byte array.
 *
 * <p><strong>Encoding.</strong> {@code OBJECT ENCODING} reports one of:
 * <ul>
 *   <li>{@code int} — the content is the canonical decimal form of a 64-bit
 *       integer (and the value was not produced by an in-place mutation);</li>
 *   <li>{@code embstr} — a short string (&le; 44 bytes);</li>
 *   <li>{@code raw} — anything longer, or any value mutated in place by
 *       {@code APPEND}/{@code SETRANGE} (Redis forces {@code raw} for those).</li>
 * </ul>
 */
public final class StringValue extends RedisValue {

    /** Redis's embstr/raw boundary. */
    private static final int EMBSTR_LIMIT = 44;

    private byte[] data;
    private boolean forcedRaw;

    /**
     * Creates a string value wrapping the given bytes (ownership transferred).
     *
     * @param data the contents
     */
    public StringValue(byte[] data) {
        this.data = data;
    }

    /** @return the backing bytes (must not be mutated by callers) */
    public byte[] get() {
        return data;
    }

    /**
     * Replaces the contents, recomputing encoding from the new value.
     *
     * @param data the new contents (ownership transferred)
     */
    public void set(byte[] data) {
        this.data = data;
        this.forcedRaw = false;
    }

    /**
     * Replaces the contents and forces {@code raw} encoding, as in-place
     * mutations ({@code APPEND}/{@code SETRANGE}) do in Redis.
     *
     * @param data the new contents (ownership transferred)
     */
    public void setRaw(byte[] data) {
        this.data = data;
        this.forcedRaw = true;
    }

    /** @return the length in bytes */
    public int length() {
        return data.length;
    }

    @Override
    public RedisType type() {
        return RedisType.STRING;
    }

    @Override
    public String encoding() {
        if (!forcedRaw && isCanonicalLong(data)) {
            return "int";
        }
        if (data.length <= EMBSTR_LIMIT && !forcedRaw) {
            return "embstr";
        }
        return "raw";
    }

    @Override
    public long estimateBytes() {
        return 16 + data.length;
    }

    @Override
    public StringValue deepCopy() {
        StringValue copy = new StringValue(data.clone());
        copy.forcedRaw = this.forcedRaw;
        return copy;
    }

    /**
     * Returns whether the bytes are the canonical decimal representation of a
     * {@code long}: an optional leading {@code -}, no leading zeros (except the
     * single digit {@code 0}), no {@code +} sign, and within {@code long} range.
     * This matches the cases Redis stores with {@code int} encoding.
     *
     * @param b the bytes to test
     * @return {@code true} if {@code b} is a canonical long
     */
    public static boolean isCanonicalLong(byte[] b) {
        int n = b.length;
        if (n == 0 || n > 20) {
            return false;
        }
        int i = 0;
        boolean negative = false;
        if (b[0] == '-') {
            if (n == 1) {
                return false;
            }
            negative = true;
            i = 1;
        }
        if (b[i] == '0') {
            // Only "0" is canonical; "0x", "00", "-0" are not.
            return n - i == 1 && !negative;
        }
        long value = 0;
        for (; i < n; i++) {
            int c = b[i];
            if (c < '0' || c > '9') {
                return false;
            }
            int digit = c - '0';
            // Guard overflow while accumulating as a negative magnitude.
            if (value < (Long.MIN_VALUE + digit) / 10) {
                return false;
            }
            value = value * 10 - digit;
        }
        // value currently holds the negative magnitude; that is fine for range checking.
        return negative || value != Long.MIN_VALUE;
    }
}
