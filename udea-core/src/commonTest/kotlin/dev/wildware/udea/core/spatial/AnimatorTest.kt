package dev.wildware.udea.core.spatial

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.ticks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `play`, `crossfade`, `clipTime` and `isFinished`, at the ticks where each one changes its answer.
 *
 * The clips are the Fox's own lengths (Survey 205, Walk 43, Run 70 ticks), so a number here can be
 * checked against the generated `Fox.Clips` a game compiles against. Every test starts somewhere
 * other than tick zero, because a start of zero hides a clip time computed from `now` instead of
 * from `now - start`.
 */
class AnimatorTest {

    private val walk = AnimationClip(index = 1, name = "Walk", length = 43.ticks)
    private val run = AnimationClip(index = 2, name = "Run", length = 70.ticks)

    private val start = Tick(1_000)

    private fun at(elapsed: Long): Tick = start + elapsed

    // ---- play, looping ------------------------------------------------------------------------

    @Test
    fun `a looping clip counts up one tick per tick and wraps to zero at its length`() {
        val animator = Animator()
        animator.play(walk, start)

        assertEquals(0.ticks, animator.clipTime(at(0)))
        assertEquals(1.ticks, animator.clipTime(at(1)))
        assertEquals(42.ticks, animator.clipTime(at(42)))
        assertEquals(0.ticks, animator.clipTime(at(43)), "the tick a loop ends on is the tick it starts again")
        assertEquals(1.ticks, animator.clipTime(at(44)))
        assertEquals(0.ticks, animator.clipTime(at(86)), "and the second time round")
    }

    @Test
    fun `a looping clip never finishes`() {
        val animator = Animator()
        animator.play(walk, start)

        assertFalse(animator.isFinished(at(43)))
        assertFalse(animator.isFinished(at(43L * 1_000)))
    }

    @Test
    fun `before its start a clip reads zero and not a negative time`() {
        val animator = Animator()
        animator.play(walk, start)

        assertEquals(0.ticks, animator.clipTime(start - 5))
        assertFalse(animator.isFinished(start - 5))
    }

    // ---- play, once ---------------------------------------------------------------------------

    @Test
    fun `a clip played once holds its last tick and finishes exactly on it`() {
        val animator = Animator()
        animator.play(walk, start, loop = Loop.Once)

        assertEquals(42.ticks, animator.clipTime(at(42)))
        assertFalse(animator.isFinished(at(42)), "one tick before the end is not the end")
        assertEquals(43.ticks, animator.clipTime(at(43)))
        assertTrue(animator.isFinished(at(43)), "the clip's length is the tick it finishes on")
        assertEquals(43.ticks, animator.clipTime(at(500)), "held on the last pose, not wrapped")
        assertTrue(animator.isFinished(at(500)))
    }

    // ---- speed --------------------------------------------------------------------------------

    @Test
    fun `speed scales elapsed ticks and rounds down to a whole tick of the clip`() {
        val animator = Animator()
        animator.play(walk, start, speed = 1.5f)

        assertEquals(1.ticks, animator.clipTime(at(1)), "1.5 rounds down")
        assertEquals(3.ticks, animator.clipTime(at(2)))
        assertEquals(42.ticks, animator.clipTime(at(28)))
        assertEquals(0.ticks, animator.clipTime(at(29)), "43.5 is past the end of a 43-tick loop")
    }

    @Test
    fun `a fast clip played once finishes on the first tick its scaled time reaches the length`() {
        val animator = Animator()
        animator.play(walk, start, loop = Loop.Once, speed = 1.5f)

        assertFalse(animator.isFinished(at(28)), "42 of 43")
        assertTrue(animator.isFinished(at(29)), "43.5 of 43")
    }

    @Test
    fun `a slow clip takes twice as many ticks at half speed`() {
        val animator = Animator()
        animator.play(walk, start, loop = Loop.Once, speed = 0.5f)

        assertEquals(0.ticks, animator.clipTime(at(1)))
        assertEquals(1.ticks, animator.clipTime(at(2)))
        assertEquals(42.ticks, animator.clipTime(at(85)))
        assertFalse(animator.isFinished(at(85)))
        assertTrue(animator.isFinished(at(86)))
    }

