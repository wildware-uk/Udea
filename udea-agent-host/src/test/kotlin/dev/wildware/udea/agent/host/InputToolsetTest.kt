package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.Axis2DBinding
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.InputBindings
import dev.wildware.udea.render.input.Intent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The agent's half of the input model: it drives the **same** seam a keyboard does.
 *
 * Every assertion here ends by sampling the [InjectedIntent] into an [Intent], because that is
 * the only thing the simulation ever sees. A test that asserted on the toolset's JSON alone would
 * pass for a toolset that recorded presses into a field nothing sampled - which is close to what
 * the Phase 1 `Gdx.input.inputProcessor` injection actually was.
 */
class InputToolsetTest {

    @Test
    fun `a press reaches the next sampled intent and stays held`() {
        val fixture = Fixture()

        assertOk(fixture.toolset.press("t/fire"))

        assertTrue(fixture.sample().isJustPressed(fixture.fire), "the press produced no edge")
        assertTrue(fixture.sample().isPressed(fixture.fire), "the press did not stay held")
    }

    /** One press is one edge, however many ticks sample it. */
    @Test
    fun `a press produces exactly one edge`() {
        val fixture = Fixture()
        fixture.toolset.press("t/fire")

        var edges = 0
        repeat(5) { if (fixture.sample().isJustPressed(fixture.fire)) edges++ }

        assertEquals(1, edges, "one input.press produced $edges edges")
    }

    /** A tap is an edge with no hold: the agent's version of a key tapped between two frames. */
    @Test
    fun `a tap is an edge that is never held`() {
        val fixture = Fixture()
        assertOk(fixture.toolset.tap("t/fire"))

        val first = fixture.sample()
        assertTrue(first.isJustPressed(fixture.fire))
        assertFalse(first.isPressed(fixture.fire))
        assertFalse(fixture.sample().isJustPressed(fixture.fire), "a tap fired twice")
    }

    @Test
    fun `release stops the hold`() {
        val fixture = Fixture()
        fixture.toolset.press("t/fire")
        fixture.sample()
        assertOk(fixture.toolset.release("t/fire"))

        assertFalse(fixture.sample().isPressed(fixture.fire))
    }

    @Test
    fun `set_axis deflects and clamps`() {
        val fixture = Fixture()

        val ok = assertOk(fixture.toolset.setAxis("t/move", 0.5f, -1f))
        assertContains(ok.json, "\"x\":0.5")

        val intent = fixture.sample()
        assertEquals(0.5f, intent.axisX(fixture.move))
        assertEquals(-1f, intent.axisY(fixture.move))

        assertOk(fixture.toolset.setAxis("t/move", 9f, 0f))
        assertEquals(1f, fixture.sample().axisX(fixture.move), "an out-of-range axis was not clamped")
    }

    /**
     * The tool that stops an agent leaving the character walking into a wall forever.
     *
     * A session that ends mid-press has no other way back: the next agent to connect inherits a
     * held action with nothing on screen to explain it.
     */
    @Test
    fun `release_all centres everything`() {
        val fixture = Fixture()
        fixture.toolset.press("t/fire")
        fixture.toolset.setAxis("t/move", 1f, 1f)

        assertOk(fixture.toolset.releaseAll())

        assertTrue(fixture.sample().isIdle(), "release_all left input behind")
    }

    /** The discovery call: without it a wrong name and a dead control look identical. */
    @Test
    fun `state lists every binding and what is held`() {
        val fixture = Fixture()
        fixture.toolset.press("t/fire")

        val json = assertOk(fixture.toolset.state()).json

        assertContains(json, "t/fire")
        assertContains(json, "t/move")
        assertContains(json, "\"held\":true")
    }

    /** A misspelt name is a typed refusal that names what does exist, not a silent nothing. */
    @Test
    fun `an unknown action is refused with the list of real ones`() {
        val fixture = Fixture()

        val failed = assertIs<AgentResult.Failed>(fixture.toolset.press("t/fier"))

        assertEquals(AgentInputErrors.NO_SUCH_BINDING, failed.error.kind)
        assertContains(failed.error.message, "t/fire")
    }

    /**
     * A host that wired no input source says so, rather than reporting a press it swallowed.
     *
     * The remedy is a line in a composition root, so the error has to be distinguishable from a
     * bad argument - an agent told `no_such_action` here would spend its next calls guessing.
     */
    @Test
    fun `an unwired toolset refuses every call with a wiring error`() {
        val toolset = InputToolset(injected = null)

        listOf(
            toolset.press("t/fire"),
            toolset.release("t/fire"),
            toolset.tap("t/fire"),
            toolset.setAxis("t/move", 1f, 0f),
            toolset.releaseAll(),
            toolset.state(),
        ).forEach { result ->
            val failed = assertIs<AgentResult.Failed>(result)
            assertEquals(AgentInputErrors.NO_INPUT_SOURCE, failed.error.kind)
        }
    }

    /** The declarations reach the toolset by the same route every other tool does. */
    @Test
    fun `the tool declarations dispatch onto the toolset`() {
        val fixture = Fixture()

        InputTapTool.invoke(fixture.toolset, AgentCommand("input.tap", mapOf("action" to "t/fire")))
        InputSetAxisTool.invoke(
            fixture.toolset,
            AgentCommand("input.set_axis", mapOf("axis" to "t/move", "x" to "-1", "y" to "0")),
        )

        val intent = fixture.sample()
        assertTrue(intent.isJustPressed(fixture.fire))
        assertEquals(-1f, intent.axisX(fixture.move))
    }

    /**
     * Every input tool is published by its own module, and by no other.
     *
     * The second half matters: `AgentHostTools` must stay free of them, or every host that wants
     * a screenshot is forced to wire an `InputToolset` it has no game for - see [AgentInputTools].
     */
    @Test
    fun `the input module publishes the input tools and the host module does not`() {
        val names = AgentInputTools.tools.map { it.name }
        assertTrue(
            AgentHostTools.tools.none { it.name.startsWith("input.") },
            "an input tool leaked into AgentHostTools, which every render host registers",
        )

        listOf(
            "input.press",
            "input.release",
            "input.release_all",
            "input.set_axis",
            "input.state",
            "input.tap",
        ).forEach { assertContains(names, it) }
    }

    private fun assertOk(result: AgentResult): AgentResult.Ok = assertIs(result)

    /** A toolset over a live injected source, plus the intent a tick would sample it into. */
    private class Fixture {

        val bindings: InputBindings = InputBindings(
            actions = listOf(ActionBinding("t/fire", keys = intArrayOf(62))),
            axes = listOf(Axis2DBinding("t/move")),
        )

        val injected: InjectedIntent = InjectedIntent(bindings.catalog)

        val toolset: InputToolset = InputToolset(injected)

        val fire = bindings.catalog.action("t/fire")

        val move = bindings.catalog.axis("t/move")

        private val intent = Intent(bindings.catalog)

        /** One tick's worth of sampling, exactly as `IntentSampleSystem` does it. */
        fun sample(): Intent {
            intent.clear()
            injected.sample(intent)
            return intent
        }
    }
}
