package dev.jediscore.protocol;

/**
 * The RESP protocol dialect a connection speaks.
 *
 * <p>A connection starts in {@link #RESP2} (Redis's historical default) and is
 * upgraded to {@link #RESP3} only when the client issues {@code HELLO 3}. The
 * encoder consults the connection's negotiated version to decide how to render
 * RESP3-only types (maps, sets, doubles, …) — natively in RESP3, or downgraded
 * to their RESP2 equivalents otherwise.
 */
public enum RespVersion {

    /** RESP2 — the original Redis protocol; the default until {@code HELLO 3}. */
    RESP2(2),

    /** RESP3 — adds typed replies (maps, sets, doubles, booleans, push, …). */
    RESP3(3);

    private final int wireNumber;

    RespVersion(int wireNumber) {
        this.wireNumber = wireNumber;
    }

    /**
     * Returns the integer used on the wire (in {@code HELLO} and its reply).
     *
     * @return {@code 2} for RESP2, {@code 3} for RESP3
     */
    public int wireNumber() {
        return wireNumber;
    }

    /**
     * Maps a protocol version number to the enum.
     *
     * @param n the version number ({@code 2} or {@code 3})
     * @return the matching version, or {@code null} if unsupported
     */
    public static RespVersion fromNumber(int n) {
        return switch (n) {
            case 2 -> RESP2;
            case 3 -> RESP3;
            default -> null;
        };
    }
}
