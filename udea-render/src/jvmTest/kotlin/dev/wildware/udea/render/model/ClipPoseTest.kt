package dev.wildware.udea.render.model

import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.ClipPlayback
import dev.wildware.udea.core.spatial.Loop
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ClipPose]: where an entity's clips are this frame, from its `Animator`, the tick and the
 * interpolation alpha alone (issue #242).
 *
 * Plain numbers, no Kool: the Kool half that writes them into a skinned model is
 * `SkinnedPoseTest`'s.
 */
class ClipPoseTest {

    private val walk = AnimationClip(index = 1, name = "Walk", length = Ticks(43L))
    private val run = AnimationClip(index = 2, name = "Run", length = Ticks(70L))

    private fun at(tick: Long) = Tick(tick)

    @Test
    fun `at speed 1 the clip time is the simulation's clip time plus alpha, in ticks`() {
        val animator = Animator().apply { play(walk, at(10)) }
        val pose = ClipPose()
        for (tick in 10L..120L) {
            for (alpha in floatArrayOf(0f, 0.25f, 0.5f, 0.999f)) {
                pose.set(animator, at(tick), alpha)
                val expected = animator.clipTime(at(tick)).count + alpha.toDouble()
                assertEquals(expected, pose.currentTicks, 1e-9, "tick $tick alpha $alpha")
                assertEquals((expected / SimClock.DEFAULT_TICK_RATE).toFloat(), pose.currentSeconds, "tick $tick alpha $alpha")
            }
        }
    }

    @Test
    fun `at alpha 0 the whole clip ticks are the simulation's, at any speed`() {
        // What keeps the renderer from drifting off the simulation's answer: the gameplay code asks
        // `clipTime` whether the attack is at its hit frame, and the picture must agree.
        val pose = ClipPose()
        for (speed in floatArrayOf(0f, 0.25f, 0.75f, 1f, 1.5f, 2f, 3.3f)) {
            for (loop in Loop.entries) {
                val animator = Animator().apply { play(run, at(5), loop = loop, speed = speed) }
                for (tick in 0L..400L) {
                    pose.set(animator, at(tick), 0f)
                    assertEquals(
                        animator.clipTime(at(tick)).count,
                        floor(pose.currentTicks).toLong(),
                        "speed $speed $loop tick $tick: ${pose.currentTicks}",
                    )
                }
            }
        }
    }

    @Test
    fun `between ticks the clip time never runs backwards, at a speed that is not whole`() {
        val animator = Animator().apply { play(run, at(0), loop = Loop.Once, speed = 1.5f) }
        val pose = ClipPose()
        var last = -1.0
        for (tick in 0L..60L) {
            for (step in 0 until 4) {
                pose.set(animator, at(tick), step / 4f)
                assertTrue(pose.currentTicks >= last, "tick $tick step $step went back: ${pose.currentTicks} < $last")
                last = pose.currentTicks
            }
        }
    }

    @Test
    fun `a repeating clip wraps at its length and a finished once clip holds its last frame`() {
        val pose = ClipPose()
        val repeating = Animator().apply { play(walk, at(0)) }
        pose.set(repeating, at(42), 0.5f)
        assertEquals(42.5, pose.currentTicks, 1e-9)
        pose.set(repeating, at(43), 0.5f)
        assertEquals(0.5, pose.currentTicks, 1e-9, "tick 43 is the first frame again")

        val once = Animator().apply { play(walk, at(0), loop = Loop.Once) }
        pose.set(once, at(42), 0.5f)
        assertEquals(42.5, pose.currentTicks, 1e-9)
        pose.set(once, at(43), 0.9f)
        assertEquals(43.0, pose.currentTicks, 1e-9, "held at its length")
        pose.set(once, at(500), 0.5f)
        assertEquals(43.0, pose.currentTicks, 1e-9, "still held")
    }

    @Test
    fun `before its start a clip is on its first frame`() {
        val animator = Animator().apply { play(walk, at(100)) }
        val pose = ClipPose()
        pose.set(animator, at(98), 0.7f)
        assertEquals(0.0, pose.currentTicks)
        pose.set(animator, at(99), 0.7f)
        assertEquals(0.0, pose.currentTicks, "a tick before the start, however close alpha is")
    }

    @Test
    fun `no animator, or one with nothing playing, is the bind pose`() {
        val pose = ClipPose()
        pose.set(Animator().apply { play(walk, at(0)) }, at(10), 0f)
        pose.set(null, at(10), 0.5f)
        assertEquals(ClipPlayback.NO_CLIP, pose.current, "no animator")
        assertEquals(ClipPlayback.NO_CLIP, pose.previous)

        pose.set(Animator(), at(10), 0.5f)
        assertEquals(ClipPlayback.NO_CLIP, pose.current, "an animator with no clip")
    }

    @Test
    fun `halfway through a crossfade both clips are weighed, each at its own time`() {
        val animator = Animator().apply {
            play(walk, at(0))
            crossfade(run, at(20), over = Ticks(10L), speed = 2f)
        }
        val pose = ClipPose()
        pose.set(animator, at(25), 0f)
        assertEquals(run.index, pose.current)
        assertEquals(walk.index, pose.previous)
        assertEquals(0.5f, pose.weight, "five ticks into ten")
        assertEquals(animator.blendWeight(at(25)), pose.weight, "the simulation's own weight at alpha 0")
        assertEquals(10.0, pose.currentTicks, 1e-9, "Run at speed 2, five ticks in")
        assertEquals(25.0, pose.previousTicks, 1e-9, "Walk carries on moving while it fades")

        pose.set(animator, at(25), 0.5f)
        assertEquals(0.55f, pose.weight, 1e-6f, "alpha moves the fade on between ticks")
    }

    @Test
    fun `the fade weight follows the simulation's at every tick and is continuous between them`() {
        val animator = Animator().apply {
            play(walk, at(0))
            crossfade(run, at(20), over = Ticks(8L))
        }
        val pose = ClipPose()
        var last = -1f
        for (tick in 18L..32L) {
            pose.set(animator, at(tick), 0f)
            assertEquals(animator.blendWeight(at(tick)), pose.weight, "tick $tick")
            for (step in 0 until 4) {
                pose.set(animator, at(tick), step / 4f)
                assertTrue(pose.weight in 0f..1f && pose.weight >= last, "tick $tick step $step: ${pose.weight} after $last")
                last = pose.weight
            }
        }
        pose.set(animator, at(40), 0.5f)
        assertEquals(1f, pose.weight)
        assertEquals(ClipPlayback.NO_CLIP, pose.previous, "a finished fade shows only the clip it faded to")
    }

    @Test
    fun `the same animator, tick and alpha give the same pose whatever was asked before`() {
        val animator = Animator().apply {
            play(walk, at(0))
            crossfade(run, at(20), over = Ticks(10L), speed = 1.25f)
        }
        val pose = ClipPose()
        pose.set(animator, at(24), 0.3f)
        val first = listOf(pose.current, pose.currentTicks, pose.previous, pose.previousTicks, pose.weight)
        pose.set(animator, at(300), 0.9f)
        pose.set(null, at(1), 0f)
        pose.set(animator, at(24), 0.3f)
        assertEquals(first, listOf(pose.current, pose.currentTicks, pose.previous, pose.previousTicks, pose.weight))
    }
}
