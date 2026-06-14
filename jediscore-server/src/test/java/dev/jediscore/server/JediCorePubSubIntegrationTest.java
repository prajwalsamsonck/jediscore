package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import dev.jediscore.protocol.RespValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pub/sub coverage over real sockets: fan-out to multiple subscribers,
 * pattern and sharded delivery, confirmation counts, RESP2 subscriber-mode
 * gating, and PUBSUB introspection.
 */
class JediCorePubSubIntegrationTest {

    private JediCore server;

    @BeforeEach
    void start() throws InterruptedException {
        server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private RespTestClient client() throws IOException {
        return new RespTestClient(server.port());
    }

    /** Extracts the elements of a push/array/set frame, regardless of which it decoded as. */
    private static List<RespValue> items(RespValue v) {
        if (v instanceof RespValue.Push p) {
            return p.items();
        }
        if (v instanceof RespValue.Array a) {
            return a.items();
        }
        throw new AssertionError("not an array/push frame: " + v);
    }

    private static String str(RespValue v) {
        return new String(((RespValue.BulkString) v).data(), StandardCharsets.UTF_8);
    }

    private static long num(RespValue v) {
        return ((RespValue.Integer) v).value();
    }

    @Test
    void publishDeliversToAllChannelSubscribers() throws Exception {
        try (RespTestClient sub1 = client(); RespTestClient sub2 = client(); RespTestClient pub = client()) {
            // Each subscriber confirms before we publish, so the subscription is live.
            sub1.send("SUBSCRIBE", "news");
            assertThat(str(items(sub1.receive()).get(0))).isEqualTo("subscribe");
            sub2.send("SUBSCRIBE", "news");
            sub2.receive();

            assertThat(num(pub.call("PUBLISH", "news", "hello"))).isEqualTo(2);

            List<RespValue> m1 = items(sub1.receive());
            assertThat(str(m1.get(0))).isEqualTo("message");
            assertThat(str(m1.get(1))).isEqualTo("news");
            assertThat(str(m1.get(2))).isEqualTo("hello");

            List<RespValue> m2 = items(sub2.receive());
            assertThat(str(m2.get(2))).isEqualTo("hello");
        }
    }

    @Test
    void subscribeConfirmationsCarryRunningCounts() throws Exception {
        try (RespTestClient c = client()) {
            c.send("SUBSCRIBE", "a", "b", "c");
            for (int expected = 1; expected <= 3; expected++) {
                List<RespValue> frame = items(c.receive());
                assertThat(str(frame.get(0))).isEqualTo("subscribe");
                assertThat(num(frame.get(2))).isEqualTo(expected);
            }
        }
    }

    @Test
    void patternSubscriptionMatchesChannel() throws Exception {
        try (RespTestClient sub = client(); RespTestClient pub = client()) {
            sub.send("PSUBSCRIBE", "news.*");
            List<RespValue> conf = items(sub.receive());
            assertThat(str(conf.get(0))).isEqualTo("psubscribe");

            assertThat(num(pub.call("PUBLISH", "news.tech", "x"))).isEqualTo(1);

            List<RespValue> m = items(sub.receive());
            assertThat(str(m.get(0))).isEqualTo("pmessage");
            assertThat(str(m.get(1))).isEqualTo("news.*");
            assertThat(str(m.get(2))).isEqualTo("news.tech");
            assertThat(str(m.get(3))).isEqualTo("x");
        }
    }

    @Test
    void unsubscribeAllLeavesSubscriberMode() throws Exception {
        try (RespTestClient c = client()) {
            c.send("SUBSCRIBE", "a", "b");
            c.receive();
            c.receive();
            // UNSUBSCRIBE with no args drops every channel; the last count is 0.
            c.send("UNSUBSCRIBE");
            long last = 0;
            for (int i = 0; i < 2; i++) {
                last = num(items(c.receive()).get(2));
            }
            assertThat(last).isZero();
            // No longer in subscriber mode: a normal command works again.
            assertThat(c.call("PING")).isEqualTo(RespValue.PONG);
        }
    }

    @Test
    void resp2SubscriberModeRejectsNonPubSubCommands() throws Exception {
        try (RespTestClient c = client()) {
            c.send("SUBSCRIBE", "ch");
            c.receive();
            // GET is forbidden in RESP2 subscriber mode.
            RespValue err = c.call("GET", "k");
            assertThat(err).isInstanceOf(RespValue.SimpleError.class);
            assertThat(((RespValue.SimpleError) err).message()).contains("only");
            // PING is allowed and replies in the subscriber-mode array form.
            List<RespValue> pong = items(c.call("PING"));
            assertThat(str(pong.get(0))).isEqualTo("pong");
        }
    }

    @Test
    void resp3AllowsNormalCommandsWhileSubscribed() throws Exception {
        try (RespTestClient c = client()) {
            c.call("HELLO", "3"); // upgrade to RESP3
            c.send("SUBSCRIBE", "ch");
            c.receive();
            // In RESP3, ordinary commands interleave with subscriptions.
            assertThat(c.call("SET", "k", "v")).isEqualTo(RespValue.OK);
            assertThat(str(c.call("GET", "k"))).isEqualTo("v");
        }
    }

    @Test
    void shardedPubSubDeliversSeparately() throws Exception {
        try (RespTestClient sub = client(); RespTestClient pub = client()) {
            sub.send("SSUBSCRIBE", "shard1");
            List<RespValue> conf = items(sub.receive());
            assertThat(str(conf.get(0))).isEqualTo("ssubscribe");

            // A regular PUBLISH must NOT reach a shard subscriber.
            assertThat(num(pub.call("PUBLISH", "shard1", "ignored"))).isZero();
            assertThat(num(pub.call("SPUBLISH", "shard1", "payload"))).isEqualTo(1);

            List<RespValue> m = items(sub.receive());
            assertThat(str(m.get(0))).isEqualTo("smessage");
            assertThat(str(m.get(2))).isEqualTo("payload");
        }
    }

    @Test
    void pubsubIntrospection() throws Exception {
        try (RespTestClient sub = client(); RespTestClient inspector = client()) {
            sub.send("SUBSCRIBE", "c1", "c2");
            sub.receive();
            sub.receive();
            sub.send("PSUBSCRIBE", "p.*");
            sub.receive();

            List<RespValue> channels = items(inspector.call("PUBSUB", "CHANNELS"));
            assertThat(channels.stream().map(JediCorePubSubIntegrationTest::str))
                    .containsExactlyInAnyOrder("c1", "c2");

            List<RespValue> numsub = items(inspector.call("PUBSUB", "NUMSUB", "c1", "missing"));
            assertThat(str(numsub.get(0))).isEqualTo("c1");
            assertThat(num(numsub.get(1))).isEqualTo(1);
            assertThat(str(numsub.get(2))).isEqualTo("missing");
            assertThat(num(numsub.get(3))).isZero();

            assertThat(num(inspector.call("PUBSUB", "NUMPAT"))).isEqualTo(1);
        }
    }

    @Test
    void disconnectClearsSubscriptions() throws Exception {
        try (RespTestClient pub = client()) {
            try (RespTestClient sub = client()) {
                sub.send("SUBSCRIBE", "gone");
                sub.receive();
                assertThat(num(pub.call("PUBLISH", "gone", "x"))).isEqualTo(1);
            } // sub disconnects here

            // The command thread cleans up the subscription on disconnect. Retry
            // briefly to avoid racing the async cleanup task.
            long receivers = -1;
            for (int attempt = 0; attempt < 50 && receivers != 0; attempt++) {
                receivers = num(pub.call("PUBLISH", "gone", "x"));
                if (receivers != 0) {
                    Thread.sleep(20);
                }
            }
            assertThat(receivers).isZero();
        }
    }
}
