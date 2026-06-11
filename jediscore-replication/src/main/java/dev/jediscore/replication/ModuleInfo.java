package dev.jediscore.replication;

/**
 * Build-time identity marker for the {@code jediscore-replication} module.
 *
 * <p>Phase&nbsp;0 scaffolding. Replaced by the master/replica handshake, the
 * replication backlog and {@code PSYNC} support in Phase&nbsp;6.
 */
public final class ModuleInfo {

    /** Canonical Gradle/artifact name of this module. */
    public static final String NAME = "jediscore-replication";

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
