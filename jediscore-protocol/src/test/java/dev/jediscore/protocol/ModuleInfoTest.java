package dev.jediscore.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Smoke test proving the protocol module's compile + test pipeline works. */
class ModuleInfoTest {

    @Test
    void exposesModuleName() {
        assertThat(ModuleInfo.name()).isEqualTo("jediscore-protocol");
    }
}
