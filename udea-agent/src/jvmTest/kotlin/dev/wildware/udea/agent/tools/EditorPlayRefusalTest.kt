package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `editor.play` and `editor.stop` where they cannot do their job (issue #196), through `SimHarness`
 * as an agent calls them.
 *
 * The whole Play-fight-Stop round trip is `MobaEditorPlayTest`'s, over a real `moba` world whose
 * components a level can carry. This harness's components are not `@Serializable`, which is what
 * makes the refused Play reachable here at all.
 */
class EditorPlayRefusalTest {

    @Test
    fun `an editor given no way to play refuses play and stop by name`() {
        val harness = ToolsetHarness(withEditor = true)

        assertEquals(EditorToolset.NO_PLAY, harness.failure("editor.play").kind)
        assertEquals(EditorToolset.NO_PLAY, harness.failure("editor.stop").kind)
    }

    @Test
    fun `stop with nothing playing is refused and changes nothing`() {
        val harness = ToolsetHarness(withEditor = true, withPlay = true)
        harness.ok("time.pause")
        val tick = harness.sim.tick

        assertEquals(EditorToolset.NOT_PLAYING, harness.failure("editor.stop").kind)
        assertEquals(tick, harness.sim.tick)
        assertTrue(harness.sim.time.paused)
    }

    @Test
    fun `a play whose world cannot be saved is refused, and the world stays paused and the drag open`() {
        val harness = ToolsetHarness(withEditor = true, withPlay = true)
        val unit = harness.place(x = 1f)
        harness.ok("time.pause")
        val begun = harness.ok("editor.begin_edit", "entities" to "${unit.raw}", "fields" to "Transform.position.x")
        val sessionId = Regex("\"sessionId\":(\\d+)").find(begun)!!.groupValues[1]

        val refused = harness.failure("editor.play")

        assertEquals(EditorToolset.LEVEL_NOT_SAVEABLE, refused.kind, refused.toString())
        assertTrue(harness.sim.time.paused, "a refused Play set the world running")
        // The drag is still open: a refused Play must not have committed it.
        assertIs<AgentResult.Ok>(harness.call("editor.update_edit", "sessionId" to sessionId, "values" to "Transform.position.x=2"))
        assertEquals(EditorToolset.NOT_PLAYING, harness.failure("editor.stop").kind)
    }

    @Test
    fun `play then ticks then stop on an empty world puts the clock back and pauses`() {
        val harness = ToolsetHarness(withEditor = true, withPlay = true)
        harness.ok("time.pause")
        harness.sim.step(5)
        val tick: Tick = harness.sim.tick

        harness.ok("editor.play")
        assertTrue(!harness.sim.time.paused, "Play left the world paused")
        harness.sim.step(40)
        harness.ok("editor.stop")

        assertEquals(tick, harness.sim.tick)
        assertTrue(harness.sim.time.paused, "Stop left the world running")
    }
}
