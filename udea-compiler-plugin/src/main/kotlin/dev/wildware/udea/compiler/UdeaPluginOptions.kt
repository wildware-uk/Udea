package dev.wildware.udea.compiler

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * Everything the plugin reads out of its command line, as one value.
 *
 * Deliberately a plain data class with defaults for every field: the compile-testing
 * suites construct it directly rather than going through a [CompilerConfiguration].
 */
public data class UdeaPluginOptions(
    /** Inner kill switch. False means register nothing at all. */
    public val enabled: Boolean = true,
    /** Register the FIR checkers. */
    public val checkers: Boolean = true,
    /** Register FIR declaration synthesis. Gated (spec 3.2), hence false. */
    public val synthesis: Boolean = false,
    /** Reserved: compiled asset index paths, for the `reference("...")` checker. */
    public val assetIndex: List<String> = emptyList(),
    /** Where the KDoc harvester writes its index. `null` means "do not harvest". */
    public val kdocIndex: String? = null,
    /** Repo root every emitted `SourceSpan` is made relative to (spec 5). */
    public val repoRoot: String? = null,
)

/**
 * Configuration keys the [UdeaCommandLineProcessor] writes and the registrar reads.
 *
 * Keys are compared by identity, so these must be singletons.
 */
public object UdeaConfigurationKeys {
    public val ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("udea: plugin enabled")
    public val CHECKERS: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("udea: fir checkers enabled")
    public val SYNTHESIS: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("udea: fir declaration synthesis enabled")
    public val ASSET_INDEX: CompilerConfigurationKey<List<String>> =
        CompilerConfigurationKey.create("udea: asset index paths")
    public val KDOC_INDEX: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("udea: kdoc index path")
    public val REPO_ROOT: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("udea: repository root")
}

/** Reads the options back out of a configuration, applying the defaults of [UdeaPluginOptions]. */
public fun CompilerConfiguration.toUdeaPluginOptions(): UdeaPluginOptions {
    val defaults = UdeaPluginOptions()
    return UdeaPluginOptions(
        enabled = get(UdeaConfigurationKeys.ENABLED, defaults.enabled),
        checkers = get(UdeaConfigurationKeys.CHECKERS, defaults.checkers),
        synthesis = get(UdeaConfigurationKeys.SYNTHESIS, defaults.synthesis),
        assetIndex = get(UdeaConfigurationKeys.ASSET_INDEX)?.toList() ?: defaults.assetIndex,
        kdocIndex = get(UdeaConfigurationKeys.KDOC_INDEX) ?: defaults.kdocIndex,
        repoRoot = get(UdeaConfigurationKeys.REPO_ROOT) ?: defaults.repoRoot,
    )
}
