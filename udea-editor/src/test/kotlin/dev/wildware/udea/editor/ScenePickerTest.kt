package dev.wildware.udea.editor

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.WorldViewport
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [ScenePicker] puts in front where a Scene view shows models (issue #235): the model pass is
 * depth-tested, so of two boxes under the pointer the one nearer the 3D eye is picked first, whichever
 * order its system reported them in.
 */
class ScenePickerTest {

    private val near = NetId.of(index = 1, generation = 0)
    private val far = NetId.of(index = 2, generation = 0)

    @Test
    fun `of two model boxes under the pointer the one nearer the eye is in front, whatever order they were reported in`() {
        val camera = EditorCamera().apply { dimension = ViewDimension.ThreeD }
        // The eye, from the orbit's own public numbers: along the line from the centre towards it,
        // one box halfway and one at the centre, so both are drawn over the middle of the view.
        val yaw = camera.yawDegrees * PI.toFloat() / 180f
        val pitch = camera.pitchDegrees * PI.toFloat() / 180f
        val towardsEyeX = camera.distance * cos(pitch) * cos(yaw)
        val towardsEyeY = camera.distance * cos(pitch) * sin(yaw)
        val towardsEyeZ = camera.distance * sin(pitch)
        // Reported far first then near, and the other way round: report order must not decide it.
        for (nearFirst in listOf(false, true)) {
            val boxes = Boxes(
                listOf(
                    far to Triple(camera.targetX, camera.targetY, camera.targetZ),
                    near to Triple(camera.targetX + towardsEyeX / 2f, camera.targetY + towardsEyeY / 2f, camera.targetZ + towardsEyeZ / 2f),
                ).let { if (nearFirst) it.reversed() else it },
            )
            val view = WorldViewport.detached(camera, VIEW_WIDTH, VIEW_HEIGHT, listOf(boxes))

            val under = ScenePicker(view).under(VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f)

            assertEquals(listOf(near, far), under, "reported ${if (nearFirst) "near first" else "far first"}")
        }
    }

    /** A system that reports a unit cube round each point, in the order given. */
    private class Boxes(private val boxes: List<Pair<NetId, Triple<Float, Float, Float>>>) : PickBounds {
        override fun reportPickBounds(out: PickSink) {
            for ((entity, at) in boxes) {
                val (x, y, z) = at
                out.box(entity, x - HALF, y - HALF, z - HALF, x + HALF, y + HALF, z + HALF)
            }
        }
    }

    private companion object {
        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360
        const val HALF = 0.5f
    }
}
