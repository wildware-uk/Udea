package dev.wildware.udea.core.host

import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.loop.Presentation
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGame
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.scene.Marker
import dev.wildware.udea.core.scene.MarkerScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every [RenderMode] runs the identical simulation; the only difference is whether a
 * [Presentation] exists.
 *
 * That is the settlement spec 3.5 records, and it only means anything if it is checked: the
 * failure it prevents is a behaviour that reproduces for the player and not on CI, which is
 * unfixable by inspection.
 *
 * The LWJGL3 contexts for `Offscreen` and `Windowed` live in `udea-render` — `udea-core` has no
 * GL on its compile classpath. What is tested here is what this module owns: the mode, the
 * decision to construct a presentation at all, and the typed error when there is no context.
 */
class RenderModeMatrixTest {

    /** Counts every presentation it is asked to build, and every frame it draws. */
    private class CountingPresentation : Presentation, PresentationFactory {
        var built: Int = 0
            private set

        var framesDrawn: Int = 0
            private set

        override fun create(game: UdeaGame): PresentationBackend {
            built++
            return PresentationBackend(this, FrameCapture { CaptureOutcome.Captured(ByteArray(4)) })
        }

        override fun render(alpha: Float) {
            framesDrawn++
        }
    }

    private fun host(mode: RenderMode, factory: PresentationFactory?): GameHost {
        val scene = MarkerScene(SceneId("arena"), seed = 909L, entityCount = 25)
        val def = UdeaGameDef(emptyList())
        def.core.scenes.register(scene)
        val host = GameHost(mode, def, factory)
        host.ctx.scenes.requestScene(scene.id)
        return host
    }

    /** Every entity's whole marker state, in NetId order: the field-by-field comparison. */
    private fun worldState(host: GameHost): List<String> {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val state = ArrayList<String>()
        netIds.forEachLive { netId, entity ->
            val marker = with(host.world) { entity[Marker] }
            state += "$netId ${marker.value} ${marker.band}"
        }
        return state
    }

    @Test
    fun `600 ticks of the same scene produce identical world state in Headless and with a presentation`() {
        val headless = host(RenderMode.Headless, null)
        val presented = host(RenderMode.Offscreen, CountingPresentation())

        headless.run(600)
        presented.run(600)

        assertTrue(worldState(headless).size == 25, "the scene populated")
        assertEquals(
            worldState(headless),
            worldState(presented),
            "the presence of a presentation changed what the simulation computed",
        )
        assertEquals(headless.tick, presented.tick)
    }

    @Test
    fun `Headless constructs no presentation, even when one is offered`() {
        val factory = CountingPresentation()
        val host = host(RenderMode.Headless, factory)

        host.run(10)
        host.frame(0.016f)

        assertEquals(0, factory.built, "Headless must not construct a presentation or a RenderSystem")
        assertEquals(0, factory.framesDrawn, "and must not draw")
        assertNull(host.presentation, "Presentation is null in Headless")
    }

    @Test
    fun `a mode with a render context constructs exactly one presentation and draws through it`() {
        val factory = CountingPresentation()
        val host = host(RenderMode.Offscreen, factory)

        host.frame(0.016f)
        host.frame(0.016f)

        assertEquals(1, factory.built, "the presentation is built once, at host construction")
        assertEquals(2, factory.framesDrawn)
        assertNotNull(host.presentation)
    }

    @Test
    fun `run(n) then frame() keeps simulating - a fast-forward must not freeze the loop`() {
        // The sequence a Windowed or Offscreen host actually performs: fast-forward a loading
        // sequence, then hand the frame cadence back to the render backend. `GameLoop.stepTicks`
        // pauses — it is TimeControl.step's primitive, where a stopped clock is the point and
        // resume() is right there — so a host that left the pause set would render at full rate
        // over a simulation frozen forever, with nothing thrown and no counter moving.
        val factory = CountingPresentation()
        val host = host(RenderMode.Offscreen, factory)

        host.run(300)
        assertEquals(300L, host.totalTicks, "the fast-forward ran")

        repeat(10) { host.frame(1f / 60f) }

        assertTrue(
            host.totalTicks > 300L,
            "the loop never stepped again after run(300): totalTicks is still ${host.totalTicks}",
        )
        assertEquals(host.totalTicks, host.tick.value, "and the clock moved with it")
        assertEquals(10, factory.framesDrawn, "while the presentation drew every frame regardless")
        assertEquals(25, worldState(host).size, "over a world that is still there")
    }

    @Test
    fun `run(n) restores the pause it found rather than clearing it`() {
        // The other half: a host paused on purpose — an agent inspecting a frozen world, which
        // is what TimeControl leaves behind — must still be paused after a step(n), or the
        // agent's next frame silently resumes the simulation underneath it.
        val host = host(RenderMode.Offscreen, CountingPresentation())
        host.loop.paused = true

        host.run(5)

        assertTrue(host.loop.paused, "run(n) must not clear a pause somebody else set")
        assertEquals(5L, host.totalTicks, "it still advanced exactly five ticks")

        host.frame(1f / 60f)

        assertEquals(5L, host.totalTicks, "and a frame over a paused loop steps nothing")
    }

    @Test
    fun `a screenshot in Headless returns the typed no_render_context error`() {
        val host = host(RenderMode.Headless, CountingPresentation())

        val outcome = host.screenshot()

        assertTrue(outcome is CaptureOutcome.Unavailable, "got $outcome; it must not throw")
        assertEquals(RenderUnavailable.NoRenderContext, outcome.reason)
        assertEquals(
            "no_render_context",
            outcome.reason.code,
            "the code crosses the agent boundary verbatim; renaming it is a protocol change",
        )
    }

    @Test
    fun `a screenshot in a mode with a context is captured`() {
        val host = host(RenderMode.Offscreen, CountingPresentation())

        val outcome = host.screenshot()

        assertTrue(outcome is CaptureOutcome.Captured, "got $outcome")
        assertEquals(4, outcome.image.size)
    }

    @Test
    fun `a mode with a context but no capture backend is reported distinctly`() {
        val host = host(RenderMode.Offscreen, null)

        val outcome = host.screenshot()

        assertTrue(outcome is CaptureOutcome.Unavailable, "got $outcome")
        assertEquals(
            RenderUnavailable.NoCaptureBackend,
            outcome.reason,
            "a wiring mistake and a headless run need different remedies, so they get " +
                "different codes",
        )
    }

    @Test
    fun `mode is readable and correct for all three modes`() {
        for (mode in RenderMode.entries) {
            val host = host(mode, CountingPresentation())
            assertEquals(mode, host.mode, "GameHost.mode is what /health reports")
            assertEquals(mode != RenderMode.Headless, mode.hasRenderContext)
            assertEquals(
                mode != RenderMode.Headless,
                host.presentation != null,
                "a presentation exists exactly when the mode has a render context",
            )
        }
    }

    @Test
    fun `run() refuses to pace a mode that a render backend drives`() {
        val host = host(RenderMode.Windowed, CountingPresentation())

        val failure = runCatching { host.run() }.exceptionOrNull()

        assertTrue(
            failure is IllegalStateException,
            "two drivers for one loop is a frame-pacing bug nobody can see; got $failure",
        )
        assertTrue("Headless" in failure.message.orEmpty(), "${failure.message}")
    }
}
