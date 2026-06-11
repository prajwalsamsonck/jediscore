package dev.jediscore.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/** Smoke test: the Phase 0 server boots and returns without throwing. */
class JediCoreServerTest {

    @Test
    void runsAndExitsCleanly() {
        assertThatCode(() -> JediCoreServer.run(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void bannerContainsVersion() {
        assertThat(JediCoreServer.banner()).contains(JediCoreServer.VERSION);
    }
}
