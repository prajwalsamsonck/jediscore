/*
 * jediscore-persistence — point-in-time snapshots and the append-only file.
 * The JVM cannot fork() for copy-on-write snapshots the way Redis does; the
 * fork-free alternative is designed and documented in a later phase. Depends on
 * the engine to observe and serialise keyspace state.
 */
plugins {
    id("jediscore.java-conventions")
}

dependencies {
    implementation(project(":jediscore-engine"))
}
