package dev.jediscore.datastructures;

/**
 * Build-time identity marker for the {@code jediscore-datastructures} module.
 *
 * <p>Phase&nbsp;0 scaffolding (see {@code dev.jediscore.protocol.ModuleInfo} for
 * the rationale). Replaced by the real data types (dict, list, set, zset, hash)
 * in later phases.
 */
public final class ModuleInfo {

    /** Canonical Gradle/artifact name of this module. */
    public static final String NAME = "jediscore-datastructures";

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
