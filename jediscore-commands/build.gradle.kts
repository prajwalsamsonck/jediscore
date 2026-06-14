/*
 * jediscore-commands — command implementations grouped by family. Plugs into
 * the engine's command-dispatch SPI; depends only on the engine.
 */
plugins {
    id("jediscore.java-conventions")
}

dependencies {
    implementation(project(":jediscore-engine"))

    // Embedded Lua interpreter for EVAL/EVALSHA (Phase 5D). The one runtime
    // dependency beyond the core set, and the engine the spec asks for.
    implementation(libs.luaj.jse)
}
