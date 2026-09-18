package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import de.fabmax.kool.input.PointerInput
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.ActionId
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.InputBindings
import dev.wildware.udea.render.input.InputModule
import dev.wildware.udea.render.input.IntentSampleSystem
import dev.wildware.udea.render.input.IntentState
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.ui.UiScreen
import org.lwjgl.glfw.GLFW
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #227 through the real Kool, the real toolkit and the real tick: **a click becomes an intent,
 * and a click on a ComposeGL control never does.**
 *
 * `KoolPointerOrderTest` drives the reconciliation with frame numbers and verdicts it chose, so it says
 * nothing about whether Kool delivers to `KoolPointer`, whether a real button ever reports a click as
 * used, or whether the verdict pairs with the right frame when the frame numbers are Kool's. This does
 * all three: raw GLFW mouse events go into Kool's own GLFW callbacks - the entry points a real mouse
 * uses - and travel Kool's `PointerInput`, its `InputStack`, `KoolPointer`, the ComposeGL scene's
 * pointer router and its `onPointerUsed` verdict, `UiLayer`, `DeviceIntent`, and out through
 * `InputModule`'s `IntentSampleSystem` into a tick, where a system of this test's reads the `Intent`
 * the way a game's control system would.
 *
 * Every step that expects no intent has a twin that expects one, because "the game saw nothing" is also
 * what a pointer nobody wired looks like: the click on the scene (1) is what makes the click on the
 * button (2) mean anything, and the click where the button *was* once no screen is shown (6) is what
 * makes it the button, rather than that spot, that took the click.
 *
 * The threads are not asserted here. Kool's pointer listener runs in the same `InputStack` pass as its
 * key listener, and the verdict in the same scene render as the composition, so `GlKoolInputTest`'s
 * assertion that input, composition and the frame callback share one thread is this path's guard too.
 */
class GlKoolPointerTest {

    @Test
    fun `a click becomes an intent, and a click on a ComposeGL button does not`() {
        GlAvailability.require()

        val registry = RenderRegistry()
        val frames = FrameProbe()
        registry.overlay({ frames })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-kool-pointer",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        val fonts = DesktopFonts()
        fonts.register(
            FONT,
            checkNotNull(javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
                "the test font is missing from udea-render's jvmTest resources"
            }.readBytes(),
            listOf(FONT_SIZE),
        )
        val screen = ButtonScreen()
        val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
        val bindings = InputBindings(
            actions = listOf(
                ActionBinding(name = "test/fire", pointerButtons = intArrayOf(PointerInput.LEFT_BUTTON)),
                ActionBinding(name = "test/aim", pointerButtons = intArrayOf(PointerInput.RIGHT_BUTTON)),
            ),
            axes = emptyList(),
        )
        val fire = bindings.catalog.action("test/fire")
        val aim = bindings.catalog.action("test/aim")
        val input = InputModule(bindings)
        val recorder = IntentRecorder(input.state, bindings.catalog.actionCount)

        try {
            val host = GameHost(
                RenderMode.Offscreen,
                UdeaGameDef(registry = CoreUdeaRegistry, modules = listOf(input, recorder)),
                backend,
            )
            backend.drive(host)
            backend.show(ui)
            ui.show(screen)

            // Built on the render thread: it joins Kool's input stack, and hears the scene's verdicts
            // there. Then wired exactly as a game wires it - the source the tick samples.
            val pointer = backend.onRenderThread { KoolPointer(ui) }
            input.state.source = DeviceIntent(bindings, pointer = pointer)

            awaitFrames(frames, frames.count.get() + 3)
            backend.moveTo(SCENE_X, SCENE_Y)
            settle(backend, frames, recorder)

            // 1. A click on the scene reaches the simulation as an intent - left and right both.
            backend.onRenderThread { recorder.reset() }
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
            settle(backend, frames, recorder)
            val pressedOnScene = backend.onRenderThread { recorder.isHeld(fire) }
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            settle(backend, frames, recorder)
            backend.button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS)
            settle(backend, frames, recorder)
            backend.button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE)
            settle(backend, frames, recorder)
            val onScene = backend.onRenderThread { recorder.snapshot() }

