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
 * List and set hot-path benchmarks (dispatch + data structure, excluding
 * network). Operations are kept size-neutral so a measurement iteration does not
 * grow the structures without bound: the push benchmark pairs an LPUSH with an
 * RPOP, and the set benchmarks re-touch a pre-populated set.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CollectionBenchmark {

    private ServerContext server;
    private CommandDispatcher dispatcher;
    private ClientConnection connection;
    private CommandExecutor executor;

    private byte[][] lpush;
    private byte[][] rpop;
    private byte[][] lrange;
    private byte[][] sadd;
    private byte[][] sismember;

    @Setup(Level.Trial)
    public void setUp() {
        CommandRegistry registry = new CommandRegistry();
        CoreCommands.registerAll(registry);
        executor = new CommandExecutor("bench-cmd");
        server = new ServerContext(ServerConfig.defaults("127.0.0.1", 0), registry, executor);
        dispatcher = new CommandDispatcher(server);
        connection = new ClientConnection(1, "127.0.0.1:0", "127.0.0.1:0", true);

        // Pre-populate a 100-element list and a 100-member set.
        for (int i = 0; i < 100; i++) {
            run(args("RPUSH", "l", "e" + i));
            run(args("SADD", "s", "m" + i));
        }
        lpush = args("LPUSH", "l", "x");
        rpop = args("RPOP", "l");
        lrange = args("LRANGE", "l", "0", "9");
        sadd = args("SADD", "s", "m50");      // already present → size-neutral
        sismember = args("SISMEMBER", "s", "m50");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.close();
    }

    @Benchmark
    public void listPushPop(Blackhole bh) {
        bh.consume(run(lpush));
        bh.consume(run(rpop));
    }

    @Benchmark
    public void listRange(Blackhole bh) {
        bh.consume(run(lrange));
    }

    @Benchmark
    public void setAdd(Blackhole bh) {
        bh.consume(run(sadd));
    }

    @Benchmark
    public void setIsMember(Blackhole bh) {
        bh.consume(run(sismember));
    }

    private Object run(byte[][] args) {
        return dispatcher.dispatch(new CommandContext(server, connection, args));
    }

    private static byte[][] args(String... parts) {
        byte[][] out = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }
}
