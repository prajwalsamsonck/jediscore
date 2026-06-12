package dev.jediscore.benchmarks;

import dev.jediscore.protocol.RespEncoder;
import dev.jediscore.protocol.RespParser;
import dev.jediscore.protocol.RespValue;
import dev.jediscore.protocol.RespVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
 * Hot-path micro-benchmarks for the RESP codec: decoding a typical
 * {@code SET key value} request and encoding a bulk-string reply.
 *
 * <p>Both reuse a single buffer across invocations so the measurement reflects
 * the codec's own work (and its unavoidable per-request argument allocation),
 * not buffer churn. Iteration counts here are placeholders; the build's "smoke"
 * profile overrides them for a fast run.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class RespParseBenchmark {

    private ByteBuf requestBuffer;
    private int requestLength;

    private ByteBuf replyBuffer;
    private RespValue reply;

    @Setup(Level.Trial)
    public void setUp() {
        byte[] request = "*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$7\r\nmyvalue\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        requestLength = request.length;
        requestBuffer = Unpooled.buffer(request.length).writeBytes(request);

        replyBuffer = Unpooled.buffer(64);
        reply = RespValue.bulk("myvalue");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        requestBuffer.release();
        replyBuffer.release();
    }

    @Benchmark
    public void parseRequest(Blackhole bh) {
        requestBuffer.setIndex(0, requestLength); // rewind reader, keep the bytes
        bh.consume(RespParser.parseRequest(requestBuffer));
    }

    @Benchmark
    public void encodeBulkReply(Blackhole bh) {
        replyBuffer.clear();
        RespEncoder.encode(reply, replyBuffer, RespVersion.RESP2);
        bh.consume(replyBuffer.readableBytes());
    }
}
