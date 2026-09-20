package dev.wildware.hollow

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.generated.Human
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gait follows the speed the solver produced (issue #250's second acceptance criterion).
 *
 * Idle, walk and run, and the crossfade between them, asserted on the real `Animator` of a real
 * character in a real headless game. Nothing here writes a clip: every clip change in this file is
 * `PlayerPoseSystem`'s answer to how fast the body ended the tick moving.
 */
class PlayerAnimationTest {

    private val scene = PlayerScene()

    @AfterTest
    fun close() {
        scene.close()
    }

    @Test
    fun `a character nobody is driving is idle`() {
        val me = scene.spawn()
        scene.run(TICKS)

        assertTrue(scene.animatorOf(me).isPlaying(Human.Clips.Idle), "standing still played ${playing(me)}")
    }

    @Test
    fun `walking plays the walk, running plays the run, and letting go goes back to idle`() {
        val me = scene.spawn()

        scene.moveX = 1f
        scene.run(TICKS)
        assertTrue(scene.animatorOf(me).isPlaying(Human.Clips.Walk), "walking played ${playing(me)}")

        scene.running = true
        scene.run(TICKS)
        assertTrue(scene.animatorOf(me).isPlaying(Human.Clips.Run), "running played ${playing(me)}")

        scene.moveX = 0f
        scene.running = false
        scene.run(TICKS)
        assertTrue(scene.animatorOf(me).isPlaying(Human.Clips.Idle), "stopping played ${playing(me)}")
    }

    @Test
    fun `a change of gait is a crossfade rather than a cut`() {
        val me = scene.spawn()
        scene.run(TICKS)

        // One tick with the key down: the pose system has run exactly once against a moving body,
        // so the fade to the walk has just begun and none of it has elapsed.
        scene.moveX = 1f
        scene.run(1)
        val animator = scene.animatorOf(me)
        val begun = scene.tick
        val fade = PlayerPoseSystem.FADE.count

        assertTrue(animator.isPlaying(Human.Clips.Walk), "the walk did not start: ${playing(me)}")
        assertEquals(
            Human.Clips.Idle.index,
            animator.previous.clip,
            "it cut to the walk instead of fading from the idle: ${animator.previous}",
        )
        assertEquals(fade, animator.fadeLength, "the fade was not ${PlayerPoseSystem.FADE}")

        // Partway through the fade the walk is partly shown, and by its end it is all of it.
        val weight = animator.blendWeight(begun + fade / 2)
        assertTrue(weight > 0f && weight < 1f, "halfway through the fade the walk was at $weight")
        assertEquals(1f, animator.blendWeight(begun + fade), "the fade never ended")
    }

    @Test
    fun `holding the same gait does not restart the clip`() {
        val me = scene.spawn()
        scene.moveX = 1f
        scene.run(1)
        val started = scene.animatorOf(me).current.start
        val length = Human.Clips.Walk.length.count
        val at = scene.animatorOf(me).clipTime(scene.tick).count

        scene.run(TICKS)

        assertEquals(started, scene.animatorOf(me).current.start, "the walk restarted while it was still walking")
        // Where the clip is comes from that start: one clip tick per simulation tick, and no
        // restart, so thirty ticks of walking is thirty ticks further into the sixty-tick clip.
        assertEquals(
            (at + TICKS) % length,
            scene.animatorOf(me).clipTime(scene.tick).count,
            "the clip is not where its start says",
        )

        // And a whole clip later it is back where it was, which is the loop rather than a counter.
        scene.run(length.toInt())
        assertEquals(
            (at + TICKS) % length,
            scene.animatorOf(me).clipTime(scene.tick).count,
            "the walk did not loop back round",
        )
    }

    @Test
    fun `a character pushing into a rock is idle, because it is not going anywhere`() {
        val me = scene.spawn()
        scene.prop(Prop.STONE_LARGE_A, x = ROCK_X, y = 0f, scale = ROCK_SCALE)

        scene.moveX = 1f
        scene.run(5 * TICKS_PER_SECOND)

        // The key is still down and the intent is still full speed east. The gait comes from what
        // the solver did, which is nothing, so the character stands against the rock rather than
        // running on the spot against it.
        assertTrue(scene.animatorOf(me).isPlaying(Human.Clips.Idle), "pressed against a rock it played ${playing(me)}")
    }

    private fun playing(id: NetId): String = scene.animatorOf(id).current.toString()

    private companion object {
        const val TICKS = 30
        const val TICKS_PER_SECOND = 60
        const val ROCK_X = 6f
        const val ROCK_SCALE = 2f
    }
}
