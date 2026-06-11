package dev.jediscore.replication;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Smoke test proving the replication module's compile + test pipeline works. */
class ModuleInfoTest {

    @Test
    void exposesModuleName() {
        assertThat(ModuleInfo.name()).isEqualTo("jediscore-replication");
    }
}
