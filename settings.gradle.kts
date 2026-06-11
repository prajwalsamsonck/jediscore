/*
 * Settings for the JediCore multi-module build.
 *
 * The Foojay toolchain resolver lets Gradle auto-provision a JDK 21 toolchain
 * on machines (and CI runners) that don't already have one, so the pinned
 * toolchain in jediscore.java-conventions is honoured everywhere.
 */
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "jediscore"

// The version catalog at gradle/libs.versions.toml is discovered automatically
// and exposed to every module as the `libs` accessor.

include(
    // --- leaf modules: zero internal deps, independently testable ---
    "jediscore-protocol",          // RESP2/RESP3 wire codec
    "jediscore-datastructures",    // the data types (dict, list, set, zset, hash)
    // --- engine + plug-ins ---
    "jediscore-engine",            // keyspace, expiry, command-dispatch SPI, exec loop
    "jediscore-commands",          // command implementations grouped by family
    "jediscore-network",           // Netty server + RESP<->Netty adapter
    "jediscore-persistence",       // snapshot + AOF (fork-free design)
    "jediscore-replication",       // master/replica state machine
    // --- assembly + tooling ---
    "jediscore-server",            // runnable entry point; wires everything together
    "jediscore-benchmarks",        // JMH harness for hot paths
)
