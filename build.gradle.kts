/*
 * Root build script.
 *
 * JediCore deliberately keeps the root script empty of shared logic: all
 * cross-module configuration lives in convention plugins under buildSrc
 * (see jediscore.java-conventions). This keeps each module's build file
 * declarative — it only states what is unique about that module.
 */

tasks.register("moduleGraph") {
    group = "help"
    description = "Prints the project's module list."
    val names = subprojects.map { it.path }.sorted()
    doLast {
        logger.lifecycle("JediCore modules:")
        names.forEach { logger.lifecycle("  $it") }
    }
}
