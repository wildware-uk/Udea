package dev.wildware.udea.render.kool

import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.InputBindings
import dev.wildware.udea.render.input.InputFrame
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.PointerId
import dev.wildware.udea.render.input.PointerReportListener
import dev.wildware.udea.render.input.UiPointers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #227's reconciliation, with no context: **a pointer the interface used never becomes an
 * intent**, and one it did not use becomes exactly the intent it would have with no interface at all.
 *
 * The interface's verdict on a frame's pointers arrives after that frame has been sampled - Kool polls
 * the pointer, the game ticks, and only then does the ComposeGL scene render and say what it used. So
 * [KoolPointer] holds each frame's changes back until the verdict for that frame arrives, and these
 * tests drive exactly that order: a frame sampled, then (maybe) a tick, then the report.
 *
 * The interface here is a fake that reports only what a test tells it to, which is the point: the
 * *order* is what is under test. That the real Kool delivers to `KoolPointer`, and that a real ComposeGL
 * button really reports a click as used, is `GlKoolPointerTest`'s job.
 *
 * ## The negative is half the test
 *
 * "No intent" is a green result for a pointer the interface took *and* for a pointer nobody wired, so
 * every held-back case has a declined twin beside it, and the no-interface case is here too.
 */
class KoolPointerOrderTest {

    private val ui = ScriptedUi()

    private val pointer = KoolPointer(ui)

    @AfterTest
    fun detach() {
        pointer.close()
    }

    @Test
    fun `a click the interface used never reaches the game`() {
        frame(1) { press(MOUSE, LEFT) }
        ui.report(MOUSE, 1, used = true)

        assertFalse(pointer.isButtonDown(LEFT_BUTTON), "a press on a button was held down for the game as well")
        assertEquals(0, pointer.pressesSince(LEFT_BUTTON), "a press on a button also pressed for the game")
    }

    @Test
    fun `a click the interface did not use reaches the game`() {
        frame(1) { press(MOUSE, LEFT) }
        ui.report(MOUSE, 1, used = false)

        assertTrue(pointer.isButtonDown(LEFT_BUTTON), "a press on the scene was lost")
        assertEquals(1, pointer.pressesSince(LEFT_BUTTON), "a press on the scene lost its edge")
    }

    @Test
    fun `nothing reaches the game before the interface has said whether it used the frame`() {
        frame(1) { press(MOUSE, LEFT) }

        // The tick between Kool's poll and the scene's render: the verdict does not exist yet.
        assertFalse(pointer.isButtonDown(LEFT_BUTTON), "a press reached the game before the interface judged it")
        assertEquals(0, pointer.pressesSince(LEFT_BUTTON), "a press reached the game before the interface judged it")

        ui.report(MOUSE, 1, used = false)
        assertEquals(1, pointer.pressesSince(LEFT_BUTTON), "the press was lost once the verdict came")
    }

    @Test
    fun `a report is matched to the frame the pointer was read in, not the one it arrives in`() {
        frame(1) { press(MOUSE, LEFT) }
        frame(2) { release(MOUSE, LEFT) }

        // The verdict for frame 1 arrives while frame 2 is already sampled. Frame 1 was a press on a
        // button; frame 2 released over the scene. Applying frame 1's verdict to frame 2 - or frame 2's
        // to frame 1 - is exactly the off-by-one this matching exists to prevent.
        ui.report(MOUSE, 1, used = true)
        assertEquals(0, pointer.pressesSince(LEFT_BUTTON), "frame 1's press was judged by some other frame's verdict")
        ui.report(MOUSE, 2, used = false)

        assertEquals(0, pointer.pressesSince(LEFT_BUTTON), "a click on a button became a press once it was released")
        assertFalse(pointer.isButtonDown(LEFT_BUTTON), "a click on a button left the button held for the game")
    }

    @Test
    fun `a used frame's verdict does not reach back into an earlier unused one`() {
        frame(1) { press(MOUSE, LEFT) }
        frame(2) { move(MOUSE) }
        ui.report(MOUSE, 1, used = false)
        ui.report(MOUSE, 2, used = true)

        assertEquals(1, pointer.pressesSince(LEFT_BUTTON), "a later frame's verdict took an earlier press away")
        assertTrue(pointer.isButtonDown(LEFT_BUTTON), "dragging over the interface let go of a button held on the scene")
    }

