package dev.wildware.udea.compiler

/**
 * The names that the K2 plugin and its Gradle wiring have to agree on, in one place.
 *
 * `udea-gradle` cannot see these constants (it must not depend on a
 * `kotlin-compiler-embeddable` consumer), so it repeats the strings; this object is the
 * authority they are checked against.
 */
public object UdeaCompilerPlugin {

    /** `-Xplugin` plugin id, and the prefix of every `-P plugin:<id>:<option>=<value>`. */
    public const val PLUGIN_ID: String = "dev.wildware.udea"

    /**
     * The kill switch (spec 7). `false` makes [UdeaCompilerPluginRegistrar] register zero
     * extensions, so a plugin that a Kotlin release has broken still loads inertly.
     */
    public const val OPTION_ENABLED: String = "enabled"

    /** FIR checkers. On by default: checkers only add diagnostics (spec 3.2). */
    public const val OPTION_CHECKERS: String = "checkers"

    /**
     * FIR declaration synthesis. Off by default and stays off until the IDE-behaviour
     * spike in spec 3.2 returns a go.
     */
    public const val OPTION_SYNTHESIS: String = "synthesis"

    /** Reserved: paths to the compiled asset index the `reference("...")` checker will read. */
    public const val OPTION_ASSET_INDEX: String = "assetIndex"

    /** Path the KDoc harvester writes its index to. Absent means "do not harvest". */
    public const val OPTION_KDOC_INDEX: String = "kdocIndex"

    /**
     * The repository root every emitted [dev.wildware.udea.diagnostics.SourceSpan] is made
     * relative to.
     *
     * Spec 5 forbids an absolute path in a span, and the compiler only ever hands the plugin
     * absolute ones, so the root has to come from the build. There is deliberately no default:
     * guessing it from the process working directory would produce a path that is *relative*
     * but wrong, which is worse than failing, because it survives into a shipped artefact.
     */
    public const val OPTION_REPO_ROOT: String = "repoRoot"

    /**
     * The Gradle property that switches the whole plugin off before any `-Xplugin`
     * argument is produced. `-P${GRADLE_PROPERTY_ENABLED}=false` is the outer kill switch;
     * [OPTION_ENABLED] is the inner one.
     */
    public const val GRADLE_PROPERTY_ENABLED: String = "udea.compilerPlugin.enabled"

    /**
     * The single class name the scaffold's self-test checker fires on.
     *
     * It exists so "is the plugin actually loaded in this compilation?" is answerable by
     * compiling one file, without waiting for the real checkers. Real game sources never
     * declare this name, so the probe is invisible outside its own test.
     */
    public const val PROBE_CLASS_NAME: String = "UdeaCompilerPluginProbe"
}
