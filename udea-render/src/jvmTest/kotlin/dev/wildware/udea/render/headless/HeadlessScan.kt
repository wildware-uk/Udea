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
 * There is **one per-module allowance, and it is not for GL**: the asset compiler converts an
 * `.fbx` model to glTF with Assimp, whose binding is LWJGL's (issue #244), so in the modules
 * `ModuleGraphRules.MODEL_CONVERTER_PROJECTS` names, a reference into
 * `ModuleGraphRules.MODEL_CONVERTER_NAMESPACE` is excused ([EXCUSED]). It is handed over from
 * `build-logic` as the module list is, and every other `org/lwjgl/` reference in those modules
 * still fails. For GL the fix is always the same -- move the code to `udea-render` -- and a
 * wider allowlist is how a gate becomes a formality.
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
     * The system property `udea-render`'s build script hands the FBX converter's allowance over
     * in, as `<namespace>:<module>,<module>`. Must equal `ModuleGraphRules.MODEL_CONVERTER_PROPERTY`.
     */
    const val MODEL_CONVERTER_PROPERTY: String = "udea.headless.modelConverter"

    /**
     * Namespaces excused per module: the FBX converter's `org/lwjgl/assimp/` in the modules that
     * run it (issue #244), read from the build like [HEADLESS_MODULES].
     *
     * @throws IllegalStateException when the property is missing or malformed. A hand-off that
     *   silently read as "nothing excused" would fail the gate on the asset compiler, and one that
     *   read as "everything excused" would pass it on anything; both are louder as an exception.
     */
    val EXCUSED: Map<String, List<String>> by lazy {
        val raw = checkNotNull(System.getProperty(MODEL_CONVERTER_PROPERTY)) {
            "-D$MODEL_CONVERTER_PROPERTY was not set. The FBX converter's allowance comes from " +
                "ModuleGraphRules via udea-render's build script; run this through Gradle."
        }
        val namespace = raw.substringBeforeLast(':')
        val modules = raw.substringAfterLast(':').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        check(namespace.endsWith('/') && namespace.startsWith("org/lwjgl/") && modules.isNotEmpty()) {
            "-D$MODEL_CONVERTER_PROPERTY was '$raw', not '<an org/lwjgl/ package>/:<module>,...'"
        }
        modules.associateWith { listOf(namespace) }
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
        excused: Map<String, List<String>> = EXCUSED,
    ): DiagnosticReport = ban.run(modules, classFilesOf, excused)

    /**
     * Every banned reference in [classFiles], as diagnostics attributed to [module], except a
     * reference into one of the [excused] namespaces.
     */
    fun violations(module: String, classFiles: List<File>, excused: List<String> = emptyList()): List<UdeaDiagnostic> =
        ban.violations(module, classFiles, excused)
}
