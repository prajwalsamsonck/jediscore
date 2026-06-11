/*
 * buildSrc has its own settings so it can import the project's version catalog.
 * Precompiled convention plugins then read versions through the VersionCatalogs
 * extension, keeping gradle/libs.versions.toml the single source of truth even
 * for build logic.
 */
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
