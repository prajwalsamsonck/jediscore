package dev.jediscore.benchmarks;

import dev.jediscore.commands.CoreCommands;
import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandDispatcher;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
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
 * SET/GET throughput and latency for the command-execution path (dispatch +
 * keyspace), excluding network I/O and RESP framing (those are measured by
 * {@link RespParseBenchmark}).
 *
 * <p>Both {@link Mode#Throughput} (ops/time) and {@link Mode#SampleTime} (which
 * yields p99 and other percentiles) are reported, so the numbers cover both
 * throughput and tail latency as the phase requires.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class KeyspaceBenchmark {

    private ServerContext server;
    private CommandDispatcher dispatcher;
    private ClientConnection connection;
    private CommandExecutor executor;
    private byte[][] setArgs;
    private byte[][] getArgs;

    @Setup(Level.Trial)
    public void setUp() {
        CommandRegistry registry = new CommandRegistry();
        CoreCommands.registerAll(registry);
        executor = new CommandExecutor("bench-cmd");
        server = new ServerContext(ServerConfig.defaults("127.0.0.1", 0), registry, executor);
        dispatcher = new CommandDispatcher(server);
        connection = new ClientConnection(1, "127.0.0.1:0", "127.0.0.1:0", true);

        setArgs = args("SET", "benchkey", "benchvalue");
        getArgs = args("GET", "benchkey");
        // Pre-populate the key so GET measures a hit.
        dispatcher.dispatch(new CommandContext(server, connection, setArgs));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.close();
    }

    @Benchmark
    public void set(Blackhole bh) {
        bh.consume(dispatcher.dispatch(new CommandContext(server, connection, setArgs)));
    }

    @Benchmark
    public void get(Blackhole bh) {
        bh.consume(dispatcher.dispatch(new CommandContext(server, connection, getArgs)));
    }

    private static byte[][] args(String... parts) {
        byte[][] out = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }
}
