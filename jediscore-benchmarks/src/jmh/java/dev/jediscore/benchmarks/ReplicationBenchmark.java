package dev.jediscore.benchmarks;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.ReplicationManager;
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
 * Cost of propagating one write to the replication stream — encoding the command,
 * appending to the backlog, advancing the offset, and fanning out to replicas.
 * This is paid on every write (even with no replicas, since the offset/backlog
 * still advance), so {@code replicas = 0} is the headline case.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ReplicationBenchmark {

    @Param({"0", "1", "10"})
    private int replicas;

    private ReplicationManager replication;
    private Blackhole sink;
    private final byte[][] setCommand = {
            "SET".getBytes(StandardCharsets.UTF_8),
            "user:1000".getBytes(StandardCharsets.UTF_8),
            "a typical value".getBytes(StandardCharsets.UTF_8)};

    @Setup(Level.Trial)
    public void setUp(Blackhole bh) {
        this.sink = bh;
        replication = new ReplicationManager("0".repeat(40));
        for (int i = 0; i < replicas; i++) {
            ClientConnection conn = new ClientConnection(i, "127.0.0.1:0", "127.0.0.1:0", true);
            conn.attachOutbox(message -> sink.consume(message));
            replication.attachReplica(conn, 0);
        }
    }

    @Benchmark
    public ReplicationManager propagateWrite() {
        replication.propagate(0, setCommand);
        return replication;
    }
}
