package dev.jediscore.benchmarks;

import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.WatchTable;
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

/**
 * Cost of the per-write WATCH/CAS bookkeeping the dispatcher now runs on every
 * write command — {@link WatchTable#touchByArguments}. The headline case is
 * {@code watchedKeys = 0} (no transactions in flight), which must be effectively
 * free since it is paid on the hot write path; the other cases quantify the cost
 * once watches exist.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WatchBenchmark {

    @Param({"0", "1", "100"})
    private int watchedKeys;

    private WatchTable table;
    private final byte[][] setArgs = {
            "SET".getBytes(StandardCharsets.UTF_8),
            "user:1000".getBytes(StandardCharsets.UTF_8),
            "some-value".getBytes(StandardCharsets.UTF_8)};

    @Setup(Level.Trial)
    public void setUp() {
        table = new WatchTable(16);
        for (int i = 0; i < watchedKeys; i++) {
            ClientConnection conn = new ClientConnection(i, "127.0.0.1:0", "127.0.0.1:0", true);
            table.watch(conn, 0, ("watched:" + i).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Benchmark
    public WatchTable touchOnWrite() {
        table.touchByArguments(0, setArgs);
        return table;
    }
}
