/*
 * Build logic for JediCore. The `kotlin-dsl` plugin compiles the precompiled
 * script plugins under src/main/kotlin into reusable convention plugins.
 */
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}