    @Test
    fun `a clip at speed zero is frozen on its first tick and never finishes`() {
        val animator = Animator()
        animator.play(walk, start, loop = Loop.Once, speed = 0f)

        assertEquals(0.ticks, animator.clipTime(at(10_000)))
        assertFalse(animator.isFinished(at(10_000)))
    }

    @Test
    fun `a negative or non-finite speed is refused at the call`() {
        val animator = Animator()
        assertFailsWith<IllegalArgumentException> { animator.play(walk, start, speed = -1f) }
        assertFailsWith<IllegalArgumentException> { animator.play(walk, start, speed = Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            animator.crossfade(walk, start, over = 6.ticks, speed = Float.POSITIVE_INFINITY)
        }
    }

    // ---- play again ---------------------------------------------------------------------------

    @Test
    fun `playing again restarts from the new tick and forgets the old loop mode`() {
        val animator = Animator()
        animator.play(walk, start, loop = Loop.Once)
        animator.play(run, at(100))

        assertTrue(animator.isPlaying(run))
        assertEquals(0.ticks, animator.clipTime(at(100)))
        assertEquals(69.ticks, animator.clipTime(at(169)))
        assertEquals(0.ticks, animator.clipTime(at(170)), "Run loops, as play's default says")
    }

    // ---- crossfade ----------------------------------------------------------------------------

    @Test
    fun `a crossfade starts the new clip now and blends it in over the fade`() {
        val animator = Animator()
        animator.play(walk, start)
        animator.crossfade(run, at(10), over = 6.ticks)

        assertTrue(animator.isPlaying(run))
        assertEquals(0.ticks, animator.clipTime(at(10)))
        assertEquals(3.ticks, animator.clipTime(at(13)))
        assertEquals(0f, animator.blendWeight(at(10)), "nothing of the new clip on the tick the fade starts")
        assertEquals(0.5f, animator.blendWeight(at(13)))
        assertEquals(1f, animator.blendWeight(at(16)), "all of it on the tick the fade ends")
        assertEquals(1f, animator.blendWeight(at(400)))
    }

    @Test
    fun `the clip faded out keeps its own clock so it does not jump back to its first frame`() {
        val animator = Animator()
        animator.play(walk, start)
        animator.crossfade(run, at(10), over = 6.ticks)

        assertTrue(animator.previous.holds(walk))
        assertEquals(13.ticks, animator.previous.clipTime(at(13)), "Walk is still 13 ticks into its own loop")
    }

    @Test
    fun `a crossfade over zero ticks is a cut`() {
        val animator = Animator()
        animator.play(walk, start)
        animator.crossfade(run, at(10), over = 0.ticks)

        assertEquals(1f, animator.blendWeight(at(10)))
    }

    @Test
    fun `a crossfade from nothing is a play with nothing to blend from`() {
        val animator = Animator()
        animator.crossfade(run, at(10), over = 6.ticks)

        assertTrue(animator.isPlaying(run))
        assertTrue(animator.previous.isEmpty())
        assertEquals(1f, animator.blendWeight(at(10)))
    }

    @Test
    fun `play after a crossfade ends the blend at once`() {
        val animator = Animator()
        animator.play(walk, start)
        animator.crossfade(run, at(10), over = 6.ticks)
        animator.play(walk, at(12))

        assertEquals(1f, animator.blendWeight(at(12)))
        assertTrue(animator.previous.isEmpty())
    }

    @Test
    fun `a negative fade length is refused at the call`() {
        assertFailsWith<IllegalArgumentException> {
            Animator().crossfade(run, start, over = Ticks(-1))
        }
    }

    // ---- nothing playing, and a clip with no length ---------------------------------------------

    @Test
    fun `an animator that has played nothing reads zero and is not finished`() {
        val animator = Animator()

        assertTrue(animator.current.isEmpty())
        assertEquals(0.ticks, animator.clipTime(start))
        assertFalse(animator.isFinished(start))
        assertEquals(1f, animator.blendWeight(start))
    }

    @Test
    fun `a clip of no length reads zero and played once it is finished as soon as it starts`() {
        val still = AnimationClip(index = 3, name = "Still", length = 0.ticks)
        val animator = Animator()

        animator.play(still, start)
        assertEquals(0.ticks, animator.clipTime(at(7)))

        animator.play(still, start, loop = Loop.Once)
        assertTrue(animator.isFinished(start))
    }
}
