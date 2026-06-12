package dev.jediscore.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Encode → decode round-trips for every RESP type.
 *
 * <p>In RESP3 every value decodes back to an equal value. In RESP2 the RESP3-only
 * types are downgraded on encode, so we assert the decoded value reflects that
 * downgrade (e.g. a boolean comes back as an integer).
 */
class RespCodecRoundTripTest {

    private static RespValue reencode(RespValue value, RespVersion version) {
        ByteBuf buf = Unpooled.buffer();
        try {
            RespEncoder.encode(value, buf, version);
            RespValue parsed = RespParser.parse(buf);
            assertThat(parsed).as("parse must consume a complete frame").isNotNull();
            assertThat(buf.isReadable()).as("no trailing bytes").isFalse();
            return parsed;
        } finally {
            buf.release();
        }
    }

    private static String encodeToString(RespValue value, RespVersion version) {
        ByteBuf buf = Unpooled.buffer();
        try {
            RespEncoder.encode(value, buf, version);
            return buf.toString(StandardCharsets.UTF_8);
        } finally {
            buf.release();
        }
    }

    // ---- RESP3: every type round-trips to an equal value --------------------

    @Test
    void resp3RoundTripsAllTypes() {
        assertRoundTrip(new RespValue.SimpleString("OK"));
        assertRoundTrip(new RespValue.SimpleError("ERR boom"));
        assertRoundTrip(new RespValue.Integer(0));
        assertRoundTrip(new RespValue.Integer(-9223372036854775808L));
        assertRoundTrip(new RespValue.Integer(9223372036854775807L));
        assertRoundTrip(RespValue.bulk("hello"));
        assertRoundTrip(new RespValue.BulkString(new byte[0]));
        assertRoundTrip(new RespValue.BulkString(new byte[] {0, 1, 2, '\r', '\n', (byte) 0xff}));
        assertRoundTrip(RespValue.NULL);
        assertRoundTrip(new RespValue.Double(3.14159));
        assertRoundTrip(new RespValue.Double(Double.POSITIVE_INFINITY));
        assertRoundTrip(new RespValue.Double(Double.NEGATIVE_INFINITY));
        assertRoundTrip(new RespValue.Boolean(true));
        assertRoundTrip(new RespValue.Boolean(false));
        assertRoundTrip(new RespValue.BigNumber(new BigInteger("3492890328409238509324850943850943825024385")));
        assertRoundTrip(new RespValue.VerbatimString("txt", "Some string".getBytes(StandardCharsets.UTF_8)));
        assertRoundTrip(new RespValue.BulkError("SYNTAX bad args".getBytes(StandardCharsets.UTF_8)));
        assertRoundTrip(new RespValue.Array(List.of(RespValue.integer(1), RespValue.bulk("two"), RespValue.NULL)));
        assertRoundTrip(new RespValue.Array(List.of())); // empty array
        assertRoundTrip(new RespValue.Set(List.of(RespValue.bulk("a"), RespValue.bulk("b"))));
        assertRoundTrip(new RespValue.Push(List.of(RespValue.bulk("message"), RespValue.bulk("ch"), RespValue.bulk("hi"))));
        assertRoundTrip(new RespValue.Map(List.of(
                new RespValue.MapEntry(RespValue.bulk("k1"), RespValue.integer(1)),
                new RespValue.MapEntry(RespValue.bulk("k2"), RespValue.bulk("v2")))));
    }

    @Test
    void resp3NaNRoundTrips() {
        RespValue decoded = reencode(new RespValue.Double(Double.NaN), RespVersion.RESP3);
        assertThat(decoded).isInstanceOf(RespValue.Double.class);
        assertThat(((RespValue.Double) decoded).value()).isNaN();
    }

    @Test
    void resp3AttributePreservesAttachedValue() {
        RespValue attr = new RespValue.Attribute(
                List.of(new RespValue.MapEntry(RespValue.bulk("ttl"), RespValue.integer(3600))),
                RespValue.bulk("payload"));
        assertRoundTrip(attr);
    }

    @Test
    void resp3NestedAggregates() {
        RespValue nested = new RespValue.Array(List.of(
                new RespValue.Array(List.of(RespValue.integer(1), RespValue.integer(2))),
                new RespValue.Map(List.of(new RespValue.MapEntry(RespValue.bulk("k"), RespValue.NULL)))));
        assertRoundTrip(nested);
    }

    private static void assertRoundTrip(RespValue value) {
        assertThat(reencode(value, RespVersion.RESP3)).isEqualTo(value);
    }

    // ---- RESP2: RESP3-only types are downgraded -----------------------------

    @Test
    void resp2NullIsBulkNull() {
        assertThat(encodeToString(RespValue.NULL, RespVersion.RESP2)).isEqualTo("$-1\r\n");
        assertThat(reencode(RespValue.NULL, RespVersion.RESP2)).isEqualTo(RespValue.NULL);
    }

    @Test
    void resp2BooleanBecomesInteger() {
        assertThat(reencode(new RespValue.Boolean(true), RespVersion.RESP2)).isEqualTo(new RespValue.Integer(1));
        assertThat(reencode(new RespValue.Boolean(false), RespVersion.RESP2)).isEqualTo(new RespValue.Integer(0));
    }

    @Test
    void resp2DoubleBecomesBulkString() {
        RespValue decoded = reencode(new RespValue.Double(3.5), RespVersion.RESP2);
        assertThat(decoded).isEqualTo(RespValue.bulk("3.5"));
    }

    @Test
    void resp2MapBecomesFlatArray() {
        RespValue map = new RespValue.Map(List.of(
                new RespValue.MapEntry(RespValue.bulk("proto"), RespValue.integer(2))));
        assertThat(encodeToString(map, RespVersion.RESP2)).isEqualTo("*2\r\n$5\r\nproto\r\n:2\r\n");
    }

    @Test
    void resp2VerbatimBecomesBulk() {
        RespValue v = new RespValue.VerbatimString("txt", "hi".getBytes(StandardCharsets.UTF_8));
        assertThat(reencode(v, RespVersion.RESP2)).isEqualTo(RespValue.bulk("hi"));
    }

    @Test
    void resp2SetBecomesArray() {
        RespValue set = new RespValue.Set(List.of(RespValue.bulk("a")));
        assertThat(encodeToString(set, RespVersion.RESP2)).isEqualTo("*1\r\n$1\r\na\r\n");
    }

    @Test
    void simpleStringEncodesExactly() {
        assertThat(encodeToString(RespValue.PONG, RespVersion.RESP2)).isEqualTo("+PONG\r\n");
    }
}
