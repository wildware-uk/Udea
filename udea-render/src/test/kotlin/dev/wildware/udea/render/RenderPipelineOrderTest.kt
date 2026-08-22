package dev.wildware.udea.render

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.support.FrameLog
import dev.wildware.udea.render.support.RecordingRenderSystem
import dev.wildware.udea.render.support.overlayScene
import dev.wildware.udea.render.support.scene
import dev.wildware.udea.render.support.testTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The order a frame comes out in: phase ordinal first, then the `before`/`after` constraints
 * within a phase, then registration index -- and an unsatisfiable set of constraints failing
 * the build rather than drawing something plausible in an arbitrary order.
 *
 * The old tree had no ordering model at all. Systems ran in the order somebody happened to
 * add them to a list in `UdeaGameManager`, so "health bars draw over sprites" was a property
 * of a line number, and two features touching that list was a merge conflict that compiled.
 */
class RenderPipelineOrderTest {

    @Test
    fun `phase ordinal orders systems regardless of registration order`() {
        val log = FrameLog()
        val registry = RenderRegistry()

        // Registered in exactly the wrong order, on purpose.
        registry.scene(RenderPhase.Debug, "debug", log)
        registry.overlayScene("overlay", log)
        registry.scene(RenderPhase.UI, "ui", log)
        registry.scene(RenderPhase.World, "world", log)
        registry.scene(RenderPhase.PreRender, "pre", log)

        val pipeline = registry.build(world(), ctx, testTargets())
        log.clear()
        pipeline.render(0f)

        assertEquals(
            listOf(
                "draw:pre@0.0",
                "draw:world@0.0",
                "draw:ui@0.0",
                "draw:debug@0.0",
                "overlay:overlay@0.0",
            ),
            log.calls,
        )
    }

    @Test
    fun `the overlay phase runs after every capturable phase`() {
        // Spec 3.7: the capture point sits between the two, so an overlay must never draw
        // before something a capture can read.
        val log = FrameLog()
        val registry = RenderRegistry()
        registry.overlayScene("agent", log)
        registry.scene(RenderPhase.Debug, "debug", log)

        val pipeline = registry.build(world(), ctx, testTargets())
        log.clear()
        pipeline.render(0.5f)

        assertEquals(listOf("draw:debug@0.5", "overlay:agent@0.0"), log.calls)
    }

    @Test
    fun `within one phase registration index is the tie-break`() {
        val log = FrameLog()
        val registry = RenderRegistry()
        repeat(4) { index -> registry.scene(RenderPhase.World, "s$index", log) }

        val pipeline = registry.build(world(), ctx, testTargets())
        log.clear()
        pipeline.render(0f)

        assertEquals(listOf("draw:s0@0.0", "draw:s1@0.0", "draw:s2@0.0", "draw:s3@0.0"), log.calls)
    }

    @Test
    fun `a before constraint moves a system ahead of one registered earlier`() {
        val log = FrameLog()
        val registry = RenderRegistry()
        val first = registry.scene(RenderPhase.World, "first", log)
        registry.scene(RenderPhase.World, "second", log) { before(first) }

        val pipeline = registry.build(world(), ctx, testTargets())
        log.clear()
        pipeline.render(0f)

        assertEquals(listOf("draw:second@0.0", "draw:first@0.0"), log.calls)
    }

    @Test
    fun `a system runs as soon as its own constraints allow, not as late as its neighbours`() {
        // The documented semantics, pinned because it is the surprising half: `third` delays
        // `first`, and `second` -- which nobody constrained -- therefore overtakes it. The
        // rule is "earliest registration among the systems whose constraints are satisfied",
        // and the alternative ("disturb registration order as little as possible") would make
        // frame order depend on which of two equally valid orders the algorithm happened to
        // pick.
        val log = FrameLog()
        val registry = RenderRegistry()
        val first = registry.scene(RenderPhase.World, "first", log)
        registry.scene(RenderPhase.World, "second", log)
        registry.scene(RenderPhase.World, "third", log) { before(first) }

        val pipeline = registry.build(world(), ctx, testTargets())
        log.clear()
        pipeline.render(0f)

        assertEquals(listOf("draw:second@0.0", "draw:third@0.0", "draw:first@0.0"), log.calls)
    }

