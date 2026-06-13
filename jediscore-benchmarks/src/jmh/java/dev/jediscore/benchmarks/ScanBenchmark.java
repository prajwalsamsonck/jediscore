package dev.jediscore.benchmarks;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.Dict;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Measures a full {@code SCAN} iteration over a {@link Dict} of {@code size}
 * entries — one operation walks every bucket from cursor 0 back to 0, which is
 * the cost of a complete keyspace scan.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ScanBenchmark {

    @Param({"10000"})
    private int size;

    private Dict<Integer> dict;

    @Setup(Level.Trial)
    public void setUp() {
        dict = new Dict<>();
        for (int i = 0; i < size; i++) {
            dict.put(new Bytes(("k" + i).getBytes(StandardCharsets.UTF_8)), i);
        }
    }

    @Benchmark
    public void fullScan(Blackhole bh) {
        AtomicInteger counter = new AtomicInteger();
        long cursor = 0;
        do {
            cursor = dict.scan(cursor, 100, (key, value) -> counter.incrementAndGet());
        } while (cursor != 0);
        bh.consume(counter.get());
    }
}
