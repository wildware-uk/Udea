package dev.wildware.udea.assets.compiler.script

import dev.wildware.udea.assets.compiler.AssetScope
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

/**
 * The `.udea.kts` script definition (issue #86).
 *
 * Three deliberate differences from the runtime definition it replaces
 * (`common/.../assets/dsl/script/scriptDef.kt`):
 *
 * 1. **The file is the bundle.** [AssetScope] is an *implicit receiver*, so a script's
 *    top-level statements are calls on it and the script returns nothing. The old definition
 *    needed a `bundle { }` wrapper and then had to inspect the script's result value, which
 *    produced the "script evaluated and returned a non-Asset object" failure — a failure that
 *    now has nowhere to live. Multiple named assets per file are unaffected: each call
 *    appends one.
 * 2. **An explicit dependency list.** The old one used
 *    `dependenciesFromClassContext(UdeaGameManager::class, wholeClasspath = true)`, which made
 *    the entire application classpath a compile input — every game class change invalidated
 *    every script. Here the static configuration names three artefacts, and the *build*
 *    classpath is supplied per-invocation by
 *    [dev.wildware.udea.assets.compiler.AssetCompiler]. Scripts still never see the generated
 *    `GameAssets` accessors (spec 3.6): they use `reference("id")`.
 * 3. **No base class with a callable on it.** The old `UdeaScript` carried a
 *    `weCantCallThis()` method that scripts could in fact call.
 *
 * `ide { acceptedLocations(Everywhere) }` is kept: it is what gives IntelliJ completion and
 * click-through inside a `.udea.kts` with no IDE plugin installed.
 */
@KotlinScript(
    displayName = "Udea Asset Script",
    fileExtension = "udea.kts",
    compilationConfiguration = UdeaAssetScriptConfiguration::class,
)
public abstract class UdeaAssetScript

/** The compilation configuration for [UdeaAssetScript]; see that class for the rationale. */
public object UdeaAssetScriptConfiguration : ScriptCompilationConfiguration({
    defaultImports(
        "dev.wildware.udea.assets.compiler.*",
        "kotlin.math.*",
    )

    // The file is the bundle.
    implicitReceivers(AssetScope::class)

    ide {
        acceptedLocations(ScriptAcceptedLocation.Everywhere)
    }

    jvm {
        jvmTarget("17")
        // Named artefacts, never `wholeClasspath = true`. This list is what an IDE resolves
        // against; a build resolves against the classpath AssetCompiler passes in.
        dependenciesFromClassContext(
            UdeaAssetScript::class,
            "udea-assets-compiler",
            "udea-diagnostics",
            "kotlin-stdlib",
        )
    }
}) {
    private fun readResolve(): Any = UdeaAssetScriptConfiguration
}