    @Test
    fun `a release is never taken away from the game`() {
        // Pressed on the scene, dragged onto a control, released there: the interface may well say it
        // used that release. If the game did not see it, the button would stay down for ever.
        frame(1) { press(MOUSE, LEFT) }
        ui.report(MOUSE, 1, used = false)
        frame(2) { release(MOUSE, LEFT) }
        ui.report(MOUSE, 2, used = true)

        assertFalse(pointer.isButtonDown(LEFT_BUTTON), "a release over the interface left the button stuck down")
        assertEquals(1, pointer.pressesSince(LEFT_BUTTON), "the scene press was lost")
    }

    @Test
    fun `a press on the interface dragged onto the scene never becomes held`() {
        frame(1) { press(MOUSE, LEFT) }
        ui.report(MOUSE, 1, used = true)
        frame(2) { move(MOUSE) }
        ui.report(MOUSE, 2, used = false)

        assertFalse(pointer.isButtonDown(LEFT_BUTTON), "a drag that began on a slider became a held button once it left it")
        assertEquals(0, pointer.pressesSince(LEFT_BUTTON), "a drag that began on a slider pressed for the game")
    }

    @Test
    fun `a pointer that goes releases everything it held`() {
        frame(1) { press(MOUSE, LEFT or RIGHT) }
        ui.report(MOUSE, 1, used = false)
        frame(2) { /* the mouse left the window: Kool lists it no more */ }
        ui.report(MOUSE, 2, used = true)

        assertFalse(pointer.isButtonDown(LEFT_BUTTON), "the mouse left the window and the game still holds its left button")
        assertFalse(pointer.isButtonDown(RIGHT_BUTTON), "the mouse left the window and the game still holds its right button")
    }

    @Test
    fun `two pointers are judged separately`() {
        frame(1) {
            press(FINGER_A, LEFT)
            press(FINGER_B, LEFT)
        }
        ui.report(FINGER_A, 1, used = true)
        ui.report(FINGER_B, 1, used = false)

        assertEquals(1, pointer.pressesSince(LEFT_BUTTON), "one finger on a button and one on the scene is one press")
        assertTrue(pointer.isButtonDown(LEFT_BUTTON), "the finger on the scene was lost with the one on the button")

        frame(2) {
            move(FINGER_A) // still down, and still the interface's
            move(FINGER_B)
        }
        ui.report(FINGER_A, 2, used = false)
        ui.report(FINGER_B, 2, used = false)
        assertTrue(pointer.isButtonDown(LEFT_BUTTON), "finger B, still on the scene, stopped holding")
        frame(3) { move(FINGER_A) } // listed alone: finger B lifted
        ui.report(FINGER_A, 3, used = false)
        ui.report(FINGER_B, 3, used = false)

        assertFalse(pointer.isButtonDown(LEFT_BUTTON), "finger A's press on the button became held once finger B lifted")
    }

    @Test
    fun `a frame the interface never reports on is the game's once a later report arrives`() {
        // The interface's scene began listening a frame after this did, so frame 1 has no verdict and
        // never will. Nothing looked at it, so nothing can have taken it - and the first verdict that
        // does arrive, used, is about frame 2 and says nothing about frame 1.
        frame(1) { press(MOUSE, LEFT) }
        frame(2) { move(MOUSE) }
        ui.report(MOUSE, 2, used = true)

        assertEquals(1, pointer.pressesSince(LEFT_BUTTON), "a frame the interface never saw was judged by a later frame's verdict")
        assertTrue(pointer.isButtonDown(LEFT_BUTTON), "a press the interface never saw was not held")
    }

    @Test
    fun `with no interface every click is the game's at once`() {
        val bare = KoolPointer()
        try {
            bare.beginFrame(InputFrame(1))
            bare.onPointer(PointerId(MOUSE), LEFT)
            bare.endFrame()

            assertTrue(bare.isButtonDown(LEFT_BUTTON), "with no interface up, a click was held back for a verdict nothing sends")
            assertEquals(1, bare.pressesSince(LEFT_BUTTON), "with no interface up, a click was lost")
        } finally {
            bare.close()
        }
    }

