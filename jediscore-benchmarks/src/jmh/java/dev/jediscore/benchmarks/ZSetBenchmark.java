package dev.jediscore.benchmarks;

import dev.jediscore.commands.CoreCommands;
import dev.jediscore.datastructures.SkipList;
import dev.jediscore.datastructures.SortedIndex;
import dev.jediscore.datastructures.TreeMapSortedIndex;
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
 * Sorted-set benchmarks.
 *
 * <p>{@link #zaddCommand}/{@link #zrangeCommand} measure ZADD/ZRANGE through the
 * command path. The remaining benchmarks compare {@link SkipList} against
 * {@link TreeMapSortedIndex} on the same 1000-element data — most pointedly on
 * {@code rank}, where the skiplist's spans give O(log n) and the tree must walk
 * O(n). All ops are size-neutral (insert+delete pairs, re-adds, reads).
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ZSetBenchmark {

    private static final int N = 1000;

    private ServerContext server;
    private CommandDispatcher dispatcher;
    private ClientConnection connection;
    private CommandExecutor executor;
    private byte[][] zrange;
    private byte[][] zaddExisting;

    private SortedIndex skip;
    private SortedIndex tree;
    private byte[] probe;
    private byte[] midMember;
    private double midScore;

    @Setup(Level.Trial)
    public void setUp() {
        CommandRegistry registry = new CommandRegistry();
        CoreCommands.registerAll(registry);
        executor = new CommandExecutor("bench-cmd");
        server = new ServerContext(ServerConfig.defaults("127.0.0.1", 0), registry, executor);
        dispatcher = new CommandDispatcher(server);
        connection = new ClientConnection(1, "127.0.0.1:0", "127.0.0.1:0", true);

        skip = new SkipList();
        tree = new TreeMapSortedIndex();
        for (int i = 0; i < N; i++) {
            byte[] m = ("m" + i).getBytes(StandardCharsets.UTF_8);
            run(args("ZADD", "z", Integer.toString(i), "m" + i));
            skip.insert(i, m);
            tree.insert(i, m);
        }
        midMember = "m500".getBytes(StandardCharsets.UTF_8);
        midScore = 500;
        probe = "probe".getBytes(StandardCharsets.UTF_8);
        zrange = args("ZRANGE", "z", "100", "109");
        zaddExisting = args("ZADD", "z", "500", "m500"); // no-op update, size-neutral
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.close();
    }

    @Benchmark
    public void zaddCommand(Blackhole bh) {
        bh.consume(run(zaddExisting));
    }

    @Benchmark
    public void zrangeCommand(Blackhole bh) {
        bh.consume(run(zrange));
    }

    @Benchmark
    public void skiplistInsertDelete(Blackhole bh) {
        skip.insert(1000.5, probe);
        bh.consume(skip.delete(1000.5, probe));
    }

    @Benchmark
    public void treemapInsertDelete(Blackhole bh) {
        tree.insert(1000.5, probe);
        bh.consume(tree.delete(1000.5, probe));
    }

    @Benchmark
    public void skiplistRank(Blackhole bh) {
        bh.consume(skip.rank(midScore, midMember));
    }

    @Benchmark
    public void treemapRank(Blackhole bh) {
        bh.consume(tree.rank(midScore, midMember));
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
