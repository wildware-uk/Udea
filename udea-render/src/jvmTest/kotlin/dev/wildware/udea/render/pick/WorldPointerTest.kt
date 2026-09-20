package dev.wildware.udea.render.pick

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.InputCatalog
import dev.wildware.udea.render.input.PointerPosition
import dev.wildware.udea.render.input.PointerState
import dev.wildware.udea.render.input.PointerWheel
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelProjection
import dev.wildware.udea.render.support.CameraProjections
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seam between the mouse and the tick (issue #262): a pixel goes in, a world point and a `NetId`
 * come out, and an `Intent` carries them into the simulation.
 *
 * [CameraPickTest] holds the geometry and [EntityPickerTest] holds the ordering. What is left for
 * here is the wiring, and every one of these is a mistake that would be invisible in a screenshot:
 *
 * - **which way `y` runs.** A backend reports a cursor `y` down from the top of the window and a
 *   view pixel runs `y` up from the bottom. Get it the wrong way round and picking works perfectly
 *   in the middle of the screen and is mirrored everywhere else, which is the kind of bug somebody
 *   spends an afternoon on.
 * - **the frame clock against the tick clock.** A wheel notch and the two ends of a drag are
 *   *events* and are latched until a tick spends them; the cursor's position is a *level* and is
 *   simply the latest reading. A frame that covers two ticks must not deliver a notch twice, and two
 *   frames inside one tick must not lose one.
 * - **the pointer leaving the window**, which must stop the game pointing at wherever it went out.
 */
class WorldPointerTest {

    private val catalog = InputCatalog.of(actions = listOf("game/attack"), axes = listOf("game/move"))

    @Test
    fun `the world point under a window pixel is the point drawn at that pixel`() {
        // The `y` question, asked where it has an answer: a pixel in the *top* half of the window.
        // Under a flipped conversion this comes back mirrored about the middle and the error is
        // twice the pixel's distance from it - hundreds of world units, not a rounding difference.
        val pointer = pointerOver(PIXEL_X, TOP_PIXEL_Y)

        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)

        assertTrue(pointer.isOnWorld, "the cursor was over the ground and the pointer says it was not")
        val drawn = CameraProjections.pixelOf(pointer.camera, pointer.worldX, pointer.worldY, 0f, WIDTH, HEIGHT)
        val error = hypot(drawn.x - PIXEL_X, drawn.y - TOP_PIXEL_Y)
        println("WorldPointerTest: window pixel ($PIXEL_X, $TOP_PIXEL_Y) -> (${pointer.worldX}, ${pointer.worldY}), $error px back")
        assertTrue(error < ONE_PIXEL, "the point under the cursor draws $error pixels from the cursor")
    }

    @Test
    fun `the top and the bottom of the window are different places, and the right way round`() {
        // The control for the test above. If `aim` ignored the flip, these two would swap - so this
        // asserts they are far apart *and* that they are the right way round: the camera looks along
        // +x+y, so the top of the picture is further away, which in world terms is larger x and y.
        val top = pointerOver(PIXEL_X, TOP_PIXEL_Y).also { it.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT) }
        val bottom = pointerOver(PIXEL_X, HEIGHT - TOP_PIXEL_Y).also { it.aim(PIXEL_X, HEIGHT - TOP_PIXEL_Y, WIDTH, HEIGHT) }

        val apart = hypot(top.worldX - bottom.worldX, top.worldY - bottom.worldY)
        println("WorldPointerTest: top and bottom of the window are $apart world units apart")

        assertTrue(apart > VIEW_HEIGHT / 2f, "the top and the bottom of the window landed $apart units apart")
        assertTrue(
            top.worldX + top.worldY > bottom.worldX + bottom.worldY,
            "the top of the picture is nearer the camera than the bottom, so `y` is upside down",
        )
    }

    @Test
    fun `the entity under the cursor reaches the intent`() {
        val model = Boxes(listOf(PICKED to Triple(4f, -3f, 0f)))
        val pointer = pointerOver(0f, 0f, pickable = listOf(model))
        // Where that model actually draws, through the very camera this pointer picks with.
        val drawn = CameraProjections.pixelOf(pointer.camera, 4f, -3f, BOX_HALF, WIDTH, HEIGHT)

        pointer.aim(drawn.x, drawn.y, WIDTH, HEIGHT)
        val intent = Intent(catalog)
        pointer.writeInto(intent)

        assertEquals(PICKED, pointer.entity, "the model under the cursor was not picked")
        assertEquals(PICKED, intent.pointerEntity, "the picked model did not reach the intent")
        assertTrue(intent.hasPointer, "the intent carries no pointer")
    }

    @Test
    fun `open ground under the cursor names no entity`() {
        val model = Boxes(listOf(PICKED to Triple(4f, -3f, 0f)))
        val pointer = pointerOver(0f, 0f, pickable = listOf(model))
        val drawn = CameraProjections.pixelOf(pointer.camera, 4f, -3f, BOX_HALF, WIDTH, HEIGHT)

        pointer.aim(drawn.x + WELL_AWAY, drawn.y, WIDTH, HEIGHT)

        assertTrue(pointer.isOnWorld, "the cursor is still over the ground")
        assertEquals(NetId.NONE, pointer.entity, "empty ground named an entity")
    }

    @Test
    fun `a pointer that has left the window points at nothing`() {
        val pointer = pointerOver(PIXEL_X, TOP_PIXEL_Y)
        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)

        pointer.away()
        val intent = Intent(catalog)
        pointer.writeInto(intent)

        assertFalse(pointer.isOnWorld, "a cursor outside the window is still over the world")
        assertFalse(intent.hasPointer, "a cursor outside the window still gave the tick a position")
    }

    @Test
    fun `wheel notches from several frames reach one tick, once`() {
        val wheel = Wheel()
        val pointer = pointerOver(PIXEL_X, TOP_PIXEL_Y, wheel = wheel)

        wheel.scrollY = 1f
        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)
        wheel.scrollY = 2f
        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)
        val first = Intent(catalog).also { pointer.writeInto(it) }

        // A second tick with no frame in between: the notches were spent by the first one.
        val second = Intent(catalog).also { pointer.writeInto(it) }

        assertEquals(3f, first.scroll, "two frames of one notch and two did not add up on the tick")
        assertEquals(0f, second.scroll, "a spent wheel turn was delivered to a second tick as well")
    }

    @Test
    fun `a drag begun and ended between two ticks still reaches one`() {
        // The whole reason the events are latched. At 144Hz a flick of the wrist is a press and a
        // release inside one tick; a pointer that reported only a level would show neither.
        val buttons = Buttons()
        val pointer = pointerOver(PIXEL_X, TOP_PIXEL_Y, buttons = buttons)

        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)
        buttons.down = true
        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)
        val startedAtX = pointer.worldX
        buttons.down = false
        pointer.aim(PIXEL_X + DRAG_PIXELS, TOP_PIXEL_Y, WIDTH, HEIGHT)
        val endedAtX = pointer.worldX

        val intent = Intent(catalog)
        pointer.writeInto(intent)

        assertTrue(intent.dragStarted, "a drag that began and ended inside one tick reported no beginning")
        assertTrue(intent.dragEnded, "a drag that began and ended inside one tick reported no end")
        assertEquals(startedAtX, intent.dragStartX, "the drag began somewhere else")
        assertEquals(endedAtX, intent.dragEndX, "the drag ended somewhere else")
        assertTrue(
            abs(intent.dragEndX - intent.dragStartX) > 0f,
            "the drag began and ended at the same world point, so the box would be empty",
        )
    }

    @Test
    fun `a drag is delivered once and not again on the next tick`() {
        val buttons = Buttons()
        val pointer = pointerOver(PIXEL_X, TOP_PIXEL_Y, buttons = buttons)
        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)
        buttons.down = true
        pointer.aim(PIXEL_X, TOP_PIXEL_Y, WIDTH, HEIGHT)

        val first = Intent(catalog).also { pointer.writeInto(it) }
        val second = Intent(catalog).also { pointer.writeInto(it) }

        assertTrue(first.dragStarted, "the drag's beginning was not delivered")
        assertFalse(second.dragStarted, "the drag began twice: it was delivered to two ticks")
    }

    // --- helpers ----------------------------------------------------------------------------

    /** A pointer over an isometric camera looking at the origin, with the cursor at a given pixel. */
    private fun pointerOver(
        x: Float,
        y: Float,
        pickable: List<PickBounds> = emptyList(),
        buttons: PointerState = PointerState.NONE,
        wheel: PointerWheel = PointerWheel.NONE,
    ): WorldPointer {
        val yaw = (ISO_YAW * PI / HALF_TURN).toFloat()
        val pitch = (ISO_PITCH * PI / HALF_TURN).toFloat()
        val level = cos(pitch) * EYE_DISTANCE
        val camera = ModelCamera().apply {
            projection = ModelProjection.Orthographic
            viewHeight = VIEW_HEIGHT
            near = 0.1f
            far = EYE_DISTANCE * 2f
            lookAt(-level * cos(yaw), -level * sin(yaw), sin(pitch) * EYE_DISTANCE, 0f, 0f, 0f)
        }
        return WorldPointer(
            // The real type, built headless as `IsometricRigTest` builds one: `viewing` is what
            // this system reads from it, and a fake would be a fake of the thing under test.
            resources = RenderResources(
                SpriteBatch2D(SpriteTexture.whitePixel("world-pointer-test-white")),
                OffscreenTarget(WIDTH, HEIGHT),
            ),
            camera = camera,
            position = At(x, y),
            buttons = buttons,
            wheel = wheel,
            pickable = { pickable },
        )
    }

    private class At(override val pointerX: Float, override val pointerY: Float) : PointerPosition {
        override val isPointerOver: Boolean get() = true
    }

    private class Wheel(override var scrollY: Float = 0f) : PointerWheel {
        override fun spendScroll() {
            scrollY = 0f
        }
    }

    private class Buttons(var down: Boolean = false) : PointerState {
        override fun isButtonDown(button: Int): Boolean = down
        override fun pressesSince(button: Int): Int = 0
        override fun endSample(): Unit = Unit
    }

    private class Boxes(private val boxes: List<Pair<NetId, Triple<Float, Float, Float>>>) : PickBounds {
        override fun reportPickBounds(out: PickSink) {
            for ((entity, at) in boxes) {
                val (x, y, z) = at
                out.box(entity, x - BOX_HALF, y - BOX_HALF, z, x + BOX_HALF, y + BOX_HALF, z + BOX_HALF * 2f)
            }
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val ONE_PIXEL = 1f

        /** Well inside the top half of the window, so a flipped `y` cannot land near the truth. */
        const val PIXEL_X = 400f
        const val TOP_PIXEL_Y = 120f

        const val VIEW_HEIGHT = 20f
        const val BOX_HALF = 0.5f

        /** Pixels: far past a one-unit cube at this zoom, which is a few dozen across. */
        const val WELL_AWAY = 300f

        /** Pixels a drag moves: enough that the two ends are different world points. */
        const val DRAG_PIXELS = 90f

        const val ISO_YAW = 45.0
        const val ISO_PITCH = 30.0
        const val HALF_TURN = 180.0
        const val EYE_DISTANCE = 200f

        val PICKED: NetId = NetId.of(index = 5, generation = 0)
    }
}
