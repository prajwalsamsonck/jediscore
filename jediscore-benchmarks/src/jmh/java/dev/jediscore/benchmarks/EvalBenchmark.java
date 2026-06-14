package dev.jediscore.benchmarks;

import dev.jediscore.commands.CoreCommands;
import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandDispatcher;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
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

/**
 * EVAL throughput with the compiled-chunk cache warm: this measures the steady
 * per-call cost of a script — KEYS/ARGV binding, the Lua call, and value
 * conversion — for a pure-Lua script and for one that issues a {@code redis.call}.
 * Compilation is amortised (cached by SHA), as in Redis.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EvalBenchmark {

    private ServerContext context;
    private CommandExecutor executor;
    private ClientConnection conn;
    private byte[][] returnConstant;
    private byte[][] redisCall;

    @Setup(Level.Trial)
    public void setUp() {
        executor = new CommandExecutor("bench-eval");
        CommandRegistry registry = new CommandRegistry();
        CoreCommands.registerAll(registry);
        context = new ServerContext(ServerConfig.defaults("127.0.0.1", 0), registry, executor);
        context.setDispatcher(new CommandDispatcher(context));
        conn = new ClientConnection(1, "127.0.0.1:0", "127.0.0.1:0", true);
        returnConstant = command("EVAL", "return 1", "0");
        redisCall = command("EVAL", "redis.call('set', KEYS[1], ARGV[1]) return 1", "1", "k", "v");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        context.blocking().shutdown();
        executor.close();
    }

    @Benchmark
    public RespValue evalReturnConstant() {
        return context.dispatcher().dispatch(new CommandContext(context, conn, returnConstant));
    }

    @Benchmark
    public RespValue evalWithRedisCall() {
        return context.dispatcher().dispatch(new CommandContext(context, conn, redisCall));
    }

    private static byte[][] command(String... parts) {
        byte[][] out = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }
}
