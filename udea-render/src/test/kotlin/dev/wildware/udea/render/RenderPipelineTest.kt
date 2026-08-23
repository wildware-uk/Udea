package dev.wildware.udea.render

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.Presentation
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.render.support.CountingDisposable
import dev.wildware.udea.render.support.FrameLog
import dev.wildware.udea.render.support.ManualFrameClock
import dev.wildware.udea.render.support.RecordingOverlaySystem
import dev.wildware.udea.render.support.RecordingRenderSystem
import dev.wildware.udea.render.support.testTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a frame is handed, and what it owns.
 *
 * The target split (spec 3.7) is the load-bearing claim here: a [RenderSystem] only ever
 * sees the capturable [OffscreenTarget] and an [OverlaySystem] only ever sees the
 * never-captured [ScreenTarget], so "the agent must not see its own overlay" is a fact about
 * the types rather than a step somebody has to remember before capturing.
 */
class RenderPipelineTest {

    @Test
    fun `every RenderSystem is given the offscreen target and the alpha untouched`() {
        val log = FrameLog()
        val system = RecordingRenderSystem("world", log)
        val targets = testTargets()
        val pipeline = pipelineOf(targets, systems = listOf(system))

        pipeline.render(0.75f)
        pipeline.render(0.25f)

        assertEquals(listOf(targets.offscreen, targets.offscreen), system.targets)
        assertEquals(
            listOf("draw:world@0.75", "draw:world@0.25"),
            log.calls.filter { it.startsWith("draw:") },
        )
    }

    @Test
    fun `every OverlaySystem is given the screen target and never the offscreen one`() {
        val log = FrameLog()
        val overlay = RecordingOverlaySystem("agent", log)
        val targets = testTargets()
        val pipeline = pipelineOf(targets, overlays = listOf(overlay))

        pipeline.render(0.5f)

        assertEquals(listOf(targets.screen), overlay.targets)
        assertSame(targets.screen, overlay.targets.single())
        // The interesting half of the guarantee -- that there is no *route* by which an
        // OverlaySystem could hold the offscreen target -- is not assertable from here, and
        // this comment used to claim it as if it were. `OverlayResourcesTest` asserts it: the
        // overlay factory takes `OverlayResources`, and nothing reachable from one is a
        // capturable target. Until that split existed, the claim was false: `overlay(...)` took
        // the same `RenderResources` a RenderSystem gets, `offscreen` and all.
    }

    @Test
    fun `an overlay is given wall seconds and never the simulation alpha`() {
        val log = FrameLog()
        val overlay = RecordingOverlaySystem("agent", log)
        val clock = ManualFrameClock()
        val pipeline = pipelineOf(overlays = listOf(overlay), clock = clock)

        pipeline.render(0.9f)
        clock.advanceSeconds(0.02f)
        pipeline.render(0.9f)
        clock.advanceSeconds(0.05f)
        pipeline.render(0.9f)

        // First frame has no predecessor, so it is zero rather than "since the JVM started".
        assertEquals(3, overlay.deltas.size)
        assertEquals(0f, overlay.deltas[0])
        assertEquals(0.02f, overlay.deltas[1], absoluteTolerance = 1e-6f)
        assertEquals(0.05f, overlay.deltas[2], absoluteTolerance = 1e-6f)
        // ...and none of them is the alpha it was rendered with.
        assertTrue(overlay.deltas.none { it == 0.9f }, "${overlay.deltas}")
    }

    @Test
    fun `a stalled frame is clamped rather than passed on`() {
        val overlay = RecordingOverlaySystem("agent", FrameLog())
        val clock = ManualFrameClock()
        val pipeline = pipelineOf(overlays = listOf(overlay), clock = clock)

        pipeline.render(0f)
        clock.advanceSeconds(40f) // a breakpoint, a GC pause, a closed lid
        pipeline.render(0f)

        assertEquals(RenderPipeline.MAX_FRAME_SECONDS, overlay.deltas[1])
    }

