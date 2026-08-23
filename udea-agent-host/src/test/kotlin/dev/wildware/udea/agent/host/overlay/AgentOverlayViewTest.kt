package dev.wildware.udea.agent.host.overlay

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.activity.AgentOutcome
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.activity.AnchorRule
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The panel, the markers and the hotkey (issues #159, #160, #161), asserted over a recording
 * canvas rather than pixels.
 */
class AgentOverlayViewTest {

    private val bridge = AgentBridge()

    private val sessions = AgentSessions()

    private val keys = FakeKeys()

    private val canvas = RecordingCanvas()

    private val projector = IdentityProjector()

    private val locator = MapLocator()

    private fun view(
        mode: RenderMode = RenderMode.Windowed,
        verbosity: OverlayVerbosity = OverlayVerbosity.NORMAL,
    ): AgentOverlayView = AgentOverlayView(bridge, sessions, mode, keys, initialVerbosity = verbosity)

    private fun call(
        name: String,
        args: Map<String, String> = emptyMap(),
        session: AgentSessionId = AgentSessionId.LOCAL,
        anchor: AnchorRule = AnchorRule.NONE,
        outcome: AgentOutcome = AgentOutcome.OK,
    ) {
        val command = AgentCommand(name, args, session = session)
        val slot = bridge.activity.begin(command, tick = 12L, session = session, anchor = anchor)
        bridge.activity.complete(slot, command.id, outcome, durationNanos = 400_000L)
    }

    // --- #159 the panel --------------------------------------------------------------------

    @Test
    fun `the panel shows the caption, the session and the recent calls newest first`() {
        val agent = sessions.intern("claude-a")
        bridge.narration.say("checking the mid wave", ttlSeconds = 60f, agent)
        call("world.query_entities")
        call("world.get_component", mapOf("id" to "266"))

        view().render(canvas, dtSeconds = 0.016f)

        assertEquals(
            listOf(
                "claude-a: checking the mid wave",
                "+ world.get_component id=266",
                "+ world.query_entities",
            ),
            canvas.texts,
        )
        assertEquals(1, canvas.fills.size, "the panel drew more than one background rectangle")
    }

    @Test
    fun `an idle agent still gets a header, so a broken overlay is distinguishable from a quiet one`() {
        view().render(canvas, dtSeconds = 0.016f)

        assertEquals(listOf("agent: idle"), canvas.texts)
    }

    @Test
    fun `a failed call and a running call are marked differently from a success`() {
        call("world.set_component_field", outcome = AgentOutcome.FAILED)
        bridge.activity.begin(AgentCommand("time.rewind"), 1L, AgentSessionId.LOCAL, AnchorRule.NONE)

        view().render(canvas, dtSeconds = 0.016f)

        assertTrue(canvas.texts.any { it.startsWith("> time.rewind") }, canvas.texts.toString())
        assertTrue(canvas.texts.any { it.startsWith("! world.set_component_field") }, canvas.texts.toString())
    }

    @Test
    fun `two sessions get two colours`() {
        val a = sessions.intern("claude-a")
        val b = sessions.intern("claude-b")
        call("world.query_entities", session = a)
        call("diag.memory", session = b)

        view().render(canvas, dtSeconds = 0.016f)

        val rows = canvas.draws.filterIsInstance<RecordingCanvas.Draw.Text>()
        val coloured = rows.filter { it.text.startsWith("+") }.map { it.rgba }.distinct()
        assertEquals(2, coloured.size, "both sessions were drawn in the same colour: $coloured")
    }

    @Test
    fun `timings and command ids appear only at verbose`() {
        call("world.get_component", mapOf("id" to "266"))

        view(verbosity = OverlayVerbosity.NORMAL).render(canvas, 0.016f)
        assertTrue(canvas.texts.none { it.contains("ms") }, canvas.texts.toString())

        canvas.clear()
        view(verbosity = OverlayVerbosity.VERBOSE).render(canvas, 0.016f)
        assertTrue(canvas.texts.any { it.contains("0.4ms") }, canvas.texts.toString())
    }

    @Test
    fun `a frame on which nothing changed re-formats nothing`() {
        // The whole reason the model exists. Formatting six rows sixty times a second is three
        // hundred and sixty short-lived strings a second for a panel that changes when the agent
        // calls a tool.
        call("world.query_entities")
        val view = view()

        view.render(canvas, 0.016f)
        val afterFirst = view.model.refreshes
        repeat(60) { view.render(canvas, 0.016f) }

        assertEquals(
            afterFirst,
            view.model.refreshes,
            "the panel re-formatted ${view.model.refreshes - afterFirst} times over 60 " +
                "unchanged frames",
        )
    }

    @Test
    fun `a new call does re-format`() {
        val view = view()
        view.render(canvas, 0.016f)
        val before = view.model.refreshes

        call("world.spawn_blueprint")
        view.render(canvas, 0.016f)

        assertEquals(before + 1, view.model.refreshes)
    }

    // --- #160 world-space markers ----------------------------------------------------------

    @Test
    fun `an entity anchor draws a ring that tracks the entity as it moves`() {
        call("world.get_component", mapOf("id" to "266"), anchor = entityRule())
        locator.put(266, 100f, 50f)
        val view = view()

        view.render(canvas, 0.016f, projector, locator)
        assertEquals(1, canvas.rings.size)
        assertEquals(100f, canvas.rings[0].cx)

        // The entity walks. A marker that had cached a position would still be at x=100.
        locator.put(266, 140f, 55f)
        canvas.clear()
        view.render(canvas, 0.016f, projector, locator)

        assertEquals(140f, canvas.rings.single().cx, "the ring did not follow the entity")
        assertEquals(55f, canvas.rings.single().cy)
    }

    @Test
    fun `a stale generation draws nothing rather than ringing whatever recycled the slot`() {
        // The defect this exists for: NetId indices are dense and recycled. Ringing "whatever is
        // in slot 266 now" would tell a human the agent had inspected an unrelated entity.
        call("world.get_component", mapOf("id" to "266"), anchor = entityRule())
        val view = view()

        // Never registered - which is what `NetIdIndex.resolveOrNull` answers for a stale id.
        view.render(canvas, 0.016f, projector, locator)

        assertTrue(
            canvas.rings.isEmpty(),
            "a marker was drawn for an entity that no longer exists: ${canvas.rings.size} ring(s)",
        )
    }

    @Test
    fun `a point anchor draws a cross, not a ring`() {
        call("world.spawn_blueprint", mapOf("x" to "20", "y" to "-8"), anchor = pointRule())

        view().render(canvas, 0.016f, projector, locator)

        assertEquals(1, canvas.crosses.size)
        assertTrue(canvas.rings.isEmpty(), "a positional call drew an entity ring")
        assertEquals(20f, canvas.crosses[0].x)
        assertEquals(-8f, canvas.crosses[0].y)
    }

    @Test
    fun `a write marker is drawn differently from a read marker`() {
        // Two channels, both asserted: a write is thicker and it is more opaque. One channel
        // alone would be lost to a colourblind reader or to a dim screen.
        call("world.set_component_field", mapOf("id" to "1"), anchor = entityRule())
        call("world.get_component", mapOf("id" to "2"), anchor = entityRule())
        locator.put(1, 0f, 0f)
        locator.put(2, 5f, 5f)

        view().render(canvas, 0.016f, projector, locator)

        val write = canvas.rings.single { it.cx == 0f }
        val read = canvas.rings.single { it.cx == 5f }
        assertTrue(
            write.thickness > read.thickness,
            "a write and a read are drawn with the same stroke",
        )
        assertTrue(
            (write.rgba and 0xFF) > (read.rgba and 0xFF),
            "a write and a read are drawn at the same opacity",
        )
    }

    @Test
    fun `a marker off screen draws nothing rather than clamping to the window edge`() {
        call("world.spawn_blueprint", mapOf("x" to "99999", "y" to "0"), anchor = pointRule())

        view().render(canvas, 0.016f, projector, locator)

        assertTrue(canvas.crosses.isEmpty())
    }

    @Test
    fun `a marker fades out on wall time and then stops being drawn`() {
        call("world.spawn_blueprint", mapOf("x" to "1", "y" to "1"), anchor = pointRule())
        val view = view()

        view.render(canvas, 0.016f, projector, locator)
        val fresh = canvas.crosses.single().rgba and 0xFF

        canvas.clear()
        view.render(canvas, AgentMarkers.DEFAULT_TTL_SECONDS / 2f, projector, locator)
        val faded = canvas.crosses.single().rgba and 0xFF
        assertTrue(faded < fresh, "the marker did not fade: $fresh then $faded")

        canvas.clear()
        view.render(canvas, AgentMarkers.DEFAULT_TTL_SECONDS, projector, locator)
        assertTrue(canvas.crosses.isEmpty(), "the marker outlived its ttl")
    }

    @Test
    fun `an unrelated call does not restart another marker's fade`() {
        // Ages are carried across a re-collect by command id. Matching by slot instead would
        // reset every marker's fade whenever the agent called anything at all, and a session
        // that called a tool a second would leave every marker on screen for ever.
        call("world.spawn_blueprint", mapOf("x" to "1", "y" to "1"), anchor = pointRule())
        val view = view()
        view.render(canvas, 0.016f, projector, locator)
        view.render(canvas, AgentMarkers.DEFAULT_TTL_SECONDS / 2f, projector, locator)
        canvas.clear()
        val halfFaded = run {
            view.render(canvas, 0f, projector, locator)
            canvas.crosses.single().rgba and 0xFF
        }

        call("diag.memory")
        canvas.clear()
        view.render(canvas, 0f, projector, locator)

        assertEquals(
            halfFaded,
            canvas.crosses.single().rgba and 0xFF,
            "an unrelated call restarted the marker's fade",
        )
    }

    // --- #161 the hotkey -------------------------------------------------------------------

    @Test
    fun `the hotkey cycles the level once per press, not once per frame`() {
        val view = view(verbosity = OverlayVerbosity.OFF)

        // Held down across ten frames: one press, not ten.
        keys.down = true
        repeat(10) { view.render(canvas, 0.016f) }

        assertEquals(1L, view.verbosityControl.presses)
        assertEquals(OverlayVerbosity.CAPTION, view.verbosityControl.verbosity)
    }

    @Test
    fun `the level wraps back to off`() {
        val control = OverlayVerbosityControl(keys, OverlayVerbosity.OFF)

        repeat(OverlayVerbosity.entries.size) { keys.press(control) }

        assertEquals(OverlayVerbosity.OFF, control.verbosity)
    }

    @Test
    fun `nothing an agent can reach moves the level`() {
        // Issue #161's whole point. The agent's channel into this process is the command queue;
        // the overlay key is not on it. Driving the agent surface as hard as a test can and
        // finding the level unmoved is what says the two are separate - and a key read from the
        // *injected* intent source instead would fail here.
        val view = view(verbosity = OverlayVerbosity.NORMAL)
        view.render(canvas, 0.016f)

        repeat(50) { index ->
            bridge.submit(AgentCommand("world.query_entities", mapOf("limit" to "$index")))
            call("world.query_entities")
            bridge.narration.say("frame $index", ttlSeconds = 5f, AgentSessionId.LOCAL)
            view.render(canvas, 0.016f)
        }

        assertEquals(0L, view.verbosityControl.presses)
        assertEquals(OverlayVerbosity.NORMAL, view.verbosityControl.verbosity)
    }

    // --- mode ------------------------------------------------------------------------------

    @Test
    fun `the overlay exists only in Windowed`() {
        for (mode in listOf(RenderMode.Headless, RenderMode.Offscreen)) {
            canvas.clear()
            call("world.query_entities")
            val view = view(mode = mode)

            view.render(canvas, 0.016f, projector, locator)

            assertFalse(view.isEnabled, "$mode drew the overlay")
            assertTrue(canvas.draws.isEmpty(), "$mode drew ${canvas.draws.size} thing(s)")
        }
    }

    @Test
    fun `OFF draws nothing but still ages the markers`() {
        // Otherwise turning the overlay back on would reveal a set of markers frozen at the age
        // they had when it was turned off, which reads as a burst of activity that never
        // happened.
        call("world.spawn_blueprint", mapOf("x" to "1", "y" to "1"), anchor = pointRule())
        val view = view(verbosity = OverlayVerbosity.OFF)

        view.render(canvas, AgentMarkers.DEFAULT_TTL_SECONDS * 2f, projector, locator)
        assertTrue(canvas.draws.isEmpty())

        view.verbosityControl.set(OverlayVerbosity.NORMAL)
        view.render(canvas, 0f, projector, locator)

        assertTrue(canvas.crosses.isEmpty(), "a stale marker reappeared when the overlay came back on")
    }

    @Test
    fun `CAPTION shows the caption but no calls and no markers`() {
        bridge.narration.say("mid dive", ttlSeconds = 60f, AgentSessionId.LOCAL)
        call("world.spawn_blueprint", mapOf("x" to "1", "y" to "1"), anchor = pointRule())

        view(verbosity = OverlayVerbosity.CAPTION).render(canvas, 0.016f, projector, locator)

        assertEquals(listOf("local: mid dive"), canvas.texts)
        assertTrue(canvas.crosses.isEmpty())
    }

    /** What `world.get_component` and friends declare: an integer identity argument. */
    private fun entityRule(): AnchorRule = AnchorRule.of(listOf(declared("id", "integer")))

    /** What `world.spawn_blueprint` declares: two numbers named x and y. */
    private fun pointRule(): AnchorRule =
        AnchorRule.of(listOf(declared("x", "number"), declared("y", "number")))

    private fun declared(name: String, type: String): AgentToolArg =
        AgentToolArg(name, type, "the $name", required = true, default = null)
}
