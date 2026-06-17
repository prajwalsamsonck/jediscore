package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jediscore.engine.MaxmemoryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for redis.conf-style file + CLI loading. */
class BootConfigTest {

    @Test
    void loadsRedisConfFile(@TempDir Path dir) throws Exception {
        Path conf = dir.resolve("redis.conf");
        Files.writeString(conf, """
                # a comment
                port 1234
                bind 0.0.0.0
                databases 8
                maxmemory 50mb
                maxmemory-policy allkeys-lru
                save 900 1 300 100
                appendonly yes
                appendfsync always
                hash-max-listpack-entries 64
                """);

        BootConfig boot = BootConfig.load(new String[]{conf.toString()});
        assertThat(boot.configFile()).isEqualTo(conf.toString());
        assertThat(boot.server().host()).isEqualTo("0.0.0.0");
        assertThat(boot.server().port()).isEqualTo(1234);
        assertThat(boot.server().databases()).isEqualTo(8);
        assertThat(boot.server().maxMemory()).isEqualTo(50L * 1024 * 1024);
        assertThat(boot.server().maxMemoryPolicy()).isEqualTo(MaxmemoryPolicy.ALLKEYS_LRU);
        assertThat(boot.server().hashMaxListpackEntries()).isEqualTo(64);
        assertThat(boot.persistence().savePoints()).hasSize(2);
        assertThat(boot.persistence().appendOnly()).isTrue();
    }

    @Test
    void cliOverridesFile(@TempDir Path dir) throws Exception {
        Path conf = dir.resolve("redis.conf");
        Files.writeString(conf, "port 1234\nmaxmemory 10mb\n");
        BootConfig boot = BootConfig.load(new String[]{conf.toString(), "--port", "9999", "--maxmemory", "1gb"});
        assertThat(boot.server().port()).isEqualTo(9999);
        assertThat(boot.server().maxMemory()).isEqualTo(1024L * 1024 * 1024);
    }

    @Test
    void addressPositionalAndCliOnly() {
        BootConfig boot = BootConfig.load(new String[]{"0.0.0.0:7001", "--maxmemory-policy", "noeviction"});
        assertThat(boot.server().host()).isEqualTo("0.0.0.0");
        assertThat(boot.server().port()).isEqualTo(7001);
        assertThat(boot.configFile()).isNull();
    }

    @Test
    void saveDisabledWithEmptyDirective(@TempDir Path dir) throws Exception {
        Path conf = dir.resolve("redis.conf");
        Files.writeString(conf, "save \"\"\n");
        BootConfig boot = BootConfig.load(new String[]{conf.toString()});
        assertThat(boot.persistence().savePoints()).isEmpty();
    }
}
