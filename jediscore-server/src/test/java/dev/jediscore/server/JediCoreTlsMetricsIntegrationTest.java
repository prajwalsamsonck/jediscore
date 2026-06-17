package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.PersistenceConfig;
import dev.jediscore.engine.ServerConfig;
import dev.jediscore.engine.TlsConfig;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/** TLS client connections and the Prometheus metrics endpoint. */
class JediCoreTlsMetricsIntegrationTest {

    private static final TrustManager[] TRUST_ALL = {
            new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};

    @Test
    void pingOverTls() throws Exception {
        JediCore server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0),
                PersistenceConfig.defaults(), Map.of(), new TlsConfig(true, null, null));
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, TRUST_ALL, new SecureRandom());
            SSLSocketFactory factory = ssl.getSocketFactory();
            try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", server.port())) {
                socket.startHandshake(); // fails if the server is not really speaking TLS
                OutputStream out = socket.getOutputStream();
                out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[7];
                int n = in.read(buf);
                assertThat(new String(buf, 0, n, StandardCharsets.US_ASCII)).isEqualTo("+PONG\r\n");
            }
        } finally {
            server.close();
        }
    }

    @Test
    void prometheusMetricsEndpoint() throws Exception {
        JediCore server = JediCore.start(ServerConfig.defaults("127.0.0.1", 0));
        try {
            int metricsPort = server.enableMetrics(0);
            // Generate some activity so counters are non-zero.
            try (RespTestClient c = new RespTestClient(server.port())) {
                c.call("SET", "k", "v");
                c.call("GET", "k");
                c.call("GET", "missing");
            }

            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + metricsPort + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            String body = resp.body();
            assertThat(body).contains("jedicore_commands_processed");
            assertThat(body).contains("jedicore_keyspace_hits");
            assertThat(body).contains("jedicore_connected_clients");
            assertThat(body).contains("jvm_memory_used_bytes"); // JVM binder is wired
        } finally {
            server.close();
        }
    }
}
