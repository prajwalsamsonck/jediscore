package dev.jediscore.benchmarks;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.datastructures.StringValue;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.Database;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.persistence.RdbSnapshot;
import dev.jediscore.persistence.RdbWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * RDB benchmarks over a keyspace of {@code keys} string entries.
 *
 * <p>{@link #bgsaveSnapshotPause} measures the command-thread <em>pause</em> a
 * {@code BGSAVE} incurs — the deep-copy of the keyspace, which is the fork-free
 * cost the JVM imposes (O(dataset) vs Redis's O(page-table) fork). One operation
 * snapshots the whole dataset, so per-op time is the pause. {@link #serialize}
 * measures the off-thread RDB write throughput.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RdbBenchmark {

    @Param({"10000"})
    private int keys;

    private ServerContext context;
    private CommandExecutor executor;
    private RdbSnapshot referenceSnapshot;

    @Setup(Level.Trial)
    public void setUp() {
        executor = new CommandExecutor("bench-rdb");
        context = new ServerContext(ServerConfig.defaults("127.0.0.1", 0), new CommandRegistry(), executor);
        Database db = context.database(0);
        for (int i = 0; i < keys; i++) {
            db.put(new Bytes(("key:" + i).getBytes(StandardCharsets.UTF_8)),
                    new StringValue(("value-number-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        referenceSnapshot = snapshot(false);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.close();
    }

    @Benchmark
    public void bgsaveSnapshotPause(Blackhole bh) {
        bh.consume(snapshot(true)); // deep-copy snapshot == the BGSAVE stop-the-world pause
    }

    @Benchmark
    public void serialize(Blackhole bh) {
        CountingOutputStream out = new CountingOutputStream();
        try {
            RdbWriter.write(referenceSnapshot, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        bh.consume(out.count);
    }

    private RdbSnapshot snapshot(boolean deepCopy) {
        Database db = context.database(0);
        List<RdbSnapshot.Entry> entries = new ArrayList<>(keys);
        for (Bytes key : db.liveKeys()) {
            RedisValue v = db.peek(key);
            entries.add(new RdbSnapshot.Entry(key.copy(), deepCopy ? v.deepCopy() : v, -1));
        }
        return new RdbSnapshot(List.of(new RdbSnapshot.DatabaseSnapshot(0, entries)));
    }

    private static final class CountingOutputStream extends OutputStream {
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
