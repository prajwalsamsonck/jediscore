/*
 * jediscore-engine — keyspace, expiry, the command-dispatch SPI and the
 * single-writer execution loop. It exposes protocol and datastructure types on
 * its API surface, so they are declared as `api` dependencies.
 */
plugins {
    id("jediscore.java-conventions")
}

dependencies {
    api(project(":jediscore-protocol"))
    api(project(":jediscore-datastructures"))
}
