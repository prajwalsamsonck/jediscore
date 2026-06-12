package dev.jediscore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.StringValue;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Tests for the keyspace, especially lazy expiration, using an injectable clock. */
class DatabaseTest {

    private static Bytes key(String s) {
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static StringValue str(String s) {
        return new StringValue(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void putGetRemove() {
        Database db = new Database(0, System::currentTimeMillis);
        db.put(key("a"), str("1"));
        assertThat(db.lookup(key("a"))).isNotNull();
        assertThat(db.containsKey(key("a"))).isTrue();
        assertThat(db.remove(key("a"))).isTrue();
        assertThat(db.lookup(key("a"))).isNull();
    }

    @Test
    void expiredKeyIsRemovedLazilyOnLookup() {
        AtomicLong now = new AtomicLong(1_000);
        Database db = new Database(0, now::get);
        db.put(key("k"), str("v"));
        db.setExpireAt(key("k"), 2_000);

        assertThat(db.lookup(key("k"))).isNotNull(); // not yet expired at t=1000
        now.set(2_500);
        assertThat(db.lookup(key("k"))).isNull();      // expired
        assertThat(db.size()).isZero();                // and physically removed
    }

    @Test
    void liveKeysPurgesExpired() {
        AtomicLong now = new AtomicLong(1_000);
        Database db = new Database(0, now::get);
        db.put(key("permanent"), str("v"));
        db.put(key("temp"), str("v"));
        db.setExpireAt(key("temp"), 1_500);

        now.set(2_000);
        assertThat(db.liveKeys()).map(Bytes::toString).containsExactly("permanent");
        assertThat(db.containsKey(key("temp"))).isFalse();
    }

    @Test
    void putClearsTtlButPutKeepTtlDoesNot() {
        Database db = new Database(0, System::currentTimeMillis);
        db.put(key("k"), str("v"));
        db.setExpireAt(key("k"), Long.MAX_VALUE);
        assertThat(db.hasExpire(key("k"))).isTrue();

        db.putKeepTtl(key("k"), str("v2"));
        assertThat(db.hasExpire(key("k"))).isTrue();

        db.put(key("k"), str("v3"));
        assertThat(db.hasExpire(key("k"))).isFalse();
    }

    @Test
    void persistRemovesTtl() {
        Database db = new Database(0, System::currentTimeMillis);
        db.put(key("k"), str("v"));
        db.setExpireAt(key("k"), Long.MAX_VALUE);
        assertThat(db.persist(key("k"))).isTrue();
        assertThat(db.hasExpire(key("k"))).isFalse();
        assertThat(db.persist(key("k"))).isFalse();
    }
}
