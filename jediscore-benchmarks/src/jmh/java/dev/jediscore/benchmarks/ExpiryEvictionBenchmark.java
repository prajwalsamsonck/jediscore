package dev.jediscore.benchmarks;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.StringValue;
import dev.jediscore.engine.ActiveExpiry;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.Database;
import dev.jediscore.engine.Eviction;
import dev.jediscore.engine.MaxmemoryPolicy;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Background-maintenance benchmarks: the per-cycle cost of the active-expiry
 * sweep over a database of volatile keys, and eviction throughput while writing
 * under sustained memory pressure.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ExpiryEvictionBenchmark {

    private ServerContext expiryContext;
    private CommandExecutor expiryExecutor;

    private ServerContext evictContext;
    private CommandExecutor evictExecutor;
    private final byte[] value = "value-payload".getBytes(StandardCharsets.UTF_8);
    private long evictCounter;

    @Setup(Level.Trial)
    public void setUp() {
        expiryExecutor = new CommandExecutor("be-expiry");
        expiryContext = new ServerContext(ServerConfig.defaults("127.0.0.1", 0),
                new CommandRegistry(), expiryExecutor);
        Database edb = expiryContext.database(0);
        for (int i = 0; i < 10_000; i++) {
            Bytes k = new Bytes(("k" + i).getBytes(StandardCharsets.UTF_8));
            edb.put(k, new StringValue(value));
            edb.setExpireAt(k, Long.MAX_VALUE); // volatile but never expires: measures pure cycle overhead
        }

        evictExecutor = new CommandExecutor("be-evict");
        evictContext = new ServerContext(
                ServerConfig.defaults("127.0.0.1", 0).withMaxMemory(1_000_000, MaxmemoryPolicy.ALLKEYS_RANDOM),
                new CommandRegistry(), evictExecutor);
        Database vdb = evictContext.database(0);
        for (int i = 0; i < 8_000; i++) {
            vdb.put(new Bytes(("f" + i).getBytes(StandardCharsets.UTF_8)), new StringValue(value));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        expiryExecutor.close();
        evictExecutor.close();
    }

    @Benchmark
    public void activeExpiryCycle(Blackhole bh) {
        bh.consume(ActiveExpiry.run(expiryContext));
    }

    @Benchmark
    public void evictionUnderPressure(Blackhole bh) {
        // Each write adds a key, pushing over the limit; eviction frees room.
        Bytes key = new Bytes(("e" + (evictCounter++)).getBytes(StandardCharsets.UTF_8));
        evictContext.database(0).put(key, new StringValue(value));
        bh.consume(Eviction.evictToFit(evictContext));
    }
}
