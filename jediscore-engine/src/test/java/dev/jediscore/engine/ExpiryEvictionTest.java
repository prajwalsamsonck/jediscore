package dev.jediscore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.StringValue;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for active expiration, memory accounting, and the eviction policies. */
class ExpiryEvictionTest {

    private CommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CommandExecutor("test-cmd");
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private ServerContext context(ServerConfig config) {
        return new ServerContext(config, new CommandRegistry(), executor);
    }

    private static Bytes key(String s) {
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static StringValue str(String s) {
        return new StringValue(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- active expiration --------------------------------------------------

    @Test
    void activeExpiryRemovesExpiredVolatileKeysWithoutAccess() {
        ServerContext ctx = context(ServerConfig.defaults("127.0.0.1", 0));
        Database db = ctx.database(0);
        for (int i = 0; i < 100; i++) {
            db.put(key("v" + i), str("x"));
            db.setExpireAt(key("v" + i), 1); // epoch 1ms — long expired
        }
        for (int i = 0; i < 20; i++) {
            db.put(key("persistent" + i), str("x")); // no TTL
        }

        int expired = ActiveExpiry.run(ctx);

        assertThat(expired).isEqualTo(100);
        assertThat(db.volatileCount()).isZero();
        assertThat(db.containsKey(key("persistent0"))).isTrue();
        assertThat(db.size()).isEqualTo(20);
    }

    // ---- memory accounting --------------------------------------------------

    @Test
    void memoryTrackedOnPutRemoveClear() {
        ServerContext ctx = context(ServerConfig.defaults("127.0.0.1", 0));
        Database db = ctx.database(0);
        assertThat(ctx.usedMemory()).isZero();

        StringValue v = str("hello");
        db.put(key("k"), v);
        assertThat(db.memoryUsed()).isEqualTo(MemoryEstimator.usage(key("k"), v));
        assertThat(ctx.usedMemory()).isEqualTo(db.memoryUsed());

        db.remove(key("k"));
        assertThat(db.memoryUsed()).isZero();

        db.put(key("a"), str("x"));
        db.clear();
        assertThat(db.memoryUsed()).isZero();
    }

    // ---- eviction -----------------------------------------------------------

    @Test
    void noevictionRefusesWhenOverLimit() {
        ServerContext ctx = context(ServerConfig.defaults("127.0.0.1", 0).withMaxMemory(500, MaxmemoryPolicy.NOEVICTION));
        Database db = ctx.database(0);
        for (int i = 0; i < 50; i++) {
            db.put(key("k" + i), str("value" + i));
        }
        assertThat(ctx.usedMemory()).isGreaterThan(500);
        assertThat(Eviction.evictToFit(ctx)).isFalse();
        assertThat(db.size()).isEqualTo(50); // nothing evicted
    }

    @Test
    void allkeysRandomEvictsUntilUnderLimit() {
        long limit = 2000;
        ServerContext ctx = context(ServerConfig.defaults("127.0.0.1", 0)
                .withMaxMemory(limit, MaxmemoryPolicy.ALLKEYS_RANDOM));
        Database db = ctx.database(0);
        for (int i = 0; i < 200; i++) {
            db.put(key("k" + i), str("value" + i));
        }
        assertThat(ctx.usedMemory()).isGreaterThan(limit);

        assertThat(Eviction.evictToFit(ctx)).isTrue();
        assertThat(ctx.usedMemory()).isLessThanOrEqualTo(limit);
        assertThat(db.size()).isLessThan(200);
        assertThat(db.size()).isGreaterThan(0);
    }

    @Test
    void volatilePolicyNeverEvictsPersistentKeys() {
        long limit = 3000;
        ServerContext ctx = context(ServerConfig.defaults("127.0.0.1", 0)
                .withMaxMemory(limit, MaxmemoryPolicy.VOLATILE_RANDOM));
        Database db = ctx.database(0);
        // Persistent keys whose total comfortably fits under the limit.
        for (int i = 0; i < 10; i++) {
            db.put(key("persistent" + i), str("p"));
        }
        // Volatile keys that push memory well over the limit.
        for (int i = 0; i < 200; i++) {
            db.put(key("vol" + i), str("value" + i));
            db.setExpireAt(key("vol" + i), Long.MAX_VALUE);
        }
        assertThat(ctx.usedMemory()).isGreaterThan(limit);

        assertThat(Eviction.evictToFit(ctx)).isTrue();
        assertThat(ctx.usedMemory()).isLessThanOrEqualTo(limit);
        for (int i = 0; i < 10; i++) {
            assertThat(db.containsKey(key("persistent" + i)))
                    .as("persistent key %d must survive volatile-* eviction", i).isTrue();
        }
    }

    @Test
    void lruEvictsTheMoreIdleKey() throws InterruptedException {
        // Two keys, one accessed much more recently; the more-idle one is evicted.
        // A real time gap is needed because idle time is measured in milliseconds.
        long limit = 300; // holds one ~176-byte entry but not two
        ServerContext ctx = context(ServerConfig.defaults("127.0.0.1", 0)
                .withMaxMemory(limit, MaxmemoryPolicy.ALLKEYS_LRU));
        Database db = ctx.database(0);
        String big = "x".repeat(100);
        db.put(key("old"), str(big));
        Thread.sleep(25);
        db.put(key("hot"), str(big));
        db.lookup(key("hot")); // refresh: hot idle ~0, old idle ~25ms

        assertThat(Eviction.evictToFit(ctx)).isTrue();
        assertThat(db.containsKey(key("hot"))).as("recently used key survives").isTrue();
        assertThat(db.containsKey(key("old"))).as("idle key is evicted").isFalse();
    }

    @Test
    void lfuEvictsTheLessFrequentKey() {
        long limit = 300;
        ServerContext ctx = context(ServerConfig.defaults("127.0.0.1", 0)
                .withMaxMemory(limit, MaxmemoryPolicy.ALLKEYS_LFU));
        Database db = ctx.database(0);
        String big = "x".repeat(100);
        db.put(key("rare"), str(big));
        db.put(key("popular"), str(big));
        // Drive "popular"'s frequency counter up; "rare" stays near the init value.
        for (int i = 0; i < 1000; i++) {
            db.lookup(key("popular"));
        }
        assertThat(db.lookup(key("popular")).frequency())
                .isGreaterThan(db.lookup(key("rare")).frequency());

        assertThat(Eviction.evictToFit(ctx)).isTrue();
        assertThat(db.containsKey(key("popular"))).as("frequently used key survives").isTrue();
        assertThat(db.containsKey(key("rare"))).as("rarely used key is evicted").isFalse();
    }
}
