package dev.wildware.udea.codegen.agent

import dev.wildware.udea.codegen.ProcessorHarness
import dev.wildware.udea.diagnostics.UdeaRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The agent surface's build failures, driven through the real processor over throwaway sources.
 *
 * The fixture source set covers the success path and cannot cover this one: a source that must
 * make the build fail cannot live in a source set that has to compile.
 *
 * Every case asserts three things — that the build **fails**, that the message carries the
 * registered `UdeaRules` id, and that it is reported **at the offending symbol**. The third is
 * the one that is easy to lose and impossible to notice: a processor that passed `null` for the
 * symbol produces byte-identical message text and prints no file and no line in front of it.
 */
class AgentDiagnosticsTest {

    // --- descriptions: the text the model reasons over -----------------------------------------

    @Test
    fun `a tool with no description fails the build with UDEA0008 at the function`(@TempDir dir: File) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            class Tools {
                @AgentTool
                fun reset() {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue(error.message.startsWith(UdeaRules.AGENT_TOOL_DESCRIPTION.id), error.message)
        assertEquals(at("fun reset"), error.position)
        // A message that only says what is wrong leaves the author guessing; this one says what
        // to write, which is the difference the rule exists to make.
        assertTrue("what the tool does" in error.message, error.message)
    }

    @Test
    fun `a description under the minimum fails with UDEA0008 and quotes what was written`(
        @TempDir dir: File,
    ) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            class Tools {
                @AgentTool(description = "resets it")
                fun reset() {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue(UdeaRules.AGENT_TOOL_DESCRIPTION.id in error.message, error.message)
        assertTrue("resets it" in error.message, error.message)
        assertTrue("${UdeaRules.MIN_TOOL_DESCRIPTION}" in error.message, error.message)
        assertEquals(at("fun reset"), error.position)
    }

    @Test
    fun `a description of exactly the minimum length is accepted`(@TempDir dir: File) {
        // The boundary, stated as a passing case so the rule cannot quietly become "off by one
        // and nobody noticed": a rule that rejected the minimum would make its own constant a lie.
        val description = "x".repeat(UdeaRules.MIN_TOOL_DESCRIPTION)
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            class Tools {
                @AgentTool(description = "$description")
                fun reset() {
                }
            }
            """,
        )

        assertEquals(emptyList(), run.errors)
        assertTrue(run.generatedFiles.any { it.name == "ToolsResetTool.kt" }, "${run.generatedFiles}")
    }

    @Test
    fun `a parameter with no Arg description fails with UDEA0009 at the parameter`(@TempDir dir: File) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset(seed: Int) {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue(error.message.startsWith(UdeaRules.AGENT_ARG_DESCRIPTION.id), error.message)
        assertTrue("seed" in error.message, error.message)
        assertEquals(at("seed: Int)"), error.position)
    }

    // --- the closed world of parameter types ---------------------------------------------------

    @Test
    fun `an unsupported parameter type fails with UDEA0010 naming the type and the escape hatch`(
        @TempDir dir: File,
    ) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool
            import dev.wildware.udea.annotations.Arg

            class Payload(val x: Int)

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset(@Arg(description = "the payload to apply") payload: Payload) {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue(error.message.startsWith(UdeaRules.AGENT_TOOL_UNSUPPORTED_TYPE.id), error.message)
        assertTrue("fixtures.Payload" in error.message, error.message)
        // The old generator's answer to an unrecognised type was a blind serialisation
        // fallback, so the message has to name what an author can do instead.
        assertTrue("NetId" in error.message, error.message)
        assertEquals(at("payload: Payload"), error.position)
    }

    @Test
    fun `an optional parameter with no declared default is refused rather than guessed at`(
        @TempDir dir: File,
    ) {
        // KSP can see *that* a Kotlin parameter has a default and never the expression behind
        // it. Guessing would publish a manifest advertising a default the tool does not have.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool
            import dev.wildware.udea.annotations.Arg

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset(@Arg(description = "the seed to reset to") seed: Int = 7) {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue("@Arg(default" in error.message, error.message)
        assertTrue("nullable" in error.message, error.message)
        assertEquals(at("seed: Int = 7"), error.position)
    }

    @Test
    fun `a nullable parameter with a declared default is refused, since the default is dead`(
        @TempDir dir: File,
    ) {
        // The manifest would publish `default: "5"` and the schema would end `(default 5)`,
        // while the dispatcher's nullable branch hard-codes `null` for an absent argument. An
        // agent that did exactly what it was told would get null, and nothing would report it.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool
            import dev.wildware.udea.annotations.Arg

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset(
                    @Arg(description = "the seed to reset to", required = false, default = "5")
                    seed: Int?,
                ) {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue("nullable" in error.message, error.message)
        assertTrue("never once be used" in error.message, error.message)
        assertEquals(at("seed: Int?"), error.position)
    }

    @Test
    fun `a nullable parameter with a Kotlin default is refused, since null would overwrite it`(
        @TempDir dir: File,
    ) {
        // KSP cannot tell `= 5` from `= null`, and the dispatcher passes an explicit value
        // either way - so the one that is a trap has to take the one that is harmless with it.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool
            import dev.wildware.udea.annotations.Arg

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset(
                    @Arg(description = "the seed to reset to", required = false)
                    seed: Int? = 5,
                ) {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue("silently overwritten" in error.message, error.message)
        assertEquals(at("seed: Int? = 5"), error.position)
    }

    @Test
    fun `a nullable parameter is published as optional, which is what the dispatcher does`(
        @TempDir dir: File,
    ) {
        // The complement: nullable and legal, and `required` has to say so. The dispatcher
        // answers an absent nullable argument with `null` and never throws, so a manifest
        // marking it required would promise a value the tool does not insist on.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool
            import dev.wildware.udea.annotations.Arg

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset(@Arg(description = "the seed to reset to") seed: Int?) {
                }
            }
            """,
        )

        assertEquals(emptyList(), run.errors)
        val generated = run.generatedSource("ToolsResetTool.kt")
        assertTrue("required" in generated && "false" in generated, generated)
    }

    // --- visibility: generated code lives in a sibling file ------------------------------------

    @Test
    fun `a private tool function is refused at the function, not in a generated file`(
        @TempDir dir: File,
    ) {
        // Every other check passes, and then `compileKotlin` fails inside
        // build/generated/ksp/.../ToolsResetTool.kt with "Cannot access 'reset'" - a file the
        // author did not write, about a rule the annotation never stated.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                private fun reset() {
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue("private" in error.message, error.message)
        assertTrue("public or internal" in error.message, error.message)
        assertEquals(at("private fun reset"), error.position)
        assertFalse(run.generatedFiles.any { it.name == "ToolsResetTool.kt" }, "${run.generatedFiles}")
    }

    @Test
    fun `a private toolset class is refused at the class`(@TempDir dir: File) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            class Outer {
                private class Tools {
                    @AgentTool(description = "Reset the arena to its starting layout before a run.")
                    fun reset() {
                    }
                }
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue("Tools is private" in error.message, error.message)
        assertEquals(at("private class Tools"), error.position)
    }

    @Test
    fun `an internal toolset is accepted, and the generated object matches its visibility`(
        @TempDir dir: File,
    ) {
        // Internal is the normal shape for a debug toolset, and a `public object` whose
        // `invoke` took an internal receiver would not compile either. The fixture source set
        // carries the compiled proof; this is the rule.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            internal class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset() {
                }
            }
            """,
        )

        assertEquals(emptyList(), run.errors)
        assertTrue("internal object ToolsResetTool" in run.generatedSource("ToolsResetTool.kt"))
    }

    @Test
    fun `a private AgentState property is refused at the property`(@TempDir dir: File) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentState

            class Match {
                @AgentState
                private var score: Int = 0
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue("private" in error.message, error.message)
        assertTrue("public or internal" in error.message, error.message)
        assertEquals(at("private var score"), error.position)
    }

    @Test
    fun `a default that will not parse fails at the parameter, not on the first call`(
        @TempDir dir: File,
    ) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool
            import dev.wildware.udea.annotations.Arg

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset(
                    @Arg(description = "the seed to reset to", required = false, default = "soon")
                    seed: Int,
                ) {
                }
            }
            """,
        )

        assertFalse(run.succeeded)
        assertTrue("soon" in run.errors.single(), run.errors.single())
    }

    @Test
    fun `a top-level tool function is refused, because it has no toolset and no receiver`(
        @TempDir dir: File,
    ) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            @AgentTool(description = "Reset the arena to its starting layout before a run.")
            fun reset() {
            }
            """,
        )

        assertFalse(run.succeeded)
        assertTrue("class or object" in run.errors.single(), run.errors.single())
    }

    @Test
    fun `two tools resolving to one name fail with UDEA0012 naming both declarations`(
        @TempDir dir: File,
    ) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentTool

            class Tools {
                @AgentTool(description = "Reset the arena to its starting layout before a run.")
                fun reset() {
                }

                @AgentTool(name = "reset", description = "Reset the clock back to tick zero.")
                fun rewindClock() {
                }
            }
            """,
        )

        val error = run.errors.single()
        assertFalse(run.succeeded)
        assertTrue(error.startsWith(UdeaRules.AGENT_NAME_COLLISION.id), error)
        assertTrue("Tools.reset" in error, error)
        assertTrue("Tools.rewindClock" in error, error)
        assertFalse(
            run.generatedFiles.any { it.name.endsWith("Tool.kt") },
            "a colliding pair must emit nothing: ${run.generatedFiles.map { it.name }}",
        )
    }

    // --- @AgentState: scalars only, by construction --------------------------------------------

    @Test
    fun `AgentState on a List fails with UDEA0011 at the property`(@TempDir dir: File) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentState

            class Match {
                @AgentState
                var players: List<String> = emptyList()
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue(error.message.startsWith(UdeaRules.AGENT_STATE_NON_SCALAR.id), error.message)
        assertTrue("kotlin.collections.List" in error.message, error.message)
        // The message has to say what actually happens, because the bridge's own behaviour is
        // the surprise: the value is dropped from the digest, not rendered oddly.
        assertTrue("vanish" in error.message, error.message)
        assertEquals(at("var players"), error.position)
    }

    @Test
    fun `AgentState on a Double is refused rather than silently narrowed to a Float`(
        @TempDir dir: File,
    ) {
        // `GameStateSink` publishes Int, Long, Float, Boolean and String, and rounds floats to
        // four decimal places. Generating a `.toFloat()` nobody wrote would be a silent
        // narrowing in generated code, which is the class of defect this generator exists to
        // remove - so the author is told to declare the property `Float` and mean it.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentState

            class Match {
                @AgentState
                var meanFrameMillis: Double = 0.0
            }
            """,
        )

        assertFalse(run.succeeded)
        val error = run.errors.single()
        assertTrue(UdeaRules.AGENT_STATE_NON_SCALAR.id in error, error)
        assertTrue("kotlin.Double" in error, error)
    }

    @Test
    fun `AgentState on a nested object fails, since the digest keeps no object`(@TempDir dir: File) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentState

            class Team(val name: String)

            class Match {
                @AgentState
                var winner: Team = Team("none")
            }
            """,
        )

        assertFalse(run.succeeded)
        assertTrue(UdeaRules.AGENT_STATE_NON_SCALAR.id in run.errors.single(), run.errors.single())
    }

    @Test
    fun `two properties resolving to one digest key fail with UDEA0012`(@TempDir dir: File) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentState

            class Match {
                @AgentState(name = "score")
                var homeScore: Int = 0

                @AgentState(name = "score")
                var awayScore: Int = 0
            }
            """,
        )

        val error = run.errors.single()
        assertFalse(run.succeeded)
        assertTrue(error.startsWith(UdeaRules.AGENT_NAME_COLLISION.id), error)
        assertTrue("homeScore" in error, error)
        assertTrue("awayScore" in error, error)
    }

    @Test
    fun `one digest key published by two types in a module fails, since the game block is flat`(
        @TempDir dir: File,
    ) {
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentState

            class Match {
                @AgentState(name = "score")
                var score: Int = 0
            }

            class Scoreboard {
                @AgentState(name = "score")
                var total: Int = 0
            }
            """,
        )

        val error = run.errorDiagnostics.single()
        assertFalse(run.succeeded)
        assertTrue(error.message.startsWith(UdeaRules.AGENT_NAME_COLLISION.id), error.message)
        assertTrue("fixtures.Match" in error.message, error.message)
        assertTrue("fixtures.Scoreboard" in error.message, error.message)
        // Reported at the *second* declaring class, which is the one an author can move. A
        // message naming two canonical names and no file at all is what this asserts against.
        assertEquals(at("class Scoreboard"), error.position)
    }

    @Test
    fun `every scalar type the digest accepts is accepted`(@TempDir dir: File) {
        // The complement of the rejections above. Without it, a regression that rejected
        // everything would make every test in this class pass.
        val run = run(
            dir,
            """
            package fixtures

            import dev.wildware.udea.annotations.AgentState

            enum class Phase { Warmup, Running }

            class Match {
                @AgentState var ticks: Int = 0
                @AgentState var millis: Long = 0L
                @AgentState var scale: Float = 1f
                @AgentState var paused: Boolean = false
                @AgentState var label: String = ""
                @AgentState var phase: Phase = Phase.Warmup
            }
            """,
        )

        assertEquals(emptyList(), run.errors)
        assertTrue(run.generatedFiles.any { it.name == "MatchAgentState.kt" }, "${run.generatedFiles}")
    }

    /**
     * Runs the processor over one throwaway file, always called `Fixture.kt` so that the
     * position assertions above can name a file without depending on which declaration in the
     * source happens to come first.
     */
    private fun run(dir: File, source: String): ProcessorHarness.Run {
        lastSource = source.trimIndent().trim() + "\n"
        return ProcessorHarness.run(dir, mapOf(FILE to lastSource))
    }

    /**
     * `Fixture.kt:7` for the line [marker] first appears on.
     *
     * Computed rather than written out, because a hard-coded line number turns every edit to a
     * fixture above into a test failure that says nothing about the defect under test - and the
     * temptation then is to delete the position assertion, which is the one that catches a
     * diagnostic reported with no symbol at all.
     */
    private fun at(marker: String): String {
        val index = lastSource.lineSequence().indexOfFirst { marker in it }
        check(index >= 0) { "no line containing '$marker' in:\n$lastSource" }
        return "$FILE:${index + 1}"
    }

    private var lastSource: String = ""

    private companion object {
        const val FILE = "Fixture.kt"
    }
}
