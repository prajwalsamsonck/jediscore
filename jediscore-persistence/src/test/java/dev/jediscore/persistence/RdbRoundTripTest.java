package dev.jediscore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.HashValue;
import dev.jediscore.datastructures.ListValue;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.datastructures.ScoredMember;
import dev.jediscore.datastructures.SetValue;
import dev.jediscore.datastructures.StringValue;
import dev.jediscore.datastructures.ZSetValue;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.Database;
import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trips every data type (and TTLs) through the RDB writer + reader via
 * {@code DEBUG RELOAD} semantics (save to disk, clear, load back), asserting the
 * keyspace is byte-for-byte the same afterward.
 */
class RdbRoundTripTest {

    private CommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CommandExecutor("test-cmd");
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private static Bytes key(String s) {
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void everyTypeAndTtlSurvivesReload(@TempDir Path dir) {
        ServerContext ctx = new ServerContext(ServerConfig.defaults("127.0.0.1", 0),
                new CommandRegistry(), executor);
        RdbPersistence persistence = new RdbPersistence(ctx, PersistenceConfig.defaults().withDir(dir.toString()));
        ctx.setPersistence(persistence);
        Database db = ctx.database(0);

        // String (incl. an int-looking value), list, intset, listpack-set, hash, zset.
        db.put(key("s"), new StringValue(b("hello world")));
        db.put(key("n"), new StringValue(b("12345")));

        ListValue list = new ListValue(128, 64);
        list.pushTail(b("a"));
        list.pushTail(b("b"));
        list.pushTail(b("c"));
        db.put(key("list"), list);

        SetValue intset = new SetValue(512, 128, 64);
        intset.add(b("1"));
        intset.add(b("2"));
        intset.add(b("3"));
        db.put(key("intset"), intset);

        SetValue strset = new SetValue(512, 128, 64);
        strset.add(b("x"));
        strset.add(b("y"));
        db.put(key("strset"), strset);

        HashValue hash = new HashValue(128, 64);
        hash.put(b("f1"), b("v1"));
        hash.put(b("f2"), b("v2"));
        db.put(key("hash"), hash);

        ZSetValue zset = new ZSetValue(128, 64);
        zset.put(b("alpha"), 1.5);
        zset.put(b("beta"), 2.0);
        zset.put(b("gamma"), -3.25);
        db.put(key("zset"), zset);

        // A key with a far-future TTL must keep its exact expiry.
        db.put(key("ttl"), new StringValue(b("temp")));
        long expireAt = System.currentTimeMillis() + 1_000_000;
        db.setExpireAt(key("ttl"), expireAt);

        Map<String, String> before = canonicalize(db);

        persistence.reload();

        Database after = ctx.database(0);
        assertThat(canonicalize(after)).isEqualTo(before);
        assertThat(after.getExpireAt(key("ttl"))).isEqualTo(expireAt);
        // OBJECT ENCODING should be reconstructed: ints → intset, strings → listpack.
        assertThat(((SetValue) after.peek(key("intset"))).encoding()).isEqualTo("intset");
        assertThat(((SetValue) after.peek(key("strset"))).encoding()).isEqualTo("listpack");
    }

    /** Canonical string per key, order-independent for sets. */
    private static Map<String, String> canonicalize(Database db) {
        Map<String, String> out = new HashMap<>();
        for (Bytes k : db.liveKeys()) {
            out.put(k.toString(), canon(db.peek(k)));
        }
        return out;
    }

    private static String canon(RedisValue v) {
        if (v instanceof StringValue s) {
            return "str:" + new String(s.get(), StandardCharsets.UTF_8);
        }
        if (v instanceof ListValue l) {
            StringBuilder sb = new StringBuilder("list:");
            for (byte[] e : l.range(0, -1)) {
                sb.append(new String(e, StandardCharsets.UTF_8)).append(',');
            }
            return sb.toString();
        }
        if (v instanceof SetValue set) {
            TreeMap<String, Boolean> sorted = new TreeMap<>();
            for (byte[] m : set.members()) {
                sorted.put(new String(m, StandardCharsets.UTF_8), Boolean.TRUE);
            }
            return "set:" + sorted.keySet();
        }
        if (v instanceof HashValue h) {
            TreeMap<String, String> sorted = new TreeMap<>();
            var flat = h.entriesFlattened();
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                sorted.put(new String(flat.get(i), StandardCharsets.UTF_8),
                        new String(flat.get(i + 1), StandardCharsets.UTF_8));
            }
            return "hash:" + sorted;
        }
        if (v instanceof ZSetValue z) {
            StringBuilder sb = new StringBuilder("zset:");
            for (ScoredMember m : z.ascending()) {
                sb.append(new String(m.member(), StandardCharsets.UTF_8)).append('=').append(m.score()).append(',');
            }
            return sb.toString();
        }
        return "?";
    }
}
