package dev.wildware.udea.render.headless

import dev.wildware.udea.diagnostics.DiagnosticReport
import dev.wildware.udea.diagnostics.DiagnosticSink
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.render.bytecode.BannedOwner
import dev.wildware.udea.render.bytecode.ClassRefScanner
import dev.wildware.udea.render.bytecode.GL_BANNED_OWNERS
import dev.wildware.udea.render.bytecode.TypeUse
import dev.wildware.udea.render.support.RepoLayout
import java.io.File

/**
 * The bytecode half of the headless rule: **no compiled class in a headless module may name
 * a GL type** (issue #117).
 *
 * ## It extends UDEA-MG-002; it does not restate it
 *
 * `UDEA-MG-002` -- owned by `udeaVerifyModuleGraph` in the build tooling -- is the
 * *configuration-level* rule: no GL backend, no LWJGL, no gdx natives on a headless module's
 * compile classpath. That rule is the one that should fail, because "you added
 * `gdx-backend-lwjgl3` to `udea-core`" is a far better message than forty class-level ones,
 * and it is checked first.
 *
 * This scan is the same rule one level down, and catches what a configuration check
 * structurally cannot:
 *
 * - a GL type reaching a headless module **transitively**, through a dependency that is
 *   itself allowed (`com.badlogicgames.gdx:gdx` is legal for `Vector2`, and carries
 *   `com/badlogic/gdx/graphics/Texture` in the same jar);
 * - a type named in *source* while the dependency that provides it is `compileOnly`, so it
 *   never appears on the runtime classpath the dependency rule inspects.
 *
 * That second case is exactly how the old tree lost the property: `SpriteRenderer.kt`
 * imported `com.badlogic.gdx.graphics.Texture` into a component the world tick touched, and
 * nothing failed.
 *
 * There is deliberately **no per-module allowlist**. The fix for a violation is always the
 * same -- move the code to `udea-render` -- and an allowlist is how a gate becomes a
 * formality.
 */
internal object HeadlessScan {

    /**
     * The rule id violations are reported under.
     *
     * Derived from `UDEA-MG-002` on purpose: a GL dependency and a GL type reference are the
     * same defect at two enforcement levels, and giving the bytecode level an unrelated id
     * would make a CI filter or an agent prompt have to know about both. The suffix says
     * which level fired.
     */
    const val RULE_ID: String = "UDEA-MG-002-BYTECODE"

    /**
     * The modules that must stay free of GL (issue #117 scope).
     *
     * `udea-render` is not here, and that is the whole shape of the design: it is the one
     * module allowed to see GL, so the identical class that fails this scan in `udea-core`
     * passes it after being moved.
     */
    val HEADLESS_MODULES: List<String> = listOf(
        "udea-annotations",
        "udea-agent",
        "udea-assets",
        "udea-core",
        "udea-gas",
        "udea-net",
    )

    /**
     * Runs the scan over [modules].
     *
     * @throws IllegalStateException if a module contributed no class files at all. An empty
     *   scan is a broken gate, not a clean module: it would pass forever while the module
     *   quietly grew GL. (The same reasoning as `UdeaLeafCheck`'s empty-classpath branch.)
     */
    fun run(
        modules: List<String> = HEADLESS_MODULES,
        banned: List<BannedOwner> = GL_BANNED_OWNERS,
        classFilesOf: (String) -> List<File> = { RepoLayout.classFiles(it) },
    ): DiagnosticReport {
        val sink = DiagnosticSink()
        for (module in modules) {
            val classFiles = classFilesOf(module)
            check(classFiles.isNotEmpty()) {
                "$module contributed no compiled classes to udeaVerifyHeadless. The gate is " +
                    "broken, not the module: a scan of nothing passes forever. Build the " +
                    "module before running the gate."
            }
            sink.reportAll(violations(module, classFiles, banned))
        }
        return sink.build()
    }

    /** Every banned reference in [classFiles], as diagnostics attributed to [module]. */
    fun violations(
        module: String,
        classFiles: List<File>,
        banned: List<BannedOwner> = GL_BANNED_OWNERS,
    ): List<UdeaDiagnostic> = classFiles
        .flatMap { file -> ClassRefScanner.scan(file) }
        .mapNotNull { use -> banned.firstOrNull { it.matches(use.owner) }?.let { use to it } }
        .map { (use, entry) -> diagnostic(module, use, entry) }

    private fun diagnostic(module: String, use: TypeUse, banned: BannedOwner): UdeaDiagnostic =
        UdeaDiagnostic(
            severity = Severity.Error,
            ruleId = RULE_ID,
            message = message(module, use, banned),
            span = span(module, use),
        )

    private fun message(module: String, use: TypeUse, banned: BannedOwner): String {
        val referenced = use.ownerMember?.let { "${use.owner}.$it" } ?: use.owner
        return "$module is a headless module, but ${use.className}.${use.member} references " +
            "$referenced -- ${banned.why}. Move the code to udea-render (spec 4: it is the " +
            "only module that touches GL). Reported under $RULE_ID, the bytecode extension " +
            "of UDEA-MG-002, which is the configuration-level rule owned by " +
            "udeaVerifyModuleGraph."
    }

    /**
     * Where the offending class was compiled from, or `null` when the source cannot be found
     * -- a generated class, or one compiled from a language directory this does not know
     * about. A span invented for such a class would point at a file that does not exist.
     */
    private fun span(module: String, use: TypeUse): SourceSpan? {
        val source = RepoLayout.sourceFileOf(module, use.className, use.sourceFile) ?: return null
        val line = use.line
        return SourceSpan(RepoLayout.relativePath(source), line, 0, line, 0)
    }
}
