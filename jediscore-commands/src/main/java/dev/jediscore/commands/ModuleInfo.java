package dev.jediscore.commands;

/**
 * Build-time identity marker for the {@code jediscore-commands} module.
 *
 * <p>Phase&nbsp;0 scaffolding. Replaced by per-family command implementations
 * (string, list, hash, set, zset, …) in later phases.
 */
public final class ModuleInfo {

    /** Canonical Gradle/artifact name of this module. */
    public static final String NAME = "jediscore-commands";

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
