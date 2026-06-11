/*
 * jediscore-network — the Netty server and the adapter that bridges the pure
 * RESP codec to Netty's ByteBuf pipeline. Netty is an implementation detail and
 * is therefore an `implementation` (not `api`) dependency.
 */
plugins {
    id("jediscore.java-conventions")
}

dependencies {
    api(project(":jediscore-protocol"))
    api(project(":jediscore-engine"))
    implementation(libs.bundles.netty)
}
