/*
 * jediscore-commands — command implementations grouped by family. Plugs into
 * the engine's command-dispatch SPI; depends only on the engine.
 */
plugins {
    id("jediscore.java-conventions")
}

dependencies {
    implementation(project(":jediscore-engine"))
}
