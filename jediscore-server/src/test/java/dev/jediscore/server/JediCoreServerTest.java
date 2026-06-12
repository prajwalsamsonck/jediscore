package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.ServerConfig;
import org.junit.jupiter.api.Test;

/** Unit tests for the entry point's banner and argument parsing. */
class JediCoreServerTest {

    @Test
    void bannerContainsVersion() {
        assertThat(JediCoreServer.banner()).contains(JediCoreServer.VERSION);
    }

    @Test
    void parseConfigDefaults() {
        ServerConfig config = JediCoreServer.parseConfig(new String[0]);
        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(6379);
    }

    @Test
    void parseConfigPortOnly() {
        ServerConfig config = JediCoreServer.parseConfig(new String[] {"7000"});
        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(7000);
    }

    @Test
    void parseConfigHostAndPort() {
        ServerConfig config = JediCoreServer.parseConfig(new String[] {"0.0.0.0:7001"});
        assertThat(config.host()).isEqualTo("0.0.0.0");
        assertThat(config.port()).isEqualTo(7001);
    }
}
