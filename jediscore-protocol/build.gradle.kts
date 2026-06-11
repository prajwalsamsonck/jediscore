/*
 * jediscore-protocol — RESP2/RESP3 wire codec.
 *
 * A leaf module with zero internal dependencies. It must stay free of Netty so
 * the protocol logic is reusable and unit-testable against plain byte arrays;
 * the Netty bridge lives in jediscore-network.
 */
plugins {
    id("jediscore.java-conventions")
}
