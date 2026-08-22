package dev.wildware.udea.build

/**
 * Gradle properties that switch build behaviour, read in one place so a typo cannot pass
 * for a decision.
 */
public object UdeaBuildFlags {

    /**
     * `-Pudea.compilerPlugin.enabled=false` builds without the K2 plugin.
     *
     * Spec 7 wants CI to prove the build is green with the plugin disabled, so that a broken
     * checker degrades to checkers-off rather than stopping every developer. The property is
     * honoured from Phase 0 — a no-op while `udea-compiler-plugin` has no plugin to apply —
     * so the codegen epic adds that CI job without reworking the workflow or this convention.
     */
    public const val COMPILER_PLUGIN_ENABLED: String = "udea.compilerPlugin.enabled"

    /**
     * Interprets [raw] as the value of [COMPILER_PLUGIN_ENABLED]; absent means enabled.
     *
     * Anything that is not exactly `true` or `false` fails the build. `-Pudea.compilerPlugin.enabled=flase`
     * quietly meaning "enabled" is the silent-failure smell in miniature: the developer
     * believes the checkers are off, CI believes they are on, and the disagreement surfaces
     * as an unexplained compile error somewhere else.
     */
    public fun compilerPluginEnabled(raw: String?): Boolean = when (raw) {
        null -> true
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException(
            "-P$COMPILER_PLUGIN_ENABLED must be exactly 'true' or 'false', not '$raw'. " +
                "An unrecognised value is a typo, and a typo that defaults to 'enabled' is a " +
                "developer and a CI run disagreeing about which compiler ran.",
        )
    }
}
