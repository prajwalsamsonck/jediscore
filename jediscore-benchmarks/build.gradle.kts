/*
 * jediscore-benchmarks — JMH micro-benchmarks for the hot paths.
 *
 * Benchmark sources live in the `jmh` source set created by the me.champeau.jmh
 * plugin. The smoke configuration below is intentionally short so CI can run a
 * benchmark end-to-end quickly; real phases override iteration counts per run.
 */
plugins {
    id("jediscore.java-conventions")
    alias(libs.plugins.jmh)
}

dependencies {
    // Benchmark code exercises the hot-path modules.
    "jmhImplementation"(project(":jediscore-protocol"))
    "jmhImplementation"(project(":jediscore-datastructures"))
    "jmhImplementation"(project(":jediscore-engine"))
    "jmhImplementation"(project(":jediscore-commands"))
}

jmh {
    // Fast "smoke" settings: prove the harness runs, not statistically rigorous.
    // Individual phases re-run with higher fidelity and paste the real numbers.
    warmupIterations.set(1)
    iterations.set(1)
    fork.set(1)
    warmup.set("1s")
    timeOnIteration.set("1s")
    failOnError.set(true)
    resultFormat.set("JSON")
}
