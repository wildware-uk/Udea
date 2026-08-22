package dev.wildware.udea.core.loop

import dev.wildware.udea.core.rng.SimRandom
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The loop is a pure function of the wall deltas it is fed.
 *
 * Every test here runs with no LibGDX `Application`, no window and no GL context, because
 * [GameLoop] takes its time as an argument instead of reading `Gdx.graphics.deltaTime`. That
 * is not an incidental convenience: it is what lets CI, the agent harness and fast-forward
 * drive the identical loop the player runs.
 */
@Timeout(value = 1, unit = TimeUnit.SECONDS)
class GameLoopFixedStepTest {

    @Test
    fun `ten seconds of jittery wall time is exactly six hundred ticks`() {
        val sim = RecordingSimulation()
        // 100ms is six ticks, one more than the default catch-up cap, so a cap that small
        // would truncate a legitimate frame. This is a jitter test, not a stall test.
        val loop = GameLoop(sim, maxCatchUp = 16)

        var fedMillis = 0
        val random = SimRandom(seed = 20260822L)
        while (fedMillis < TEN_SECONDS_MILLIS) {
            val millis = (1 + random.nextInt(100)).coerceAtMost(TEN_SECONDS_MILLIS - fedMillis)
            fedMillis += millis
            loop.frame(millis / 1000f)
        }

        assertEquals(TEN_SECONDS_MILLIS, fedMillis)
        assertEquals(
            600,
            sim.steps,
            "ten wall seconds at 60Hz is 600 ticks; a float accumulator loses one to rounding",
        )
        assertEquals(0L, loop.truncatedFrames)
        assertTrue(loop.isAccumulatorEmpty, "10s divides into whole ticks with nothing left over")
    }

    @Test
    fun `a stalled frame is capped and drops its residue rather than spiralling`() {
        val sim = RecordingSimulation()
        val loop = GameLoop(sim, maxCatchUp = GameLoop.DEFAULT_MAX_CATCH_UP)

        // 250ms is fifteen ticks of owed simulation.
        loop.frame(0.250f)

        assertEquals(GameLoop.DEFAULT_MAX_CATCH_UP, sim.steps, "the cap is the ceiling per frame")
        assertEquals(GameLoop.DEFAULT_MAX_CATCH_UP, loop.lastFrameTicks)
        assertTrue(
            loop.isAccumulatorEmpty,
            "carrying the residue is what makes a stall spiral: each frame would owe more",
        )
        assertEquals(1L, loop.truncatedFrames, "the dropped time is counted, not hidden")

        // And the next ordinary frame behaves as if nothing happened.
        loop.frame(1f / 60f)
        assertEquals(GameLoop.DEFAULT_MAX_CATCH_UP + 1, sim.steps)
    }

    @Test
    fun `a debugger-sized delta is clamped before it is scaled`() {
        val sim = RecordingSimulation()
        // Room for more ticks than the clamp permits, so any burst would be visible.
        val loop = GameLoop(sim, maxCatchUp = 64)

        loop.frame(40f)

        assertEquals(
            (GameLoop.MAX_WALL_DELTA * 60).toInt(),
            sim.steps,
            "a 40 second delta must not produce 40 seconds of catch-up",
        )
        assertEquals(0L, loop.truncatedFrames, "the clamp handled it, so the cap never fired")
    }

    @Test
    fun `half and double time scale produce half and double the ticks`() {
        val ticksAtScale = { scale: Float ->
            val sim = RecordingSimulation()
            // 8ms frames: even at 2x that is under a tick per frame, so the catch-up cap
            // plays no part and the count measures time scaling alone.
            val loop = GameLoop(sim).apply { timeScale = scale }
            repeat(1250) { loop.frame(0.008f) }
            assertEquals(0L, loop.truncatedFrames)
            sim.steps
        }

        assertEquals(600, ticksAtScale(1f))
        assertEquals(300, ticksAtScale(0.5f))
        assertEquals(1200, ticksAtScale(2f))
    }

