package dev.wildware.udea.render.model

import de.fabmax.kool.math.Mat4f
import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.Model as KoolModel
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A [ClipPose] written into the Khronos Fox's own skin, through Kool's glTF clips, with no GL
 * context (issue #242).
 *
 * What is measured is where the skin puts a joint - the same matrices Kool's GPU skinning reads -
 * as a point in the model's own space: a joint's bind-pose position, moved by its joint transform.
 * The GL test (`GlSkinnedModelRenderTest`) is the same question asked of the pixels.
 *
 * The node is made with the stage's own [gltfLoadConfig], so a stage that stopped loading the
 * file's clips or skin would fail here too.
 */
class SkinnedPoseTest {

    private val survey = FoxClips.Survey
    private val walk = FoxClips.Walk
    private val run = FoxClips.Run

    private val fox = loadModel(exampleAssets(), Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")))
    private val pose = ClipPose()

    private fun at(tick: Long) = Tick(tick)

    private fun node(): KoolModel = fox.gltf.makeModel(gltfLoadConfig(fox, shadowMaps = emptyList()))

    @Test
    fun `the stage's node carries the file's three clips and its skin`() {
        val node = node()
        assertEquals(listOf("Survey", "Walk", "Run"), node.animations.map { it.name })
        assertEquals(1, node.skins.size)
    }

    @Test
    fun `a clip moves the head, and the same clip time puts it back`() {
        val node = node()
        val animator = Animator().apply { play(survey, at(0)) }
        node.applyPose(pose.set(animator, at(10), 0f))
        val early = head(node)
        node.applyPose(pose.set(animator, at(100), 0f))
        val late = head(node)
        println("SkinnedPoseTest: Survey head at tick 10 $early, at tick 100 $late")
        assertTrue(early.distance(late) > MOVED, "the head did not move between ticks 10 and 100: $early, $late")

        node.applyPose(pose.set(animator, at(10), 0f))
        assertEquals(early, head(node), "the pose depends on the tick, not on the poses before it")
    }

    @Test
    fun `no animator draws the bind pose, on a node that was posed before`() {
        val node = node()
        val bind = bindHead(node)
        node.applyPose(pose.set(Animator().apply { play(walk, at(0)) }, at(20), 0f))
        assertTrue(head(node).distance(bind) > MOVED, "Walk did not move the head off its bind pose")

        node.applyPose(pose.set(null, at(20), 0f))
        assertTrue(head(node).distance(bind) < SAME, "no animator: the head is at ${head(node)}, not its bind pose $bind")
    }

    @Test
    fun `a clip the file does not have draws the bind pose, with no error`() {
        val node = node()
        val bind = bindHead(node)
        node.applyPose(pose.set(Animator().apply { play(walk, at(0)) }, at(20), 0f))
        val stranger = AnimationClip(index = 7, name = "Dance", length = Ticks(30L))
        node.applyPose(pose.set(Animator().apply { play(stranger, at(0)) }, at(20), 0f))
        assertTrue(head(node).distance(bind) < SAME, "clip 7: the head is at ${head(node)}, not its bind pose $bind")
    }

    @Test
    fun `halfway through a crossfade every joint that moves lies between the two clips' poses`() {
        val node = node()
        // Walk from tick 0, then a crossfade to Run over 12 ticks from tick 30: tick 36 is halfway.
        val fading = Animator().apply {
            play(walk, at(0))
            crossfade(run, at(30), over = Ticks(12L))
        }
        val halfway = at(36)
        node.applyPose(pose.set(fading, halfway, 0f))
        assertEquals(0.5f, pose.weight)
        val mid = joints(node)

        // Each clip alone at the time it has at that tick.
        node.applyPose(pose.set(Animator().apply { play(walk, at(0)) }, halfway, 0f))
        val walking = joints(node)
        node.applyPose(pose.set(Animator().apply { play(run, at(30)) }, halfway, 0f))
        val running = joints(node)

        var compared = 0
        for (joint in mid.indices) {
            val apart = walking[joint].distance(running[joint])
            if (apart < MOVED) continue
            compared++
            val fromWalk = mid[joint].distance(walking[joint])
            val fromRun = mid[joint].distance(running[joint])
            assertTrue(
                fromWalk < apart && fromRun < apart && fromWalk > SAME && fromRun > SAME,
                "joint $joint: halfway at ${mid[joint]} is not between Walk ${walking[joint]} and Run " +
                    "${running[joint]} ($fromWalk from Walk, $fromRun from Run, $apart apart)",
            )
        }
        println("SkinnedPoseTest: $compared joints differ between Walk and Run at tick 36, and every one is between")
        assertTrue(compared >= 5, "only $compared joints differ between the two clips: the test is not looking at a fade")

        // And the ends of the fade are the clips themselves.
        node.applyPose(pose.set(fading, at(30), 0f))
        assertJointsNear(walking.size, joints(node), jointsOf(node, Animator().apply { play(walk, at(0)) }, at(30)), "the fade's first tick is Walk")
        node.applyPose(pose.set(fading, at(42), 0f))
        assertJointsNear(walking.size, joints(node), jointsOf(node, Animator().apply { play(run, at(30)) }, at(42)), "the fade's last tick is Run")
    }

    // --- measuring -----------------------------------------------------------------------

    private fun jointsOf(node: KoolModel, animator: Animator, now: Tick): List<Vec3f> {
        node.applyPose(pose.set(animator, now, 0f))
        return joints(node)
    }

    private fun assertJointsNear(count: Int, actual: List<Vec3f>, expected: List<Vec3f>, message: String) {
        for (joint in 0 until count) {
            assertTrue(actual[joint].distance(expected[joint]) < SAME, "$message: joint $joint at ${actual[joint]}, expected ${expected[joint]}")
        }
    }

    /** Where the skin puts every joint now, in the model's own space. */
    private fun joints(node: KoolModel): List<Vec3f> = node.skins.single().nodes.map { skinned ->
        val bind = bindPosition(skinned.inverseBindMatrix)
        MutableVec3f(bind).also { skinned.jointTransform.transform(it, 1f) }
    }

    private fun head(node: KoolModel): Vec3f = joints(node)[headIndex(node)]

    private fun bindHead(node: KoolModel): Vec3f =
        bindPosition(node.skins.single().nodes[headIndex(node)].inverseBindMatrix)

    private fun headIndex(node: KoolModel): Int =
        node.skins.single().nodes.indexOfFirst { it.joint.name == "b_Head_05" }.also { check(it >= 0) }

    /** A joint's position in the bind pose: the inverse of its inverse bind matrix, at the origin. */
    private fun bindPosition(inverseBind: Mat4f): Vec3f {
        val bind = MutableMat4f(inverseBind)
        check(bind.invert()) { "a joint's inverse bind matrix has no inverse" }
        return MutableVec3f().also { bind.transform(it, 1f) }
    }

    private fun exampleAssets(): Path = Path.of(
        System.getProperty("udea.render.exampleAssets") ?: error("-Dudea.render.exampleAssets is not set"),
    )

    private companion object {
        /** The fox is about 80 units tall: a joint that moved less than this did not move. */
        const val MOVED = 1f

        /** Two positions closer than this are the same position, to float rounding. */
        const val SAME = 1e-2f
    }
}
