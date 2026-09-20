package dev.wildware.udea.render.camera

import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.input.PointerPosition
import dev.wildware.udea.render.input.PointerState
import dev.wildware.udea.render.input.PointerWheel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two controls every isometric strategy game has (issue #262): the cursor pushed against the
 * edge of the screen, and the middle button held and dragged. The wheel zooms.
 *
 * ## What is worth asserting here, and what is not
 *
 * Not "does it move" - anything moves. Three properties, each of which a plausible implementation
 * gets wrong:
 *
 * - **an edge scroll is a speed.** Twice the wall time moves twice as far, so the camera crosses the
 *   map in the same time on a 60Hz monitor and a 144Hz one. A per-frame step passes "does it move"
 *   and fails this.
 * - **a drag keeps the ground under the hand.** Dragging across half the picture moves the view
 *   across half of what the picture holds, **at every zoom**, which is the property that makes a
 *   drag feel like grabbing the map rather than like a speed control.
 * - **and it does not move when nothing asks it to.** A cursor in the middle of the screen, a
 *   button nobody is holding and a wheel nobody turned leave the camera exactly where it was.
 */
class IsoPanControlTest {

    @Test
    fun `a cursor in the middle of the screen moves nothing`() {
        val world = world()
        world.position.set(WIDTH / 2f, HEIGHT / 2f)

        world.control.step(A_FRAME, WIDTH, HEIGHT)

        assertEquals(0f, world.rig.focusX, "the view slid with the cursor nowhere near an edge")
        assertEquals(0f, world.rig.focusY, "the view slid with the cursor nowhere near an edge")
    }

    @Test
    fun `an edge scroll is a speed, so twice the time moves twice as far`() {
        val once = world().also {
            it.position.set(0f, HEIGHT / 2f)
            it.control.step(A_FRAME, WIDTH, HEIGHT)
        }
        val twice = world().also {
            it.position.set(0f, HEIGHT / 2f)
            it.control.step(A_FRAME * 2f, WIDTH, HEIGHT)
        }

        val short = distance(once)
        val long = distance(twice)
        println("IsoPanControlTest: ${A_FRAME}s scrolled $short units, ${A_FRAME * 2}s scrolled $long")

        assertTrue(short > 0f, "a cursor hard against the left edge did not scroll at all")
        assertTrue(
            abs(long / short - 2f) < A_HUNDREDTH,
            "twice the wall time scrolled ${long / short} times as far, so the edge scroll is not a speed",
        )
    }

    @Test
    fun `the edge scroll goes the way the cursor pushes`() {
        // Left, right, top and bottom, each checked against the rig's own report of which way the
        // picture faces - so this cannot pass by agreeing with a sign this file made up.
        val left = world().also { it.position.set(0f, HEIGHT / 2f); it.control.step(A_FRAME, WIDTH, HEIGHT) }
        val right = world().also { it.position.set(WIDTH.toFloat(), HEIGHT / 2f); it.control.step(A_FRAME, WIDTH, HEIGHT) }
        val top = world().also { it.position.set(WIDTH / 2f, 0f); it.control.step(A_FRAME, WIDTH, HEIGHT) }
        val bottom = world().also { it.position.set(WIDTH / 2f, HEIGHT.toFloat()); it.control.step(A_FRAME, WIDTH, HEIGHT) }

        val rig = left.rig
        assertTrue(along(left, rig.rightX, rig.rightY) < 0f, "pushing the left edge did not go left")
        assertTrue(along(right, rig.rightX, rig.rightY) > 0f, "pushing the right edge did not go right")
        assertTrue(along(top, rig.forwardX, rig.forwardY) > 0f, "pushing the top edge did not go into the picture")
        assertTrue(along(bottom, rig.forwardX, rig.forwardY) < 0f, "pushing the bottom edge did not come back out")
    }

    @Test
    fun `a middle drag across half the picture moves the view across half of what it holds`() {
        for (zoom in floatArrayOf(8f, 20f, 60f)) {
            val world = world()
            world.rig.viewHeight = zoom
            // Both ends well clear of every edge, so nothing but the drag can have moved the view.
            world.position.set(WIDTH / 2f, HEIGHT * DRAG_FROM)
            world.buttons.down = true
            world.control.step(A_FRAME, WIDTH, HEIGHT)

            world.position.set(WIDTH / 2f, HEIGHT * DRAG_TO)
            world.control.step(A_FRAME, WIDTH, HEIGHT)

            val moved = distance(world)
            val half = zoom / 2f
            println("IsoPanControlTest: at a view height of $zoom a half-picture drag moved $moved units (half is $half)")
            assertTrue(
                abs(moved - half) < half * A_TENTH,
                "at a view height of $zoom a drag across half the picture moved $moved units, and " +
                    "half the picture is $half: the ground is not staying under the hand",
            )
        }
    }

    @Test
    fun `a drag with nobody holding the button moves nothing`() {
        val world = world()
        world.position.set(WIDTH / 2f, HEIGHT * DRAG_FROM)
        world.control.step(A_FRAME, WIDTH, HEIGHT)

        world.position.set(WIDTH / 2f, HEIGHT * DRAG_TO)
        world.control.step(A_FRAME, WIDTH, HEIGHT)

        assertEquals(0f, distance(world), "the view was dragged by a button nobody was holding")
    }

    @Test
    fun `a notch away from the player zooms in and a notch back zooms out`() {
        val inwards = world().also { it.wheel.scrollY = 1f; it.control.step(A_FRAME, WIDTH, HEIGHT) }
        val outwards = world().also { it.wheel.scrollY = -1f; it.control.step(A_FRAME, WIDTH, HEIGHT) }

        assertTrue(inwards.rig.viewHeight < START_HEIGHT, "a notch away from the player did not zoom in")
        assertTrue(outwards.rig.viewHeight > START_HEIGHT, "a notch back towards the player did not zoom out")
    }

    @Test
    fun `two notches zoom by the square of one, and the wheel is spent`() {
        // Compounding, not adding: it is what makes the wheel feel the same at every zoom level.
        val one = world().also { it.wheel.scrollY = 1f; it.control.step(A_FRAME, WIDTH, HEIGHT) }
        val two = world().also { it.wheel.scrollY = 2f; it.control.step(A_FRAME, WIDTH, HEIGHT) }

        val oneStep = one.rig.viewHeight / START_HEIGHT
        val twoSteps = two.rig.viewHeight / START_HEIGHT
        println("IsoPanControlTest: one notch is x$oneStep, two are x$twoSteps, one squared is ${oneStep * oneStep}")

        assertTrue(
            abs(twoSteps - oneStep * oneStep) < A_HUNDREDTH,
            "two notches gave x$twoSteps where one squared is x${oneStep * oneStep}",
        )
        assertEquals(0f, one.wheel.scrollY, "the wheel was read and not spent, so it zooms again next frame")

        // And a second frame with nothing new on the wheel changes nothing.
        val before = one.rig.viewHeight
        one.control.step(A_FRAME, WIDTH, HEIGHT)
        assertEquals(before, one.rig.viewHeight, "a spent wheel turn zoomed a second time")
    }

    // --- helpers ----------------------------------------------------------------------------

    /** How far the view's focus has moved from where it started. */
    private fun distance(world: World): Float =
        kotlin.math.hypot(world.rig.focusX, world.rig.focusY)

    /** How far the view's focus has moved along ground direction ([dx], [dy]). */
    private fun along(world: World, dx: Float, dy: Float): Float =
        world.rig.focusX * dx + world.rig.focusY * dy

    private class World {
        val resources = RenderResources(
            SpriteBatch2D(SpriteTexture.whitePixel("iso-pan-test-white")),
            OffscreenTarget(WIDTH, HEIGHT),
        )
        val rig = IsometricRig(resources, FixedSeconds).apply { viewHeight = START_HEIGHT }
        val position = At()
        val buttons = Buttons()
        val wheel = Wheel()
        val control = IsoPanControl(resources, FixedSeconds, rig, position, buttons, wheel)
    }

    private fun world() = World()

    private class At : PointerPosition {
        override var isPointerOver: Boolean = true
        override var pointerX: Float = 0f
        override var pointerY: Float = 0f

        fun set(x: Float, y: Float) {
            pointerX = x
            pointerY = y
        }
    }

    private class Buttons(var down: Boolean = false) : PointerState {
        override fun isButtonDown(button: Int): Boolean = down
        override fun pressesSince(button: Int): Int = 0
        override fun endSample(): Unit = Unit
    }

    private class Wheel(override var scrollY: Float = 0f) : PointerWheel {
        override fun spendScroll() {
            scrollY = 0f
        }
    }

    /** A frame of a fixed length. Only [IsoPanControl.render] reads it; [IsoPanControl.step] is told. */
    private object FixedSeconds : FrameTime {
        override val frameSeconds: Float get() = A_FRAME
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720

        /** A sixtieth of a second. */
        const val A_FRAME = 1f / 60f

        const val START_HEIGHT = 20f
        const val A_HUNDREDTH = 0.01f
        const val A_TENTH = 0.1f

        /** The two ends of the test drag, as fractions of the picture's height: exactly half of it. */
        const val DRAG_FROM = 0.75f
        const val DRAG_TO = 0.25f
    }
}