            assertTrue(pressedOnScene, "a mouse button held down on the scene was never held in any tick's intent")
            assertEquals(1, onScene.presses(fire), "a left click on the scene did not become exactly one intent: $onScene")
            assertFalse(onScene.heldNow(fire), "the left button stayed held in the intent after it was released")
            assertEquals(1, onScene.presses(aim), "a right click on the scene did not become exactly one intent: $onScene")

            // 2. A click on a ComposeGL button is the button's, and never becomes an intent.
            backend.moveTo(BUTTON_X, BUTTON_Y)
            settle(backend, frames, recorder)
            backend.onRenderThread { recorder.reset() }
            val clicksBefore = screen.clicks.get()
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
            settle(backend, frames, recorder)
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            settle(backend, frames, recorder)
            val onButton = backend.onRenderThread { recorder.snapshot() }

            assertEquals(
                clicksBefore + 1,
                screen.clicks.get(),
                "the button never saw the click, so nothing below says anything about the interface taking it",
            )
            assertEquals(0, onButton.presses(fire), "a click on a button also fired the binding underneath: $onButton")
            assertFalse(onButton.everHeld(fire), "a click on a button held the binding underneath in some tick: $onButton")

            // 3. Pressed on the button, dragged onto the scene, released there: still the button's.
            backend.onRenderThread { recorder.reset() }
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
            settle(backend, frames, recorder)
            backend.moveTo(SCENE_X, SCENE_Y)
            settle(backend, frames, recorder)
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            settle(backend, frames, recorder)
            val draggedOff = backend.onRenderThread { recorder.snapshot() }

            assertEquals(0, draggedOff.presses(fire), "a press that began on the button became the game's once it left: $draggedOff")
            assertFalse(draggedOff.everHeld(fire), "a press that began on the button was held for the game once it left: $draggedOff")

            // 4. Pressed on the scene, dragged onto the button, released there: the game's press, and
            //    the game's release - a release taken away would leave the button stuck down.
            backend.onRenderThread { recorder.reset() }
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
            settle(backend, frames, recorder)
            backend.moveTo(BUTTON_X, BUTTON_Y)
            settle(backend, frames, recorder)
            val heldOverButton = backend.onRenderThread { recorder.isHeld(fire) }
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            settle(backend, frames, recorder)
            val draggedOn = backend.onRenderThread { recorder.snapshot() }

            assertEquals(1, draggedOn.presses(fire), "a press on the scene was lost once dragged onto the button: $draggedOn")
            assertTrue(heldOverButton, "dragging a held button over the interface let go of it for the game")
            assertFalse(draggedOn.heldNow(fire), "released over the button, the game's button stayed held down: $draggedOn")

            // 5. Pressed on the scene and the mouse leaves the window: Kool drops the pointer, and the
            //    game lets go of what it held. Then it comes back with the button up, and that is not a
            //    click: Kool lists the returning mouse with the button it left holding marked as changed.
            backend.moveTo(SCENE_X, SCENE_Y)
            settle(backend, frames, recorder)
            backend.onRenderThread { recorder.reset() }
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
            settle(backend, frames, recorder)
            val heldBeforeLeaving = backend.onRenderThread { recorder.isHeld(fire) }
            backend.leaveWindow()
            settle(backend, frames, recorder)
            val left = backend.onRenderThread { recorder.snapshot() }
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            backend.moveTo(SCENE_X, SCENE_Y)
            settle(backend, frames, recorder)
            val back = backend.onRenderThread { recorder.snapshot() }

            assertTrue(heldBeforeLeaving, "the press on the scene before leaving was never held")
            assertFalse(left.heldNow(fire), "the mouse left the window and the game still holds its button: $left")
            assertEquals(1, back.presses(fire), "the mouse coming back into the window pressed a button nobody pressed: $back")
            assertFalse(back.heldNow(fire), "the mouse came back with its button up and the game holds it: $back")

