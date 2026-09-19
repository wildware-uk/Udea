package dev.wildware.udea.render.libgdx

import dev.wildware.udea.diagnostics.DiagnosticReport
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.render.bytecode.BytecodeBan
import dev.wildware.udea.render.bytecode.LIBGDX_BANNED_OWNERS
import dev.wildware.udea.render.support.RepoLayout
import java.io.File

/**
 * The bytecode half of `UDEA-MG-009`: **no module's main bytecode may name LibGDX**, and scene2d
 * above all (issue #189).
 *
 * ## Why a second level at all
 *
 * `UDEA-MG-009`, owned by `udeaVerifyModuleGraph`, bans every LibGDX artifact from every project by
 * its coordinates, and it fires first: "you added `com.badlogicgames.gdx:gdx`" is the better
 * message. But a coordinate is only one way for a class to arrive. Source vendored into the tree
 * under LibGDX's own package has none (that is how `udea-fleks` carries Fleks), and neither does a
 * jar added with `files(...)`; a shaded jar republished under another group has the wrong one. Each
 * of those compiles a scene2d reference with `UDEA-MG-009` green. The class file names the owner
 * whichever route it took, and this reads the class file.
 *
 * ## Every module, not only the headless ones
 *
 * The headless scan bans GL where GL is forbidden, and `udea-render` is exempt from it by design.
 * LibGDX is forbidden everywhere, `udea-render` included - being allowed GL is not being allowed a
 * second renderer or a second UI toolkit - so this reads every project `settings.gradle.kts`
 * includes. `UdeaVerifyNoLibGdxTest` asserts the list against that file.
 */
internal object LibGdxScan {

    /** Derived from `UDEA-MG-009` as `UDEA-MG-002-BYTECODE` is from `UDEA-MG-002`. */
    const val RULE_ID: String = "UDEA-MG-009-BYTECODE"

    /** The system property `udea-render`'s build script hands the module list over in. */
    const val MODULES_PROPERTY: String = "udea.libgdx.modules"

    private const val GATE_TASK: String = "udeaVerifyNoLibGdx"

    /**
     * Every module the gate reads, as repository-relative directories (`udea-core`, `moba/game`).
     *
     * @throws IllegalStateException when the property is missing or empty.
     */
    val MODULES: List<String> by lazy { BytecodeBan.modulesFrom(MODULES_PROPERTY, GATE_TASK) }

    private val ban = BytecodeBan(RULE_ID, GATE_TASK, LIBGDX_BANNED_OWNERS) { module, use, entry ->
        "$module compiles ${use.className}.${use.member} against ${BytecodeBan.referenced(use)} -- " +
            "${entry.why}. No module may name LibGDX, however the class arrived: vendored source, " +
            "a files() jar or a shaded jar all pass UDEA-MG-009, the dependency-level rule owned " +
            "by udeaVerifyModuleGraph, which this extends. Reported under $RULE_ID."
    }

    /**
     * Runs the scan over [modules].
     *
     * @throws IllegalStateException if a module contributed no class files at all.
     */
    fun run(
        modules: List<String> = MODULES,
        classFilesOf: (String) -> List<File> = { RepoLayout.classFiles(it) },
    ): DiagnosticReport = ban.run(modules, classFilesOf)

    /** Every LibGDX reference in [classFiles], as diagnostics attributed to [module]. */
    fun violations(module: String, classFiles: List<File>): List<UdeaDiagnostic> =
        ban.violations(module, classFiles)
}
