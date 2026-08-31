package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.tools.ReplayToolModules
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reproduction block a `replay-equality` job summary carries, and the one claim in it.
 *
 * Issue #165 asks the summary to "link the `replay.bisect` MCP tool with the exact invocation
 * for local reproduction". There is no `replay.bisect` tool: issue #149 built a bisect *surface*
 * out of several `replay.*` tools, and a bisect is the loop a reader drives with them. A summary
 * naming a tool nobody can call would send a reader to a `tools/call` that answers with an
 * unknown-tool error, which is worse than saying nothing.
 *
 * So the fence here is not that the block reads well. It is that **every tool name it prints is
 * a tool the generated `replay.*` module actually declares**, checked against
 * `ReplayToolModules.Replay` rather than against a second hand-written list. That is the one
 * thing about a document embedded in a CI summary that can be wrong without anybody noticing:
 * nothing downstream of a job summary fails when it lies.
 */
class ReplayBisectGuideTest {

    private val declared: List<String> = ReplayToolModules.Replay.tools.map { it.name }

    @Test
    fun `every tool the guide tells a reader to call is one the replay module declares`() {
        for (tool in ReplayBisectGuide.TOOLS) {
            assertContains(
                declared, tool,
                "the bisect guide tells a reader to call '$tool', and the generated replay tool " +
                    "module declares ${declared.joinToString(", ")}. A summary naming a tool that " +
                    "does not exist is worse than one naming none.",
            )
        }
    }

    @Test
    fun `the guide prints every tool it lists, so the list is not decoration`() {
        // Without this the list above could be correct and the rendered text could name something
        // else entirely - a fence on a constant nothing reads.
        val rendered = ReplayBisectGuide.render(FIXTURE, divergentTick = Tick(1_200L))

        for (tool in ReplayBisectGuide.TOOLS) assertContains(rendered, tool)
    }

    @Test
    fun `a divergence sends the reader to the last tick the two legs agreed on`() {
        // Not the divergent tick. `replay.seek` lands *on* the tick given and the interesting
        // step is the one into the divergence, so seeking to the divergence itself has already
        // run the step a reader wants to watch.
        val rendered = ReplayBisectGuide.render(FIXTURE, divergentTick = Tick(1_200L))

        assertContains(rendered, "\"tick\": 1199")
        assertContains(rendered, "t1200")
        assertContains(rendered, FIXTURE)
    }

    @Test
    fun `a divergence at the very first tick does not send the reader to a tick before the recording`() {
        // The boundary the arithmetic above has: tick zero has no preceding tick to land on, and
        // a `replay.seek` to -1 is refused by `ReplaySession`, so the guide would be handing a
        // reader a call that cannot work.
        val rendered = ReplayBisectGuide.render(FIXTURE, divergentTick = Tick(0L))

        assertTrue(
            "-1" !in rendered,
            "the guide asks for a seek to a tick before the recording begins:\n$rendered",
        )
        assertContains(rendered, "\"tick\": 0")
    }

    @Test
    fun `with nothing to bisect the guide says so and still says how to run it`() {
        // A green summary is read too - by somebody who wants to run the gate before they push,
        // which is the whole reason the reproduction command is in it. Rendering nothing here
        // would make the block appear only in the situation where nobody has time to read it.
        val rendered = ReplayBisectGuide.render(FIXTURE, divergentTick = null)

        assertContains(rendered, "no divergence")
        assertContains(rendered, "udeaReplayEqualityProof")
        assertTrue(
            "replay.seek" !in rendered,
            "there is no tick to seek to, so the guide must not print a seek with a made-up " +
                "one:\n$rendered",
        )
    }

    @Test
    fun `the reproduction command names the fixture that actually diverged`() {
        // Two fixtures exist and the nightly replays the long one; a reproduction command that
        // always named the short one would run a different recording and find nothing.
        val rendered = ReplayBisectGuide.render("drift-36000.udearep", divergentTick = Tick(9_000L))

        assertContains(rendered, "-Pudea.replay.fixture=drift-36000.udearep")
        assertEquals(
            0, Regex("drift-3600\\.udearep").findAll(rendered).count(),
            "the guide names a fixture the job did not replay:\n$rendered",
        )
    }

    private companion object {
        const val FIXTURE: String = "drift-3600.udearep"
    }
}
