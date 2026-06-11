/*
 * Shared Java conventions for every JediCore module.
 *
 * WHY a convention plugin instead of `allprojects {}` in the root script:
 * convention plugins are typed, testable, and only apply to modules that opt in
 * via `plugins { id("jediscore.java-conventions") }`, so the dependency graph
 * stays explicit and there is no implicit cross-project configuration coupling.
 */
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
}

group = "dev.jediscore"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

// Read shared versions/coordinates from the version catalog imported in
// buildSrc/settings.gradle.kts.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Pin the Java toolchain. This is the *one* place the language level is set;
// the catalog's `java` version documents the intent and is asserted in CI.
extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

dependencies {
    // SLF4J is the only runtime dependency allowed in the core engine modules.
    add("implementation", libs.findLibrary("slf4j-api").get())

    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("assertj-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    // A logging backend so test output is readable; never leaks to a module's
    // own runtime classpath because it is test-scoped only.
    add("testRuntimeOnly", libs.findLibrary("logback-classic").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -Xlint:all surfaces correctness smells early. We exclude `processing`
    // (annotation-processor noise) and `serial` (we don't ship Serializable types).
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    options.release.set(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
