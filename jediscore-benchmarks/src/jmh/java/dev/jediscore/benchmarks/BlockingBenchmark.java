package dev.jediscore.benchmarks;

import dev.jediscore.engine.BlockingOp;
import dev.jediscore.engine.ClientConnection;
import dev.jediscore.engine.CommandExecutor;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.protocol.RespValue;
import java.nio.charset.StandardCharsets;
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

/**
 * Cost of the readiness signal the dispatcher runs after every write —
 * {@link dev.jediscore.engine.BlockingManager#signalKeys}. The headline case is
 * {@code blockedClients = 0} (no one waiting), which must be effectively free; the
 * other cases keep clients blocked on an <em>unrelated</em> key, so the signal
 * iterates the write's arguments without serving anyone — the worst realistic
 * per-write overhead while blocks exist.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BlockingBenchmark {

    @Param({"0", "1", "100"})
    private int blockedClients;

    private ServerContext context;
    private CommandExecutor executor;
    private final byte[][] setArgs = {
            "SET".getBytes(StandardCharsets.UTF_8),
            "user:1000".getBytes(StandardCharsets.UTF_8),
            "value".getBytes(StandardCharsets.UTF_8)};

    @Setup(Level.Trial)
    public void setUp() {
        executor = new CommandExecutor("bench-block");
        context = new ServerContext(ServerConfig.defaults("127.0.0.1", 0), new CommandRegistry(), executor);
        BlockingOp neverReady = new BlockingOp() {
            @Override public RespValue attempt(ServerContext server, ClientConnection conn) {
                return null;
            }
            @Override public RespValue timeoutReply() {
                return RespValue.NULL_ARRAY;
            }
        };
        for (int i = 0; i < blockedClients; i++) {
            ClientConnection conn = new ClientConnection(i, "127.0.0.1:0", "127.0.0.1:0", true);
            // Block on an unrelated key with no timeout, so the signal never serves.
            context.blocking().block(conn, 0,
                    List.of(new dev.jediscore.datastructures.Bytes(("waited:" + i).getBytes(StandardCharsets.UTF_8))),
                    neverReady, 0);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        context.blocking().shutdown();
        executor.close();
    }

    @Benchmark
    public ServerContext signalOnWrite() {
        context.blocking().signalKeys(0, setArgs);
        return context;
    }
}
