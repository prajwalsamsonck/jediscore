package dev.jediscore.benchmarks;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Baseline JMH benchmark proving the benchmark pipeline runs end-to-end.
 *
 * <p>It measures {@link Fnv1a#hash(byte[])} over a tiny RESP-sized payload — a
 * stand-in for the allocation-free, per-byte hot-path work the real engine will
 * do (key hashing). Annotation defaults here are overridden by the fast "smoke"
 * settings in the module's build script, so a CI run completes in seconds.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
public class BaselineBenchmark {

    private byte[] payload;

    /** Allocates the payload once, outside the measured loop. */
    @Setup
    public void setup() {
        payload = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Hashes the payload and sinks the result into the {@link Blackhole} so the
     * JIT cannot eliminate the work as dead code.
     *
     * @param bh the JMH dead-code sink
     */
    @Benchmark
    public void fnv1aHash(Blackhole bh) {
        bh.consume(Fnv1a.hash(payload));
    }
}
