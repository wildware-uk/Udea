package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.testing.CheckerRun
import dev.wildware.udea.compiler.testing.TestSource
import dev.wildware.udea.compiler.testing.UdeaCompileTesting
import dev.wildware.udea.compiler.testing.source
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRule
import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A misspelled clip name, or a misspelled socket, fails the build with a did-you-mean
 * (issues #241, #260).
 *
 * `Fox.Clips.Rnu` does not compile whether or not this plugin runs - Kotlin's own unresolved
 * reference sees to that. What the checker adds is the part spec section 5 makes mandatory: the
 * clip the author meant, under a stable rule id. So every case below that expects the rule also
 * expects the compilation to fail, and every case that expects silence expects nothing from this
 * rule rather than a clean compile. `Chassis.Nodes.socket_rooof` is the same bargain for the
 * nodes a part is mounted on, and it is checked here rather than in a file of its own so that
 * the two cannot quietly come to behave differently.
 *
 * ### Why two modules
 *
 * The clips and nodes arrive the way they do in a game: `AnimationClip` and `ModelNode` from
 * `udea-core` and the generated objects from the asset build, all already compiled and on the
 * classpath. So the upstream fixture is compiled once with the plugin off, and each case
 * compiles against its class output - the shape a Gradle project dependency has - exactly as
 * [UdeaAssetReferenceCheckerTest] does for asset ids.
 */
class UdeaGeneratedMemberCheckerTest {

    /** Stands in for `udea-core`'s types: the checker keys on these class names. */
    private val clipApi: TestSource = source(
        "AnimationClip.kt",
        """
        package dev.wildware.udea.core.spatial

        class AnimationClip(val index: Int, val name: String)

        class ModelNode(val index: Int, val name: String)
        """,
    )

    /**
     * Stands in for the accessor generator's output for `Fox.glb` and a chassis with sockets,
     * plus an object that holds neither.
     */
    private val generated: TestSource = source(
        "Fox.kt",
        """
        package dev.wildware.udea.generated

        import dev.wildware.udea.core.spatial.AnimationClip
        import dev.wildware.udea.core.spatial.ModelNode

        object Fox {
            object Clips {
                val Survey: AnimationClip = AnimationClip(0, "Survey")
                val Walk: AnimationClip = AnimationClip(1, "Walk")
                val Run: AnimationClip = AnimationClip(2, "Run")
            }
        }

        object Chassis {
            object Nodes {
                val socket_roof: ModelNode = ModelNode(0, "socket_roof")
                val socket_left: ModelNode = ModelNode(1, "socket_left")
                val muzzle: ModelNode = ModelNode(2, "muzzle")
            }
        }

        object Settings {
            val Volume: Int = 3
        }
        """,
    )

    private val upstreamClasses: File by lazy {
        val run = UdeaCompileTesting.compile(listOf(clipApi, generated), applyPlugin = false)
        assertEquals(emptyList(), run.otherMessages, "the upstream fixture must compile:\n" + run.describe())
        File(run.workDir, "out")
    }

    @Test
    fun `a misspelled clip is an error at the name, with a did-you-mean`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Fox

            val clip = Fox.Clips.Rnu
            """,
        )

        val diagnostic = run.assertOneClipError()
        assertEquals(Severity.Error, diagnostic.severity)
        assertTrue("Did you mean 'Run'?" in diagnostic.message, diagnostic.message)
        assertTrue("Rnu" in diagnostic.message, diagnostic.message)
        val span = requireNotNull(diagnostic.span) { "no span:\n" + run.describe() }
        // `val clip = Fox.Clips.` is 21 characters, so the typo starts at column 22 of line 5.
        assertEquals(5, span.startLine, run.describe())
        assertEquals(22, span.startColumn, "the squiggle must sit on the clip name:\n" + run.describe())
    }

    @Test
    fun `a wrong-case clip name is suggested in its real case`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Fox

            val clip = Fox.Clips.walk
            """,
        )

        assertTrue("Did you mean 'Walk'?" in run.assertOneClipError().message, run.describe())
    }

    @Test
    fun `a name like no clip at all lists the clips the model has`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Fox

            val clip = Fox.Clips.Backflip
            """,
        )

        val message = run.assertOneClipError().message
        assertTrue("Did you mean" !in message, message)
        assertTrue("Run, Survey, Walk" in message, "the clips on offer, sorted, so the fix is one read: $message")
    }

    @Test
    fun `a misspelling through a value of the clips object is caught the same way`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Fox

            val clips = Fox.Clips
            val clip = clips.Surveyy
            """,
        )

        assertTrue("Did you mean 'Survey'?" in run.assertOneClipError().message, run.describe())
    }

    @Test
    fun `correctly spelled clips compile clean`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Fox

            val clips = listOf(Fox.Clips.Survey, Fox.Clips.Walk, Fox.Clips.Run)
            """,
        )

        assertEquals(emptyList(), run.diagnostics, run.describe())
        assertEquals(emptyList(), run.otherMessages, run.describe())
    }

    /**
     * The rule is about clips and nothing else. An unresolved member of an object that holds no
     * `AnimationClip` is Kotlin's error alone - naming a clip rule there would send the author
     * looking for a model that does not exist.
     */
    @Test
    fun `an unresolved member of an object without clips is not this rule`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Settings

            val volume = Settings.Volumee
            """,
        )

        assertNotEquals(ExitCode.OK, run.exitCode, run.describe())
        assertEquals(emptyList(), run.diagnostics, run.describe())
    }

    @Test
    fun `Suppress by rule id silences the did-you-mean but not the compile error`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Fox

            @Suppress("UDEA0016")
            val clip = Fox.Clips.Rnu
            """,
        )

        assertNotEquals(ExitCode.OK, run.exitCode, run.describe())
        assertEquals(emptyList(), run.diagnostics, run.describe())
    }

    /** Spec 7's degrade path: without the plugin the typo still fails, only the suggestion goes. */
    @Test
    fun `with the plugin not applied the typo still fails, without the suggestion`() {
        val run = UdeaCompileTesting.compile(
            listOf(source("Typo.kt", "package udea.game\n\nval clip = dev.wildware.udea.generated.Fox.Clips.Rnu\n")),
            applyPlugin = false,
            extraClasspath = listOf(upstreamClasses),
        )

        assertNotEquals(ExitCode.OK, run.exitCode, run.describe())
        assertEquals(emptyList(), run.diagnostics, run.describe())
    }

    // ---- the nodes a part is mounted on (issue #260) -----------------------------------------

    @Test
    fun `a misspelled socket is an error at the name, with a did-you-mean`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Chassis

            val socket = Chassis.Nodes.socket_rooof
            """,
        )

        val diagnostic = run.assertOneError(UdeaRules.UNRESOLVED_MODEL_NODE)
        assertEquals(Severity.Error, diagnostic.severity)
        assertTrue("Did you mean 'socket_roof'?" in diagnostic.message, diagnostic.message)
        assertTrue("Nodes has no node 'socket_rooof'" in diagnostic.message, diagnostic.message)
        val span = requireNotNull(diagnostic.span) { "no span:\n" + run.describe() }
        // `val socket = Chassis.Nodes.` is 27 characters, so the typo starts at column 28.
        assertEquals(5, span.startLine, run.describe())
        assertEquals(28, span.startColumn, "the squiggle must sit on the node name:\n" + run.describe())
    }

    @Test
    fun `a name like no node at all lists the nodes the model has`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Chassis

            val socket = Chassis.Nodes.turret_hardpoint
            """,
        )

        val message = run.assertOneError(UdeaRules.UNRESOLVED_MODEL_NODE).message
        assertTrue("Did you mean" !in message, message)
        assertTrue("muzzle, socket_left, socket_roof" in message, "the nodes on offer, sorted: $message")
    }

    @Test
    fun `correctly spelled sockets compile clean`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Chassis

            val sockets = listOf(Chassis.Nodes.socket_roof, Chassis.Nodes.muzzle)
            """,
        )

        assertEquals(emptyList(), run.diagnostics, run.describe())
        assertEquals(emptyList(), run.otherMessages, run.describe())
    }

    @Test
    fun `Suppress by the node rule id silences the did-you-mean but not the compile error`() {
        val run = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Chassis

            @Suppress("UDEA0018")
            val socket = Chassis.Nodes.socket_rooof
            """,
        )

        assertNotEquals(ExitCode.OK, run.exitCode, run.describe())
        assertEquals(emptyList(), run.diagnostics, run.describe())
    }

    /**
     * The two rules do not answer for each other: a missing clip is never reported as a missing
     * node, and the suppression of one does not reach the other.
     */
    @Test
    fun `a missing clip is the clip rule and a missing node is the node rule`() {
        val clip = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Fox

            val clip = Fox.Clips.Rnu
            """,
        )
        val node = downstream(
            """
            package udea.game

            import dev.wildware.udea.generated.Chassis

            val socket = Chassis.Nodes.socket_rooof
            """,
        )

        assertEquals(listOf(UdeaRules.UNRESOLVED_ANIMATION_CLIP.id), clip.diagnostics.map { it.ruleId }, clip.describe())
        assertEquals(listOf(UdeaRules.UNRESOLVED_MODEL_NODE.id), node.diagnostics.map { it.ruleId }, node.describe())
    }

    // ---- plumbing -------------------------------------------------------------------------

    private fun downstream(code: String): CheckerRun =
        UdeaCompileTesting.compile(
            listOf(source("Game.kt", code)),
            extraClasspath = listOf(upstreamClasses),
        )

    /**
     * Exactly one diagnostic, under [rule], in a compilation that failed.
     *
     * Kotlin's own unresolved-reference error is expected in [CheckerRun.otherMessages] and is
     * not asserted away: the failure is the part that must never depend on this plugin.
     */
    private fun CheckerRun.assertOneError(rule: UdeaRule) = run {
        assertNotEquals(ExitCode.OK, exitCode, "a misspelled name must fail the compile:\n" + describe())
        assertEquals(
            listOf(rule.id),
            diagnostics.map { it.ruleId },
            "expected exactly one ${rule.id}:\n" + describe(),
        )
        diagnostics.single()
    }

    private fun CheckerRun.assertOneClipError() = assertOneError(UdeaRules.UNRESOLVED_ANIMATION_CLIP)
}