            // 6. With no screen shown, the same click where the button was is the game's.
            ui.show(null)
            backend.moveTo(BUTTON_X, BUTTON_Y)
            settle(backend, frames, recorder)
            backend.onRenderThread { recorder.reset() }
            val clicksHidden = screen.clicks.get()
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
            settle(backend, frames, recorder)
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            settle(backend, frames, recorder)
            val noScreen = backend.onRenderThread { recorder.snapshot() }

            assertEquals(clicksHidden, screen.clicks.get(), "a hidden button still took a click")
            assertEquals(1, noScreen.presses(fire), "with no screen shown, a click did not become exactly one intent: $noScreen")

            backend.onRenderThread { pointer.close() }
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun awaitFrames(probe: FrameProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.count.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.count.get() >= target, "the render thread stopped drawing")
    }

    /**
     * Long enough for a GLFW event to be read by Kool's poll, judged by the scene's render, and then
     * sampled by at least two ticks: Kool hands its listeners the pointer state of the poll before, the
     * verdict arrives at the end of the frame it was read in, and the tick after that is the first that
     * can see it.
     */
    private fun settle(backend: KoolBackend, frames: FrameProbe, recorder: IntentRecorder) {
        awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
        val ticks = backend.onRenderThread { recorder.ticks }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (backend.onRenderThread { recorder.ticks } < ticks + SETTLE_TICKS && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(backend.onRenderThread { recorder.ticks } >= ticks + SETTLE_TICKS, "the simulation stopped ticking")
    }

    /**
     * Moves the mouse on Kool's own GLFW cursor callback, as a real mouse would.
     *
     * The same round trip `GlKoolInputTest` takes for keys, for the same reason: the position goes in
     * as GLFW's and Kool decides the pointer, so what reaches `KoolPointer` - and what the toolkit hit-
     * tests - is what Kool made of it rather than a value this test chose. GLFW has no getter for a
     * callback, only a setter that returns the previous one, so it is taken off and put straight back.
     * Render thread only: GLFW requires the thread that owns the window.
     *
     * Reported twice, as a real mouse reports a drag in many small moves. Kool holds back the position
     * of the move that *starts* a drag, so that the drag begins where the press was
     * (`BufferedPointerInput.movePointer`), and a single jump with a button held would leave Kool's
     * pointer - and so the toolkit's hit test - where it started. That is not hypothetical: with one
     * move, "dragged off the button and released on the scene" released on the button, and clicked it.
     */
    private fun KoolBackend.moveTo(x: Double, y: Double) = onRenderThread {
        val window = currentWindow()
        val callback = checkNotNull(GLFW.glfwSetCursorPosCallback(window, null)) {
            "Kool installed no GLFW cursor callback, so this test would be moving nothing"
        }
        try {
            callback.invoke(window, x, y)
            callback.invoke(window, x, y)
        } finally {
            GLFW.glfwSetCursorPosCallback(window, callback)
        }
    }

    /** Presses or releases a mouse button on Kool's own GLFW callback. */
    private fun KoolBackend.button(button: Int, action: Int) = onRenderThread {
        val window = currentWindow()
        val callback = checkNotNull(GLFW.glfwSetMouseButtonCallback(window, null)) {
            "Kool installed no GLFW mouse button callback, so this test would be clicking nothing"
        }
        try {
            callback.invoke(window, button, action, NO_MODIFIERS)
        } finally {
            GLFW.glfwSetMouseButtonCallback(window, callback)
        }
    }

    /** Tells Kool, on its own GLFW callback, that the cursor left the window. */
    private fun KoolBackend.leaveWindow() = onRenderThread {
        val window = currentWindow()
        val callback = checkNotNull(GLFW.glfwSetCursorEnterCallback(window, null)) {
            "Kool installed no GLFW cursor-enter callback, so this test would be leaving nothing"
        }
        try {
            callback.invoke(window, false)
        } finally {
            GLFW.glfwSetCursorEnterCallback(window, callback)
        }
    }

    private fun currentWindow(): Long {
        val window = GLFW.glfwGetCurrentContext()
        check(window != 0L) { "no GLFW window is current on the render thread" }
        return window
    }

    /** Counts frames. */
    private class FrameProbe : OverlaySystem {

        val count: AtomicInteger = AtomicInteger()

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            count.incrementAndGet()
        }
    }

    /**
     * A game's control system, as far as this test needs one: what every tick's [IntentState] said.
     *
     * A module so it is registered the way a game's would be, after `IntentSampleSystem` in
     * `SimPhase.Intent`, and so what it reads is the intent the simulation itself sampled. Its fields
     * are written by the tick and read by the test through `onRenderThread`, which is the tick's thread.
     */
    private class IntentRecorder(private val state: IntentState, actions: Int) : UdeaModule {

        override val name: String get() = "test/intent-recorder"

        var ticks: Long = 0L
            private set

        private val presses = IntArray(actions)

        private val everHeld = BooleanArray(actions)

        private val heldNow = BooleanArray(actions)

        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Intent, { Record(this) }) { after(IntentSampleSystem::class) }
        }

        fun reset() {
            presses.fill(0)
            everHeld.fill(false)
        }

        fun isHeld(action: ActionId): Boolean = heldNow[action.value]

        fun snapshot(): Seen = Seen(presses.copyOf(), everHeld.copyOf(), heldNow.copyOf())

        private fun record() {
            ticks++
            for (index in presses.indices) {
                val id = ActionId(index)
                presses[index] += state.intent.pressCount(id)
                heldNow[index] = state.intent.isPressed(id)
                if (heldNow[index]) everHeld[index] = true
            }
        }

        private class Record(private val recorder: IntentRecorder) : SimSystem() {
            override fun onTick() {
                recorder.record()
            }
        }
    }

    /** What the ticks since the last reset saw, copied on the tick's thread. */
    private class Seen(private val presses: IntArray, private val everHeld: BooleanArray, private val heldNow: BooleanArray) {

        fun presses(action: ActionId): Int = presses[action.value]

        fun everHeld(action: ActionId): Boolean = everHeld[action.value]

        fun heldNow(action: ActionId): Boolean = heldNow[action.value]

        override fun toString(): String =
            "presses=${presses.toList()}, everHeld=${everHeld.toList()}, heldNow=${heldNow.toList()}"
    }

    /** A button in the top-left corner and nothing else, counting the clicks it takes. */
    private class ButtonScreen : UiScreen {

        val clicks: AtomicInteger = AtomicInteger()

        @Composable
        override fun content() {
            Box(Modifier.fillMaxSize()) {
                Button("FIRE", { clicks.incrementAndGet() }, Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT))
            }
        }
    }

    private companion object {

        const val WIDTH = 320
        const val HEIGHT = 240

        /** The button is laid out at the top-left, at this size, and the design is the window's size. */
        const val BUTTON_WIDTH = 160f
        const val BUTTON_HEIGHT = 40f

        /** The middle of the button. */
        const val BUTTON_X = 80.0
        const val BUTTON_Y = 20.0

        /** Well clear of the button: scene, with nothing of the interface over it. */
        const val SCENE_X = 240.0
        const val SCENE_Y = 180.0

        /** The toolkit's own `DEFAULT_FAMILY`: a `Button`'s label is styled from the skin, not here. */
        const val FONT = "default"

        /** The size the default button style asks for. */
        const val FONT_SIZE = 16

        /** GLFW passes modifiers with a mouse button, and Kool's mapping reads none. */
        const val NO_MODIFIERS = 0

        /** Frames from a GLFW event to its verdict: one poll to buffer it, one to list it, one to judge it. */
        const val SETTLE_FRAMES = 4

        /** Ticks after that, so at least one tick samples what the verdict let through. */
        const val SETTLE_TICKS = 2L
    }
}
