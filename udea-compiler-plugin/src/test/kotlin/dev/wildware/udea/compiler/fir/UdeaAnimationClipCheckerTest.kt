package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.testing.CheckerRun
import dev.wildware.udea.compiler.testing.TestSource
import dev.wildware.udea.compiler.testing.UdeaCompileTesting
import dev.wildware.udea.compiler.testing.source
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A misspelled clip name fails the build with a did-you-mean (issue #241).
 *
 * `Fox.Clips.Rnu` does not compile whether or not this plugin runs - Kotlin's own unresolved
 * reference sees to that. What the checker adds is the part spec section 5 makes mandatory: the
 * clip the author meant, under a stable rule id. So every case below that expects the rule also
 * expects the compilation to fail, and every case that expects silence expects nothing from this
 * rule rather than a clean compile.
 *
 * ### Why two modules
 *
 * The clips arrive the way they do in a game: `AnimationClip` from `udea-core` and the clip object
 * from generated code, both already compiled and on the classpath. So the upstream fixture is
 * compiled once with the plugin off, and each case compiles against its class output - the shape a
 * Gradle project dependency has - exactly as [UdeaAssetReferenceCheckerTest] does for asset ids.
 */
class UdeaAnimationClipCheckerTest {

    /** Stands in for `udea-core`'s `AnimationClip`: the checker keys on this class's name. */
    private val clipApi: TestSource = source(
        "AnimationClip.kt",
        """
        package dev.wildware.udea.core.spatial

        class AnimationClip(val index: Int, val name: String)
        """,
    )

    /** Stands in for the accessor generator's output for `Fox.glb`, plus an object that is not clips. */
    private val generated: TestSource = source(
        "Fox.kt",
        """
        package dev.wildware.udea.generated

        import dev.wildware.udea.core.spatial.AnimationClip

        object Fox {
            object Clips {
                val Survey: AnimationClip = AnimationClip(0, "Survey")
                val Walk: AnimationClip = AnimationClip(1, "Walk")
                val Run: AnimationClip = AnimationClip(2, "Run")
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

    // ---- plumbing -------------------------------------------------------------------------

    private fun downstream(code: String): CheckerRun =
        UdeaCompileTesting.compile(
            listOf(source("Game.kt", code)),
            extraClasspath = listOf(upstreamClasses),
        )

    /**
     * Exactly one diagnostic, under the clip rule, in a compilation that failed.
     *
     * Kotlin's own unresolved-reference error is expected in [CheckerRun.otherMessages] and is
     * not asserted away: the failure is the part that must never depend on this plugin.
     */
    private fun CheckerRun.assertOneClipError() = run {
        assertNotEquals(ExitCode.OK, exitCode, "a misspelled clip must fail the compile:\n" + describe())
        assertEquals(
            listOf(UdeaRules.UNRESOLVED_ANIMATION_CLIP.id),
            diagnostics.map { it.ruleId },
            "expected exactly one ${UdeaRules.UNRESOLVED_ANIMATION_CLIP.id}:\n" + describe(),
        )
        diagnostics.single()
    }
}
