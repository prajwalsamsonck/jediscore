package dev.jediscore.server;

import com.sun.net.httpserver.HttpServer;
import dev.jediscore.engine.ServerContext;
import dev.jediscore.engine.ServerStats;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exposes server metrics via Micrometer and a Prometheus scrape endpoint
 * ({@code GET /metrics}). The JediCore counters come from {@link ServerStats} and
 * the live registries; JVM memory/CPU come from the standard Micrometer binders.
 */
public final class MetricsExporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsExporter.class);

    private final PrometheusMeterRegistry registry;
    private HttpServer http;

    /**
     * Builds the registry and binds the meters.
     *
     * @param context the server context whose counters to expose
     */
    public MetricsExporter(ServerContext context) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        bind(context);
    }

    private void bind(ServerContext context) {
        ServerStats s = context.stats();
        counter("jedicore.commands.processed", s, ServerStats::commandsProcessed);
        counter("jedicore.connections.received", s, ServerStats::connectionsReceived);
        counter("jedicore.connections.rejected", s, ServerStats::rejectedConnections);
        counter("jedicore.keyspace.hits", s, ServerStats::keyspaceHits);
        counter("jedicore.keyspace.misses", s, ServerStats::keyspaceMisses);
        counter("jedicore.expired.keys", s, ServerStats::expiredKeys);
        counter("jedicore.evicted.keys", s, ServerStats::evictedKeys);
        gauge("jedicore.connected.clients", context, c -> c.connectionCount());
        gauge("jedicore.blocked.clients", context, c -> c.blocking().blockedCount());
        gauge("jedicore.memory.used.bytes", context, ServerContext::usedMemory);
        gauge("jedicore.db.keys", context, MetricsExporter::totalKeys);
        gauge("jedicore.connected.replicas", context, c -> c.replication().replicaCount());
        new JvmMemoryMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
    }

    private void counter(String name, ServerStats s, java.util.function.ToDoubleFunction<ServerStats> fn) {
        FunctionCounter.builder(name, s, fn).register(registry);
    }

    private void gauge(String name, ServerContext c, java.util.function.ToDoubleFunction<ServerContext> fn) {
        Gauge.builder(name, c, fn).strongReference(true).register(registry);
    }

    private static double totalKeys(ServerContext context) {
        long total = 0;
        for (int i = 0; i < context.databaseCount(); i++) {
            total += context.database(i).size();
        }
        return total;
    }

    /**
     * Starts the HTTP scrape endpoint.
     *
     * @param port the listen port ({@code 0} picks an ephemeral port)
     * @return the actually-bound port
     * @throws IOException if the socket cannot be bound
     */
    public int start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        http.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jedicore-metrics");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        int bound = http.getAddress().getPort();
        log.info("Prometheus metrics endpoint at http://0.0.0.0:{}/metrics", bound);
        return bound;
    }

    /** @return the Micrometer registry (for tests / embedding) */
    public PrometheusMeterRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        if (http != null) {
            http.stop(0);
        }
        registry.close();
    }
}
