package dev.wildware.moba.model

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Loop
import dev.wildware.udea.core.ticks
import dev.wildware.udea.generated.Human
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The game's FBX character (issue #244): `models/human/Human.fbx`, which the asset build converts
 * to glTF, arrives as typed clips generated from that conversion and plays through the same
 * `Animator` API as the fox.
 *
 * That this file compiles at all is half the proof: `Human.Clips` is generated at build time from
 * the `.glb` the converter wrote, and nothing here is spelled as a string. The lengths are the
 * FBX's own takes, at 24 frames a second - Idle 240 frames, Punch 24, Run 15, Walk 24 - in 60Hz
 * ticks, rounded up: 600, 60, 38 (37.5) and 60.
 */
class HumanModelTest {

    @Test
    fun `the fbx's four takes are generated as typed clips, named for their actions, in file order`() {
        assertEquals(
            listOf(
                AnimationClip(index = 0, name = "Idle", length = 600.ticks),
                AnimationClip(index = 1, name = "Punch", length = 60.ticks),
                AnimationClip(index = 2, name = "Run", length = 38.ticks),
                AnimationClip(index = 3, name = "Walk", length = 60.ticks),
            ),
            listOf(Human.Clips.Idle, Human.Clips.Punch, Human.Clips.Run, Human.Clips.Walk),
        )
    }

    @Test
    fun `a looping walk wraps at the length the fbx gave it`() {
        val start = Tick(1_000)
        val animator = Animator()
        animator.play(Human.Clips.Walk, start)

        assertEquals(30.ticks, animator.clipTime(start + 30))
        assertEquals(59.ticks, animator.clipTime(start + 59))
        assertEquals(0.ticks, animator.clipTime(start + 60))
        assertFalse(animator.isFinished(start + 600))
    }

    @Test
    fun `a punch played once finishes on the tick its length says, and crossfades back to idle`() {
        val start = Tick(1_000)
        val animator = Animator()
        animator.play(Human.Clips.Punch, start, loop = Loop.Once)

        assertFalse(animator.isFinished(start + 59))
        assertTrue(animator.isFinished(start + 60))

        animator.crossfade(Human.Clips.Idle, start + 60, over = 6.ticks)
        assertTrue(animator.isPlaying(Human.Clips.Idle))
        assertEquals(0.5f, animator.blendWeight(start + 63))
        assertEquals(1f, animator.blendWeight(start + 66))
    }
}
