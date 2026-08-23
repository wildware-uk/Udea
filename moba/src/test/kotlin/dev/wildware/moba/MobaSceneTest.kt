package dev.wildware.moba

import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the scene promises, checked without a GL context.
 *
 * The GL half - that a champion actually reaches a framebuffer - is proven by driving
 * `:moba:run` over HTTP and reading the PNG back. These are the parts that can fail at build
 * time instead, and each one is a failure a reviewer has seen shipped: an index computed with
 * `%`, and an overlay registered into a mode that gets captured.
 */
class MobaSceneTest {

    // --- the animation playhead --------------------------------------------------------------

    /** One frame every `TICKS_PER_FRAME` ticks, wrapping at the end of the strip. */
    @Test
    fun `the frame index advances with the tick and wraps`() {
        val frames = 6
        val step = ChampionRenderSystem.TICKS_PER_FRAME
        assertEquals(0, ChampionRenderSystem.frameIndex(Tick(0), frames))
        assertEquals(0, ChampionRenderSystem.frameIndex(Tick(step - 1), frames))
        assertEquals(1, ChampionRenderSystem.frameIndex(Tick(step), frames))
        assertEquals(5, ChampionRenderSystem.frameIndex(Tick(step * 5), frames))
        // ...and back to the start rather than off the end of the array.
        assertEquals(0, ChampionRenderSystem.frameIndex(Tick(step * 6), frames))
    }

    /**
     * A negative tick still names a frame in the strip.
     *
     * `time.rewind` can put the clock before tick zero, and `%` would hand back a negative index
     * - an `ArrayIndexOutOfBoundsException` on the render thread, reachable from a tool an agent
     * calls. Replace `Math.floorMod` with `%` in `ChampionRenderSystem.frameIndex` and this test
     * fails; nothing else in the suite notices.
     */
    @Test
    fun `a negative tick still names a frame`() {
        val frames = 6
        val step = ChampionRenderSystem.TICKS_PER_FRAME
        for (tick in -60L..0L) {
            val index = ChampionRenderSystem.frameIndex(Tick(tick), frames)
            assertTrue(index in 0 until frames, "tick $tick gave index $index")
        }
        assertEquals(5, ChampionRenderSystem.frameIndex(Tick(-step), frames))
    }

    /** A strip with no frames is a wiring fault, and says so rather than throwing an index error. */
    @Test
    fun `an empty strip is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ChampionRenderSystem.frameIndex(Tick(0), 0)
        }
    }

    /**
     * The playhead is a pure function of the tick, which is the property an agent depends on.
     *
     * Two captures of the same paused world must be byte-identical, or `render.compare_artifacts`
     * reports the animation instead of the mutation. Wall time does not appear in the signature,
     * and this asserts it does not appear in the answer either.
     */
    @Test
    fun `the same tick always names the same frame`() {
        val first = ChampionRenderSystem.frameIndex(Tick(1234), 6)
        Thread.sleep(5)
        assertEquals(first, ChampionRenderSystem.frameIndex(Tick(1234), 6))
    }

    // --- the playfield, and the blank screenshots it used to cause -------------------------

    /**
     * A drifting unit stays inside the field, which is what stops every capture going black.
     *
     * Unbounded, `x` leaves `MobaScene`'s camera at about tick 460 and every `render.screenshot`
     * from then on is a valid PNG of an empty framebuffer - which is indistinguishable from a
     * broken renderer without booting an instance. Delete the `wrap` call in `DriftSystem.onTick`
     * and this fails.
     */
    @Test
    fun `a lap of the field leaves the unit where it started`() {
        var x = 0f
        repeat(DriftSystem.LAP_TICKS) { x = DriftSystem.wrap(x + DriftSystem.DRIFT_PER_TICK) }
        assertEquals(0f, x, absoluteTolerance = 1e-3f)
    }

    /** Every tick of a long run leaves `x` somewhere the camera can see. */
    @Test
    fun `x never leaves the field`() {
        var x = 0f
        repeat(DriftSystem.LAP_TICKS * 3) {
            x = DriftSystem.wrap(x + DriftSystem.DRIFT_PER_TICK)
            assertTrue(x >= 0f && x < DriftSystem.FIELD_WIDTH, "x drifted to $x")
        }
    }

    /**
     * A negative `x` comes back onto the field, not to a negative remainder.
     *
     * `x` is agent-writable: `world.set_component_field` with `x = -5` is one HTTP call away, and
     * Kotlin's `%` keeps the sign. Replace `wrap` with a bare `%` and this fails.
     */
    @Test
    fun `a negative x wraps onto the field`() {
        assertEquals(85f, DriftSystem.wrap(-5f), absoluteTolerance = 1e-3f)
        assertEquals(85f, DriftSystem.wrap(-95f), absoluteTolerance = 1e-3f)
        assertEquals(0f, DriftSystem.wrap(-DriftSystem.FIELD_WIDTH), absoluteTolerance = 1e-3f)
    }

    /** A value an agent can write that has no remainder at all lands on the field anyway. */
    @Test
    fun `a non-finite x is brought back rather than propagated`() {
        assertEquals(0f, DriftSystem.wrap(Float.NaN))
        assertEquals(0f, DriftSystem.wrap(Float.POSITIVE_INFINITY))
    }

    /** The field fits inside what the camera frames, or the wrap fixes nothing. */
    @Test
    fun `the field sits inside the camera`() {
        val halfWidth = MobaScene.WORLD_WIDTH / 2f
        val left = MobaScene.CAMERA_X - halfWidth
        val right = MobaScene.CAMERA_X + halfWidth
        assertTrue(left < 0f, "the left edge of the field is off camera: $left")
        assertTrue(right > DriftSystem.FIELD_WIDTH, "the right edge is off camera: $right")
    }

    // --- the overlay, and the mode it is allowed in ------------------------------------------

    /**
     * Spec 3.7: the overlay exists only in `RenderMode.Windowed`.
     *
     * `runWithGl` refuses rather than skipping, and the refusal happens before a backend is
     * started - which is what makes this assertable with no display. Delete the `require` and
     * an `Offscreen` instance would happily draw the agent's own narration onto a surface
     * every capture reads.
     */
    @Test
    fun `an overlay is refused outside Windowed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MobaEntry.runWithGl(RenderMode.Offscreen, overlay = { error("never constructed") }) {
                    _, _ ->
                error("never attached")
            }
        }
        assertTrue("Windowed" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /** And `Headless` is refused first, because it has no backend at all. */
    @Test
    fun `Headless has no GL backend to run with`() {
        assertFailsWith<IllegalArgumentException> {
            MobaEntry.runWithGl(RenderMode.Headless) { _, _ -> error("never attached") }
        }
    }
}
