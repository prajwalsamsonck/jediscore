/*
 * jediscore-replication — the master/replica state machine (PSYNC, backlog,
 * replica handshake). Needs the engine (to apply/produce the command stream)
 * and the network layer (to open replica connections).
 */
plugins {
    id("jediscore.java-conventions")
}

dependencies {
    implementation(project(":jediscore-engine"))
    implementation(project(":jediscore-network"))
}