    @Test
    fun `onBind runs once at build time and never during a frame`() {
        val log = FrameLog()
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { RecordingRenderSystem("world", log) })
        val world = world()

        val pipeline = registry.build(world, ctx, testTargets())

        assertEquals(listOf("bind:world"), log.calls)
        repeat(5) { pipeline.render(0f) }
        assertEquals(1, log.calls.count { it.startsWith("bind:") })
    }

    @Test
    fun `onBind receives the world and the context the pipeline was built with`() {
        val system = RecordingRenderSystem("world", FrameLog())
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { system })
        val world = world()

        registry.build(world, ctx, testTargets())

        assertSame(world, system.boundWorld)
        assertSame(ctx, system.boundContext)
    }

    @Test
    fun `an overlay is never bound to the world at all`() {
        // There is no onBind on OverlaySystem: an overlay narrates the agent, and handing it
        // the world would hand it simulation state through the back door (spec 3.7).
        assertNull(
            OverlaySystem::class.java.methods.firstOrNull { it.name == "onBind" },
            "OverlaySystem must not expose a world-binding hook",
        )
    }

    @Test
    fun `dispose releases owned resources in reverse construction order`() {
        val log = FrameLog()
        val batch = CountingDisposable("batch", log)
        val shapes = CountingDisposable("shapes", log)
        val pipeline = pipelineOf(testTargets(owned = listOf(batch, shapes)))

        pipeline.dispose()

        assertEquals(listOf("dispose:shapes", "dispose:batch"), log.calls)
    }

    @Test
    fun `dispose is idempotent`() {
        val log = FrameLog()
        val batch = CountingDisposable("batch", log)
        val pipeline = pipelineOf(testTargets(owned = listOf(batch)))

        pipeline.dispose()
        pipeline.dispose()

        // Disposing a SpriteBatch twice is undefined in LibGDX; a shutdown path running twice
        // is far likelier than a real defect, so the second call is a no-op.
        assertEquals(1, batch.disposeCount)
        assertTrue(pipeline.isDisposed)
    }

    @Test
    fun `drawing after dispose fails loudly rather than with a GL crash later`() {
        val pipeline = pipelineOf(testTargets(owned = listOf(CountingDisposable("batch", FrameLog()))))
        pipeline.dispose()

        val failure = assertFailsWith<IllegalStateException> { pipeline.render(0f) }

        assertTrue("disposed" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an alpha outside its documented range is rejected`() {
        val pipeline = pipelineOf()

        // GameLoop guarantees [0, 1); a value outside it means the loop is broken, and a
        // renderer would silently extrapolate transforms past the next tick.
        assertFailsWith<IllegalArgumentException> { pipeline.render(1.5f) }
        assertFailsWith<IllegalArgumentException> { pipeline.render(-0.1f) }
        assertFailsWith<IllegalArgumentException> { pipeline.render(Float.NaN) }
    }

    @Test
    fun `the pipeline is what GameLoop drives, and it draws once per frame`() {
        val log = FrameLog()
        val system = RecordingRenderSystem("world", log)
        val pipeline = pipelineOf(systems = listOf(system))
        val presentation: Presentation = pipeline
        val sim = WorldSimulation(ctx, world())
        val loop = GameLoop(sim, presentation)

        repeat(10) { loop.frame(1f / 60f) }

        assertEquals(10L, pipeline.frameCount)
        assertEquals(10, log.calls.count { it.startsWith("draw:world") })
    }

    private fun pipelineOf(
        targets: RenderTargets = testTargets(),
        systems: List<RenderSystem> = emptyList(),
        overlays: List<OverlaySystem> = emptyList(),
        clock: FrameClock = ManualFrameClock(),
    ): RenderPipeline {
        val registry = RenderRegistry(clock)
        for (system in systems) registry.register(RenderPhase.World, { system })
        for (overlay in overlays) registry.overlay({ overlay })
        return registry.build(world(), ctx, targets)
    }

    private val ctx: GameContext = testGameContext(seed = 11L)

    private fun world(): World = configureWorld { injectables { gameContext(ctx) } }
}
