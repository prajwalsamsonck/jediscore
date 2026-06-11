package dev.jediscore.engine;

/**
 * Build-time identity marker for the {@code jediscore-engine} module.
 *
 * <p>Phase&nbsp;0 scaffolding. The {@link #dependsOn()} reference to the
 * datastructures module also proves the {@code engine -> datastructures}
 * compile edge is wired correctly.
 */
public final class ModuleInfo {

    /** Canonical Gradle/artifact name of this module. */
    public static final String NAME = "jediscore-engine";

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

    /**
     * Names a downstream module this one compiles against. Exists purely to
     * exercise the inter-module classpath edge during the Phase&nbsp;0 build.
     *
     * @return the name of a module on this module's compile classpath
     */
    public static String dependsOn() {
        return dev.jediscore.datastructures.ModuleInfo.name();
    }
}
