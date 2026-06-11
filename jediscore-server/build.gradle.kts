/*
 * jediscore-server — the assembly module and runnable entry point. It is the
 * only module that wires concrete implementations together, and the only one
 * that depends on a logging backend (Logback) at runtime.
 */
plugins {
    id("jediscore.java-conventions")
    application
}

dependencies {
    implementation(project(":jediscore-engine"))
    implementation(project(":jediscore-commands"))
    implementation(project(":jediscore-network"))
    implementation(project(":jediscore-persistence"))
    implementation(project(":jediscore-replication"))
    implementation(libs.bundles.micrometer)

    // Logging backend is bound only in the runnable app, never in libraries.
    runtimeOnly(libs.logback.classic)

    // Testcontainers is wired here for the wire-compatibility integration tests
    // that arrive once there is a real server to connect to (Phase 2+).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
}

application {
    mainClass.set("dev.jediscore.server.JediCoreServer")
    applicationName = "jediscore"
}