    @Test
    fun `an after constraint moves a system behind one registered later`() {
        val log = FrameLog()
        val registry = RenderRegistry()
        val anchor = registry.scene(RenderPhase.World, "anchor", log)
        registry.scene(RenderPhase.World, "late", log) { after(anchor) }
        registry.scene(RenderPhase.World, "early", log) { before(anchor) }

        val pipeline = registry.build(world(), ctx, testTargets())
        log.clear()
        pipeline.render(0f)

        assertEquals(listOf("draw:early@0.0", "draw:anchor@0.0", "draw:late@0.0"), log.calls)
    }

    @Test
    fun `overlays are ordered among themselves by the same rules`() {
        val log = FrameLog()
        val registry = RenderRegistry()
        val panel = registry.overlayScene("panel", log)
        registry.overlayScene("markers", log) { before(panel) }

        val pipeline = registry.build(world(), ctx, testTargets())
        log.clear()
        pipeline.render(0f)

        assertEquals(listOf("overlay:markers@0.0", "overlay:panel@0.0"), log.calls)
    }

    @Test
    fun `a constraint cycle fails the build and prints the cycle`() {
        val log = FrameLog()
        val registry = RenderRegistry()
        val first = registry.scene(RenderPhase.World, "first", log)
        val second = registry.scene(RenderPhase.World, "second", log) { after(first) }
        registry.scene(RenderPhase.World, "third", log) {
            after(second)
            before(first)
        }

        val failure = assertFailsWith<RenderOrderException> {
            registry.build(world(), ctx, testTargets())
        }

        val message = failure.message.orEmpty()
        assertTrue("cycle" in message, message)
        // Registration indices, in the order the cycle closes: 0 -> 1 -> 2 -> 0.
        assertEquals(
            listOf(0, 1, 2, 0),
            message.substringAfter("cycle: ").split(" -> ").map { it.substringAfterLast('#').toInt() },
            message,
        )
        // ...and the class is named too, or the indices mean nothing to a reader.
        assertTrue(RecordingRenderSystem::class.qualifiedName!! in message, message)
    }

    @Test
    fun `a constraint across two phases is rejected at the registration site`() {
        val log = FrameLog()
        val registry = RenderRegistry()
        val worldSystem = registry.scene(RenderPhase.World, "w", log)

        val failure = assertFailsWith<IllegalArgumentException> {
            registry.scene(RenderPhase.Debug, "d", log) { before(worldSystem) }
        }

        assertTrue("different phases" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a handle from another registry is rejected`() {
        val log = FrameLog()
        val other = RenderRegistry()
        val foreign = other.scene(RenderPhase.World, "foreign", log)
        val registry = RenderRegistry()

        val failure = assertFailsWith<IllegalArgumentException> {
            registry.scene(RenderPhase.World, "mine", log) { after(foreign) }
        }

        assertTrue("different RenderRegistry" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `RenderPhase Overlay rejects a RenderSystem because it would be handed a capturable target`() {
        val log = FrameLog()
        val registry = RenderRegistry()

        val failure = assertFailsWith<IllegalArgumentException> {
            registry.scene(RenderPhase.Overlay, "sneaky", log)
        }

        assertTrue("OverlaySystem" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an empty registry still produces a working pipeline`() {
        // A game with no renderers registered yet must draw a frame, not throw.
        val pipeline = RenderRegistry().build(world(), ctx, testTargets())

        pipeline.render(0.25f)

        assertEquals(1L, pipeline.frameCount)
    }

    private val ctx: GameContext = testGameContext(seed = 7L)

    private fun world(): World = configureWorld { injectables { gameContext(ctx) } }
}
