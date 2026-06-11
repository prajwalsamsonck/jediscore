package dev.jediscore.datastructures;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Smoke test proving the datastructures module's compile + test pipeline works. */
class ModuleInfoTest {

    @Test
    void exposesModuleName() {
        assertThat(ModuleInfo.name()).isEqualTo("jediscore-datastructures");
    }
}