    @Test
    fun `a zero time scale freezes the simulation without freezing rendering`() {
        val sim = RecordingSimulation()
        val view = RecordingPresentation()
        val loop = GameLoop(sim, view).apply { timeScale = 0f }

        repeat(60) { loop.frame(1f / 60f) }

        assertEquals(0, sim.steps)
        assertEquals(60, view.renderCount)
    }

    @Test
    fun `stepTicks advances exactly n ticks and renders nothing`() {
        val sim = RecordingSimulation()
        val view = RecordingPresentation()
        val loop = GameLoop(sim, view)

        loop.stepTicks(7)

        assertEquals(7, sim.steps)
        assertEquals(0, view.renderCount, "an agent stepping the sim must not smear a frame into it")
        assertTrue(loop.paused, "stepping pauses, so the next real frame does not run on")
        assertEquals(7L, loop.totalTicks)

        // Paused means paused: wall time no longer advances the simulation.
        loop.frame(1f)
        assertEquals(7, sim.steps)
        assertEquals(1, view.renderCount)
    }

    @Test
    fun `stepTicks rejects a negative count`() {
        val loop = GameLoop(RecordingSimulation())
        assertFailsWith<IllegalArgumentException> { loop.stepTicks(-1) }
    }

    @Test
    fun `alpha is always in zero until one`() {
        val sim = RecordingSimulation()
        val view = RecordingPresentation()
        val loop = GameLoop(sim, view, maxCatchUp = 16)

        val random = SimRandom(seed = 7L)
        repeat(2000) { loop.frame((1 + random.nextInt(100)) / 1000f) }

        assertEquals(2000, view.renderCount)
        val offending = view.alphas.withIndex().filter { (_, alpha) -> alpha < 0f || alpha >= 1f }
        assertEquals(
            emptyList(),
            offending.map { "frame ${it.index}: alpha=${it.value}" },
            "alpha is how far the render sits between two ticks; 1.0 means a tick was skipped",
        )
        assertTrue(view.alphas.any { it > 0f }, "an alpha that is always zero is not interpolating")
    }

    @Test
    fun `alpha is the fraction of a tick that has elapsed`() {
        val sim = RecordingSimulation()
        val view = RecordingPresentation()
        val loop = GameLoop(sim, view)

        // 5ms is 0.3 of a 60Hz tick: the alpha walks 0.3, 0.6, 0.9 and then, once the fourth
        // frame has paid for a whole tick, drops back to the 0.2 it carries forward.
        repeat(4) { loop.frame(0.005f) }

        assertEquals(1, sim.steps)
        assertEquals(listOf(0.3f, 0.6f, 0.9f, 0.2f), view.alphas)
    }

    @Test
    fun `pausing stops the simulation and resuming restarts it`() {
        val sim = RecordingSimulation()
        val loop = GameLoop(sim)
        val control = TimeControl(loop)

        repeat(6) { loop.frame(1f / 60f) }
        control.pause()
        repeat(60) { loop.frame(1f / 60f) }
        assertEquals(6, sim.steps)

        control.resume()
        repeat(6) { loop.frame(1f / 60f) }
        assertEquals(12, sim.steps)
    }

    @Test
    fun `TimeControl exposes only whole-tick operations`() {
        val sim = RecordingSimulation()
        val loop = GameLoop(sim)
        val control = TimeControl(loop)

        control.timeScale(0.5f)
        assertEquals(0.5f, control.timeScale)

        control.step(3)
        assertEquals(3, sim.steps)
        assertTrue(control.paused)
        assertEquals(3L, control.totalTicks)

        assertFailsWith<IllegalArgumentException> { control.timeScale(-1f) }
        assertFailsWith<IllegalArgumentException> { control.step(-1) }
    }

    @Test
    fun `a nonsensical wall delta advances nothing`() {
        val sim = RecordingSimulation()
        val loop = GameLoop(sim)

        loop.frame(0f)
        loop.frame(-1f)
        loop.frame(Float.NaN)

        assertEquals(0, sim.steps)
        assertTrue(loop.isAccumulatorEmpty)
    }

    private companion object {
        const val TEN_SECONDS_MILLIS: Int = 10_000
    }
}
