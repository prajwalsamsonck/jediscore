package dev.jediscore.persistence;

/**
 * Build-time identity marker for the {@code jediscore-persistence} module.
 *
 * <p>Phase&nbsp;0 scaffolding. Replaced by the fork-free snapshot writer and the
 * append-only file (AOF) implementation in Phase&nbsp;5.
 */
public final class ModuleInfo {

    /** Canonical Gradle/artifact name of this module. */
    public static final String NAME = "jediscore-persistence";

    private ModuleInfo() {
        // Utility holder; not instantiable.
    }

    /**
     * Returns the canonical module name.
     *
     * @return the module name, never {@code null}
     */
    public static String name() {
        return NAME;
    }
}
