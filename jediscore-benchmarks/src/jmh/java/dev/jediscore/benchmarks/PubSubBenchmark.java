package dev.jediscore.benchmarks;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.PubSubRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Pub/sub fan-out cost: how long {@code PUBLISH} takes to dispatch a message to N
 * direct channel subscribers, isolated from the socket write by attaching a
 * no-op outbox that hands the frame to a {@link Blackhole}. This measures the
 * registry lookup plus per-subscriber frame build and delivery dispatch — the
 * work the command thread does per publish.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PubSubBenchmark {

    @Param({"1", "10", "100", "1000"})
    private int subscribers;

    private PubSubRegistry registry;
    private final byte[] channel = "bench.channel".getBytes(StandardCharsets.UTF_8);
    private final byte[] payload = "a typical pub/sub payload".getBytes(StandardCharsets.UTF_8);
    private Blackhole sink;

    @Setup(Level.Trial)
    public void setUp(Blackhole bh) {
        this.sink = bh;
        registry = new PubSubRegistry();
        for (int i = 0; i < subscribers; i++) {
            ClientConnection conn = new ClientConnection(i, "127.0.0.1:0", "127.0.0.1:0", true);
            conn.attachOutbox(message -> sink.consume(message));
            registry.subscribe(conn, channel);
        }
    }

    @Benchmark
    public int publishToChannel() {
        return registry.publish(channel, payload);
    }
}
