package dev.jediscore.protocol;

/**
 * Build-time identity marker for the {@code jediscore-protocol} module.
 *
 * <p>This is Phase&nbsp;0 scaffolding: it exists so the module compiles, ships a
 * unit-testable public symbol, and proves the build/test pipeline end-to-end.
 * It will be superseded by the real RESP codec types in Phase&nbsp;1 but is kept
 * as a stable, dependency-free module identifier.
 */
public final class ModuleInfo {

    /** Canonical Gradle/artifact name of this module. */
    public static final String NAME = "jediscore-protocol";

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
