package dev.jediscore.network;

/**
 * Build-time identity marker for the {@code jediscore-network} module.
 *
 * <p>Phase&nbsp;0 scaffolding. Replaced by the Netty server bootstrap and the
 * RESP&lt;-&gt;ByteBuf pipeline adapter in Phase&nbsp;2.
 */
public final class ModuleInfo {

    /** Canonical Gradle/artifact name of this module. */
    public static final String NAME = "jediscore-network";

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