    @Test
    fun `an interface that stops reporting hands back what it was judging`() {
        frame(1) { press(MOUSE, LEFT) }
        ui.reporting = false
        frame(2) { move(MOUSE) }

        assertEquals(1, pointer.pressesSince(LEFT_BUTTON), "a press waiting on an interface that closed was lost")
    }

    @Test
    fun `a sample spends the counted presses`() {
        frame(1) { press(MOUSE, LEFT) }
        ui.report(MOUSE, 1, used = false)

        pointer.endSample()

        assertEquals(0, pointer.pressesSince(LEFT_BUTTON), "one press was counted by two ticks")
        assertTrue(pointer.isButtonDown(LEFT_BUTTON), "spending the edge let go of the level")
    }

    @Test
    fun `a click the interface did not use becomes an intent, and one it used does not`() {
        val bindings = InputBindings(
            actions = listOf(ActionBinding(name = "test/fire", pointerButtons = intArrayOf(LEFT_BUTTON))),
            axes = emptyList(),
        )
        val source = DeviceIntent(bindings, pointer = pointer)
        val fire = bindings.catalog.action("test/fire")

        frame(1) { press(MOUSE, LEFT) }
        ui.report(MOUSE, 1, used = true)
        frame(2) { release(MOUSE, LEFT) }
        ui.report(MOUSE, 2, used = true)
        val onButton = Intent(bindings.catalog).also(source::sample)

        frame(3) { press(MOUSE, LEFT) }
        ui.report(MOUSE, 3, used = false)
        val onScene = Intent(bindings.catalog).also(source::sample)

        assertEquals(0, onButton.pressCount(fire), "a click on a button fired the binding under it")
        assertFalse(onButton.isPressed(fire), "a click on a button held the binding under it")
        assertEquals(1, onScene.pressCount(fire), "a click on the scene did not fire its binding")
        assertTrue(onScene.isPressed(fire), "a press on the scene did not hold its binding")
    }

    // --- fixture -------------------------------------------------------------------------

    /** One of Kool's frames, as its pointer listener sees it. */
    private fun frame(number: Int, pointers: Frame.() -> Unit) {
        pointer.beginFrame(InputFrame(number))
        Frame().pointers()
        pointer.endFrame()
    }

    /**
     * The pointers listed in one frame, each with the buttons down now. Kool reports state, not events,
     * so a press is "down now, and not last frame".
     */
    private inner class Frame {

        fun press(id: Int, mask: Int) = list(id, (last[id] ?: 0) or mask)

        fun release(id: Int, mask: Int) = list(id, (last[id] ?: 0) and mask.inv())

        fun move(id: Int) = list(id, last[id] ?: 0)

        private fun list(id: Int, buttons: Int) {
            pointer.onPointer(PointerId(id), buttons)
            last[id] = buttons
        }
    }

    /** What each pointer held in the frame before, so a frame can say what changed. */
    private val last = HashMap<Int, Int>()

    /** An interface whose verdicts are whatever the test says, delivered when the test says. */
    private class ScriptedUi : UiPointers {

        var reporting = true

        private var listener: PointerReportListener? = null

        override val isReporting: Boolean get() = reporting

        override fun reportTo(listener: PointerReportListener?) {
            this.listener = listener
        }

        fun report(id: Int, frame: Int, used: Boolean) {
            checkNotNull(listener) { "nothing is listening for the interface's reports" }
                .onReport(PointerId(id), InputFrame(frame), used)
        }
    }

    private companion object {
        /** Kool's own id for the mouse, `PointerInput.MOUSE_POINTER_ID`. */
        const val MOUSE = -1000000
        const val FINGER_A = 0
        const val FINGER_B = 1

        /** Kool's button masks and indices. */
        const val LEFT = 1
        const val RIGHT = 2
        const val LEFT_BUTTON = 0
        const val RIGHT_BUTTON = 1
    }
}
