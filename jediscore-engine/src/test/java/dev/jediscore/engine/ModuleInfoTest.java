package dev.jediscore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Smoke test proving the engine module compiles and sees its dependency. */
class ModuleInfoTest {

    @Test
    void exposesModuleName() {
        assertThat(ModuleInfo.name()).isEqualTo("jediscore-engine");
    }

    @Test
    void seesDatastructuresOnClasspath() {
        assertThat(ModuleInfo.dependsOn()).isEqualTo("jediscore-datastructures");
    }
}
