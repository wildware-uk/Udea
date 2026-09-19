package dev.wildware.udea.render.headless

import dev.wildware.udea.diagnostics.DiagnosticReport
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.render.bytecode.BytecodeBan
import dev.wildware.udea.render.bytecode.GL_BANNED_OWNERS
import dev.wildware.udea.render.support.RepoLayout
import java.io.File

/**
 * The bytecode half of the headless rule: **no compiled class in a headless module may name
 * a GL type** (issue #117).
 *
 * ## It extends UDEA-MG-002; it does not restate it
 *
 * `UDEA-MG-002` -- owned by `udeaVerifyModuleGraph` in the build tooling -- is the
 * *configuration-level* rule: no Kool, no GL backend and no LWJGL on a headless module's
 * compile classpath. That rule is the one that should fail, because "you added
 * `lwjgl-opengl` to `udea-core`" is a far better message than forty class-level ones, and it
 * is checked first.
 *
 * This scan is the same rule one level down, and catches what a configuration check
 * structurally cannot:
 *
 * - a GL type reaching a headless module **transitively**, through a dependency that is
 *   itself allowed. Until issue #213 that was concrete: `com.badlogicgames.gdx:gdx` was legal
 *   for `Vector2` and carried `com/badlogic/gdx/graphics/Texture` in the same jar;
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
     * The system property `udea-render`'s build script hands the designated list over in.
     *
     * Must equal `ModuleGraphRules.HEADLESS_MODULES_PROPERTY` in `build-logic`, which is
     * where the list is decided. A mismatch is not silent: [HEADLESS_MODULES] throws.
     */
    const val MODULES_PROPERTY: String = "udea.headless.modules"

    /**
     * The modules that must stay free of GL: read from the build, never written down here.
     *
     * `udea-render` is not in it, and that is the whole shape of the design: it is the one
     * module allowed to see GL, so the identical class that fails this scan in `udea-core`
     * passes it after being moved.
     *
     * The list itself is `ModuleGraphRules.HEADLESS_PROJECTS`, which is also what the
     * configuration-level rule `UDEA-MG-002` governs. Two copies of it is what this replaces:
     * they disagreed in both directions, so a GL backend on `udea-agent-host` or
     * `udea-diagnostics` passed the dependency rule *and* the bytecode scan while
     * `docs/module-graph.md` called them "the same rule, one level down".
     *
     * @throws IllegalStateException when the property is missing or empty. A scan with no
     *   modules passes forever, so a broken hand-off has to be louder than a green tick.
     */
    val HEADLESS_MODULES: List<String> by lazy { BytecodeBan.modulesFrom(MODULES_PROPERTY, GATE_TASK) }

    private const val GATE_TASK: String = "udeaVerifyHeadless"

    private val ban = BytecodeBan(RULE_ID, GATE_TASK, GL_BANNED_OWNERS) { module, use, entry ->
        "$module is a headless module, but ${use.className}.${use.member} references " +
            "${BytecodeBan.referenced(use)} -- ${entry.why}. Move the code to udea-render (spec 4: " +
            "it is the only module that touches GL). Reported under $RULE_ID, the bytecode " +
            "extension of UDEA-MG-002, which is the configuration-level rule owned by " +
            "udeaVerifyModuleGraph."
    }

    /**
     * Runs the scan over [modules].
     *
     * @throws IllegalStateException if a module contributed no class files at all. An empty
     *   scan is a broken gate, not a clean module: it would pass forever while the module
     *   quietly grew GL.
     */
    fun run(
        modules: List<String> = HEADLESS_MODULES,
        classFilesOf: (String) -> List<File> = { RepoLayout.classFiles(it) },
    ): DiagnosticReport = ban.run(modules, classFilesOf)

    /** Every banned reference in [classFiles], as diagnostics attributed to [module]. */
    fun violations(module: String, classFiles: List<File>): List<UdeaDiagnostic> =
        ban.violations(module, classFiles)
}
