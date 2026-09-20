package dev.wildware.udea.render.pick

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelProjection
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
import dev.wildware.udea.render.view.ViewPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which entity is under the cursor, **outside the editor** (issue #262).
 *
 * The editor has picked entities since issue #235, through `PickBounds` - the interface a render
 * system implements to say where the things it draws are. A game needs the same answer for the same
 * reason and had no way to ask, so this is that machinery with the editor's camera taken out of it:
 * [EntityPicker] does the ordering and the hit test, and a [PickProjector] says where a world point
 * lands. The editor supplies one over its orbit camera; a game supplies [CameraPickProjector] over
 * the camera it is actually played through.
 *
 * The editor's own behaviour is guarded where it always was - `ScenePickerTest`, `ScenePickingTest`
 * and `GlScenePickingTest` - and those suites are this refactor's control: if the shared ordering
 * has moved, they move with it.
 *
 * ## What each test holds
 *
 * - **the model under the cursor** is issue #262's second criterion, through a real isometric camera:
 *   a model is placed, its centre is projected to a pixel, and picking that pixel must name it.
 * - **the nearer of two** is the ordering a flattened picture draws, which is not the same as the
 *   order the systems reported in.
 * - **nothing under an empty pixel**, and **a system drawn later wins**, are the two ways a picker
 *   is quietly wrong: it answers when it should not, and it answers with the thing behind.
 */
class EntityPickerTest {

    @Test
    fun `the model under the cursor is the model that was placed there`() {
        val pick = isoPick()
        val alone = Boxes(listOf(Box(ONE, 4f, -3f, 0f)))
        val picker = EntityPicker(CameraPickProjector(pick)) { listOf(alone) }

        val at = ViewPoint()
        assertTrue(pick.project(4f, -3f, BOX_HALF, at), "the model projected nowhere")

        assertEquals(listOf(ONE), picker.under(at.x, at.y), "picking where the model draws missed it")
    }

    @Test
    fun `nothing is under a pixel no model is drawn at`() {
        val pick = isoPick()
        val alone = Boxes(listOf(Box(ONE, 4f, -3f, 0f)))
        val picker = EntityPicker(CameraPickProjector(pick)) { listOf(alone) }

        val at = ViewPoint()
        assertTrue(pick.project(4f, -3f, BOX_HALF, at), "the model projected nowhere")

        assertEquals(
            emptyList(),
            picker.under(at.x + FAR_AWAY, at.y + FAR_AWAY),
            "picking well away from the only model still found something",
        )
    }

    @Test
    fun `of two models under the cursor the nearer one is first, whatever order they were reported in`() {
        // Along the camera's line of sight, so both draw at the same pixel and only depth separates
        // them.
        //
        // **Both report orders**, and that clause is the whole test. The first version of this file
        // reported the far one first only - and when the depth term was taken out of the comparator
        // as a mutation, this test stayed green, because the fallback rule ("the one reported later
        // is in front") happened to give the same answer. It was a test that could not fail, and it
        // was `udea-editor`'s own `ScenePickerTest` - which has always tried both orders - that went
        // red instead and said so.
        val far = worldAlongSight(FAR_ALONG_SIGHT)
        val near = worldAlongSight(NEAR_ALONG_SIGHT)
        for (nearFirst in listOf(false, true)) {
            val pick = isoPick()
            val reported = listOf(Box(TWO, far.x, far.y, far.z), Box(ONE, near.x, near.y, near.z))
            val both = Boxes(if (nearFirst) reported.reversed() else reported)
            val picker = EntityPicker(CameraPickProjector(pick)) { listOf(both) }

            val at = ViewPoint()
            assertTrue(pick.project(near.x, near.y, near.z + BOX_HALF, at), "the near model projected nowhere")

            assertEquals(
                listOf(ONE, TWO),
                picker.under(at.x, at.y),
                "reported ${if (nearFirst) "near first" else "far first"}: the far model came before the near one",
            )
        }
    }

    @Test
    fun `a system drawn later is in front of one drawn earlier`() {
        val pick = isoPick()
        val behind = Boxes(listOf(Box(TWO, 4f, -3f, 0f)))
        val infront = Boxes(listOf(Box(ONE, 4f, -3f, 0f)))
        val picker = EntityPicker(CameraPickProjector(pick)) { listOf(behind, infront) }

        val at = ViewPoint()
        assertTrue(pick.project(4f, -3f, BOX_HALF, at), "the model projected nowhere")

        assertEquals(
            listOf(ONE, TWO),
            picker.under(at.x, at.y),
            "the system drawn first was picked before the one drawn over it",
        )
    }

    @Test
    fun `a selection box names every entity it touches`() {
        val pick = isoPick()
        val spread = Boxes(listOf(Box(ONE, 4f, -3f, 0f), Box(TWO, -6f, 5f, 0f)))
        val picker = EntityPicker(CameraPickProjector(pick)) { listOf(spread) }

        val one = ViewPoint().also { assertTrue(pick.project(4f, -3f, BOX_HALF, it)) }
        val two = ViewPoint().also { assertTrue(pick.project(-6f, 5f, BOX_HALF, it)) }

        val tight = picker.touching(one.x - 1f, one.y - 1f, one.x + 1f, one.y + 1f)
        val wide = picker.touching(
            minOf(one.x, two.x) - 1f,
            minOf(one.y, two.y) - 1f,
            maxOf(one.x, two.x) + 1f,
            maxOf(one.y, two.y) + 1f,
        )

        assertEquals(listOf(ONE), tight, "a box round one model took the other as well")
        assertEquals(setOf(ONE, TWO), wide.toSet(), "a box round both models missed one")
    }

    @Test
    fun `a sprite lying on the ground is picked through the same camera`() {
        // A 3D game's ground decals and its 2D-authored markers report a rectangle rather than a box.
        // They lie on the ground plane, so they go through the same camera at the ground's height.
        val pick = isoPick()
        val flat = Rects(listOf(Rect(ONE, 2f, 2f, 6f, 6f)))
        val picker = EntityPicker(CameraPickProjector(pick)) { listOf(flat) }

        val at = ViewPoint()
        assertTrue(pick.project(4f, 4f, 0f, at), "the middle of the rectangle projected nowhere")

        assertEquals(listOf(ONE), picker.under(at.x, at.y), "a ground rectangle was not picked")
    }

    @Test
    fun `an entity reported by two systems is listed once`() {
        val pick = isoPick()
        val twice = Boxes(listOf(Box(ONE, 4f, -3f, 0f)))
        val picker = EntityPicker(CameraPickProjector(pick)) { listOf(twice, twice) }

        val at = ViewPoint()
        assertTrue(pick.project(4f, -3f, BOX_HALF, at), "the model projected nowhere")

        assertEquals(listOf(ONE), picker.under(at.x, at.y), "one entity drawn twice came back twice")
    }

    // --- helpers ----------------------------------------------------------------------------

    /** An isometric camera looking at the origin, flattened, 20 units tall, fitted to a 1280x720 picture. */
    private fun isoPick(): CameraPick {
        val yaw = (ISO_YAW * PI / HALF_TURN).toFloat()
        val pitch = (ISO_PITCH * PI / HALF_TURN).toFloat()
        val level = cos(pitch) * EYE_DISTANCE
        val camera = ModelCamera().apply {
            projection = ModelProjection.Orthographic
            viewHeight = 20f
            near = 0.1f
            far = EYE_DISTANCE * 2f
            lookAt(-level * cos(yaw), -level * sin(yaw), sin(pitch) * EYE_DISTANCE, 0f, 0f, 0f)
        }
        return CameraPick(camera).apply { fit(WIDTH, HEIGHT) }
    }

    /** The world point [along] units in front of the eye, straight down the line of sight. */
    private fun worldAlongSight(along: Float): WorldPoint {
        val yaw = (ISO_YAW * PI / HALF_TURN).toFloat()
        val pitch = (ISO_PITCH * PI / HALF_TURN).toFloat()
        val level = cos(pitch) * EYE_DISTANCE
        val eyeX = -level * cos(yaw)
        val eyeY = -level * sin(yaw)
        val eyeZ = sin(pitch) * EYE_DISTANCE
        val length = EYE_DISTANCE
        return WorldPoint(
            eyeX + (0f - eyeX) / length * along,
            eyeY + (0f - eyeY) / length * along,
            eyeZ + (0f - eyeZ) / length * along,
        )
    }

    private class Box(val entity: NetId, val x: Float, val y: Float, val z: Float)

    private class Boxes(private val boxes: List<Box>) : PickBounds {
        override fun reportPickBounds(out: PickSink) {
            for (box in boxes) {
                out.box(
                    box.entity,
                    box.x - BOX_HALF, box.y - BOX_HALF, box.z,
                    box.x + BOX_HALF, box.y + BOX_HALF, box.z + BOX_HALF * 2f,
                )
            }
        }
    }

    private class Rect(val entity: NetId, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    private class Rects(private val rects: List<Rect>) : PickBounds {
        override fun reportPickBounds(out: PickSink) {
            for (rect in rects) out.rect(rect.entity, rect.minX, rect.minY, rect.maxX, rect.maxY)
        }
    }

    private companion object {
        val ONE: NetId = NetId.of(1, 0)
        val TWO: NetId = NetId.of(2, 0)

        const val WIDTH = 1280
        const val HEIGHT = 720

        /** Half a one-unit cube. */
        const val BOX_HALF = 0.5f

        /** Pixels away from the only model: well past a cube, which is a few dozen pixels across. */
        const val FAR_AWAY = 300f

        const val ISO_YAW = 45.0
        const val ISO_PITCH = 30.0
        const val HALF_TURN = 180.0
        const val EYE_DISTANCE = 200f

        /** Two points on the line of sight, ten units apart, both well in front of the eye. */
        const val FAR_ALONG_SIGHT = 200f
        const val NEAR_ALONG_SIGHT = 190f
    }
}
