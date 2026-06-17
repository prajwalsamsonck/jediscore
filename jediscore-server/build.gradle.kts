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
    // jqwik powers the property-based differential test against real Redis.
    testImplementation(libs.jqwik)
    // Test-only: enables Netty's self-signed certificate generation on JDK 17+ for
    // the TLS integration test (production TLS uses real cert/key files).
    testImplementation(libs.bouncycastle.pkix)
}

application {
    mainClass.set("dev.jediscore.server.JediCoreServer")
    applicationName = "jediscore"
}

tasks.withType<Test>().configureEach {
    // Forward the address of a reference Redis (host:port) to the differential
    // test, so it can run locally against a redis started via `docker run`. When
    // unset, the test falls back to Testcontainers (CI) or skips.
    System.getProperty("jedicore.diff.redis")?.let { systemProperty("jedicore.diff.redis", it) }
}
