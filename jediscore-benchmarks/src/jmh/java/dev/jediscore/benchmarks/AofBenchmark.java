package dev.jediscore.benchmarks;

import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.persistence.RdbPersistence;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
 * AOF append throughput per fsync policy — the cost of durability. {@code always}
 * fsyncs on every command (disk-latency bound); {@code everysec}/{@code no} only
 * buffer/flush to the OS (memory bound), so they should be far faster.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class AofBenchmark {

    private ServerContext context;
    private CommandExecutor executor;
    private RdbPersistence always;
    private RdbPersistence everysec;
    private RdbPersistence no;
    private Path dirAlways;
    private Path dirEverysec;
    private Path dirNo;
    private final byte[][] command = {
            "SET".getBytes(StandardCharsets.UTF_8),
            "benchkey".getBytes(StandardCharsets.UTF_8),
            "benchvalue".getBytes(StandardCharsets.UTF_8)};

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        executor = new CommandExecutor("bench-aof");
        context = new ServerContext(ServerConfig.defaults("127.0.0.1", 0), new CommandRegistry(), executor);
        dirAlways = Files.createTempDirectory("aof-always");
        dirEverysec = Files.createTempDirectory("aof-everysec");
        dirNo = Files.createTempDirectory("aof-no");
        always = open(dirAlways, "always");
        everysec = open(dirEverysec, "everysec");
        no = open(dirNo, "no");
    }

    private RdbPersistence open(Path dir, String fsync) {
        RdbPersistence p = new RdbPersistence(context,
                PersistenceConfig.defaults().withDir(dir.toString()).withAppendOnly(fsync));
        p.loadOnStartup(); // creates a fresh AOF and opens the incr file for appending
        return p;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        always.shutdown();
        everysec.shutdown();
        no.shutdown();
        executor.close();
        deleteRecursively(dirAlways);
        deleteRecursively(dirEverysec);
        deleteRecursively(dirNo);
    }

    @Benchmark
    public void appendAlwaysFsync(Blackhole bh) {
        always.feedAppendOnly(0, command);
        bh.consume(command);
    }

    @Benchmark
    public void appendEverysec(Blackhole bh) {
        everysec.feedAppendOnly(0, command);
        bh.consume(command);
    }

    @Benchmark
    public void appendNoFsync(Blackhole bh) {
        no.feedAppendOnly(0, command);
        bh.consume(command);
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
