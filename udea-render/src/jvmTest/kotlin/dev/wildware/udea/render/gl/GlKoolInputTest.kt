package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import de.fabmax.kool.input.KeyboardInput
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.InputBindings
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.KeyStroke
import dev.wildware.udea.render.input.UiInput
import dev.wildware.udea.render.kool.KoolKeyboard
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.ui.UiScreen
import org.lwjgl.glfw.GLFW
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Issue #224's first acceptance criterion, through the real Kool and the real toolkit: **a key
 * becomes an intent, and a key the interface takes does not.**
 *
 * `KoolKeyboardOrderTest` drives the same ordering with hand-built events and a fake interface, so
 * it says nothing about whether Kool delivers to `KoolKeyboard` at all, whether a real ComposeGL
 * screen ever answers `true`, or what code Kool derives from a physical key. This does all three,
 * over one context: a raw GLFW key goes into Kool's own GLFW key callback - the entry point a real
 * keyboard uses - and travels `GlfwInput`'s key table, Kool's `InputStack`, `KoolKeyboard`,
 * `UiLayer`, the toolkit's `KeyRouter`, and out into an `Intent`.
 *
 * ## The third assertion is about a thread, and it is the one that will catch somebody
 *
 * The toolkit is not thread-safe and does not marshal: `UiLayer.onKey` reaches `KeyRouter` and
 * `FocusManager` directly, so it must run on the thread that draws. On this host it does, because
 * `KoolThread.config()` sets `asyncSceneUpdate = false` and Kool then polls input, runs the frame
 * callbacks and draws the scenes inline on one thread. That is one line away from being untrue, the
 * KDoc explaining why it is off is in a different file from the line that would turn it on, and
 * nothing else here would notice. So the three threads are recorded where they actually are - inside
 * the input callback, inside the composition, and inside a frame callback - and compared.
 */
class GlKoolInputTest {

    @Test
    fun `a Kool key becomes an intent, and one the interface takes does not`() {
        GlAvailability.require()

        val registry = RenderRegistry()
        val frames = FrameProbe()
        registry.overlay({ frames })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-kool-input",
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
        val screen = EscapeScreen()
        val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
        val watcher = ThreadWatchingUi(ui)
        val bindings = InputBindings(
            actions = listOf(
                ActionBinding(name = "test/walk", keys = intArrayOf(W)),
                ActionBinding(name = "test/menu", keys = intArrayOf(ESCAPE)),
            ),
            axes = emptyList(),
        )
        val walk = bindings.catalog.action("test/walk")
        val menu = bindings.catalog.action("test/menu")

        try {
            val host = GameHost(
                RenderMode.Offscreen,
                UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()),
                backend,
            )
            backend.drive(host)
            backend.show(ui)
            ui.show(screen)

            // Built where it is used: it joins Kool's input stack, and the counters it keeps are
            // written by Kool's poll and read by the sampler, both on this thread.
            val keyboard = backend.onRenderThread { KoolKeyboard(watcher) }
            val source = backend.onRenderThread { DeviceIntent(bindings, keyboard) }

            // A frame so the screen is composed, laid out and focused before any key arrives.
            awaitFrames(frames, frames.count.get() + 3)

            // 1. A key the interface does not want reaches the simulation as an intent.
            backend.press(GLFW.GLFW_KEY_W)
            awaitFrames(frames, frames.count.get() + 2)
            val walking = backend.onRenderThread { sample(source, bindings) }

            assertTrue(
                walking.isPressed(walk),
                "W never became an intent. The binding is on $W; the codes Kool derived from the " +
                    "physical keys pressed were ${watcher.codes}. This is where a game's controls " +
                    "asset stops matching the keyboard, and it is silent everywhere else.",
            )
            assertEquals(1, walking.pressCount(walk), "W's press edge was lost between Kool and the intent")

            // The key table, measured rather than assumed. A binding is an int in a `.udea.kts`, and
            // the whole point of driving Kool's own GLFW callback above is that this number is Kool's
            // answer and not this test's premise.
            assertEquals(
                'W'.code,
                W,
                "GLFW numbers a letter key by its ASCII *uppercase*; that is the number a controls " +
                    "asset has to carry",
            )
            assertTrue(
                keyboard.isKeyDown(W),
                "the physical W key came out of Kool as something other than $W: ${watcher.codes}",
            )
            assertFalse(
                keyboard.isKeyDown('w'.code),
                "the physical W key came out as the *lowercase* code ${'w'.code}. That is what " +
                    "`UniversalKeyCode(Char)`'s convenience constructor would produce, and GLFW " +
                    "never calls it - `GlfwInput` passes the raw GLFW key. A controls asset written " +
                    "in lowercase codes would bind the wrong keys silently.",
            )

            // 2. A key the interface takes never does.
            backend.release(GLFW.GLFW_KEY_W)
            backend.press(GLFW.GLFW_KEY_ESCAPE)
            awaitFrames(frames, frames.count.get() + 2)
            val closing = backend.onRenderThread { sample(source, bindings) }

            assertTrue(screen.escapes.get() > 0, "the panel never saw Escape, so nothing was refused")
            assertFalse(closing.isPressed(menu), "Escape closed the panel AND fired the binding under it")
            assertEquals(0, closing.pressCount(menu), "Escape closed the panel AND fired the binding under it")

            // 3. ...all of it on the one thread the toolkit may be touched on.
            val input = checkNotNull(watcher.thread.get()) { "the interface was never asked about a key" }
            val composing = checkNotNull(screen.thread.get()) { "the screen was never composed" }
            val frame = checkNotNull(frames.thread.get()) { "no frame ever ran" }
            assertSame(
                composing,
                input,
                "Kool delivered a key on $input while the toolkit draws on $composing. UiLayer.onKey " +
                    "walks KeyRouter and FocusManager, neither of which is thread-safe, so this is a " +
                    "race rather than a naming difference. Check KoolThread.config(): it sets " +
                    "asyncSceneUpdate = false, and that is what makes Kool poll input, run the frame " +
                    "callbacks and draw on one thread.",
            )
            assertSame(
                composing,
                frame,
                "the simulation ticks on $frame and the toolkit draws on $composing; same cause as above",
            )

            backend.onRenderThread { keyboard.close() }
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /** One tick's worth of sampling, on the render thread, into a fresh intent the caller reads. */
    private fun sample(source: DeviceIntent, bindings: InputBindings): Intent =
        Intent(bindings.catalog).also { source.sample(it) }

    private fun awaitFrames(probe: FrameProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.count.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.count.get() >= target, "the render thread stopped drawing")
    }

    /**
     * Presses [glfwKey] on Kool's own GLFW key callback, as a keyboard would.
     *
     * The point of going this far round rather than calling `KeyboardInput.handleKeyEvent` with an
     * event this test built: an event this test builds carries whatever code this test chose, so a
     * binding on the same code passes whatever the number is, and the *key table* - the thing a
     * game's controls asset is written in - would never be measured at all. Here the raw GLFW key
     * goes in and Kool decides the code, in `GlfwInput`'s `KEY_CODE_MAP[key] ?: UniversalKeyCode(key)`.
     *
     * GLFW has no getter for a callback, only a setter that returns the previous one, so it is taken
     * off and put straight back. Render thread only: it is the thread that owns the window, and GLFW
     * requires its own.
     */
    private fun KoolBackend.press(glfwKey: Int) = key(glfwKey, GLFW.GLFW_PRESS)

    private fun KoolBackend.release(glfwKey: Int) = key(glfwKey, GLFW.GLFW_RELEASE)

    private fun KoolBackend.key(glfwKey: Int, action: Int) = onRenderThread {
        val window = GLFW.glfwGetCurrentContext()
        check(window != 0L) { "no GLFW window is current on the render thread" }
        val callback = checkNotNull(GLFW.glfwSetKeyCallback(window, null)) {
            "Kool installed no GLFW key callback, so this test would be driving nothing"
        }
        try {
            callback.invoke(window, glfwKey, NO_SCANCODE, action, NO_MODIFIERS)
        } finally {
            GLFW.glfwSetKeyCallback(window, callback)
        }
    }

    /** Counts frames and remembers the thread one ran on. */
    private class FrameProbe : OverlaySystem {

        val count: AtomicInteger = AtomicInteger()

        val thread: AtomicReference<Thread?> = AtomicReference(null)

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            thread.compareAndSet(null, Thread.currentThread())
            count.incrementAndGet()
        }
    }

    /**
     * The real layer, with a note of which thread it was asked on and which codes it was offered.
     *
     * [codes] is not decoration: when a key-table assertion fails, the useful thing to print is the
     * number Kool actually produced, and this is the only place it is visible.
     */
    private class ThreadWatchingUi(private val delegate: UiInput) : UiInput {

        val thread: AtomicReference<Thread?> = AtomicReference(null)

        /** Every code offered, in order. Written and read on the render thread. */
        val codes: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()

        override fun onKey(event: KeyStroke): Boolean {
            thread.compareAndSet(null, Thread.currentThread())
            codes += event.keycode
            return delegate.onKey(event)
        }
    }

    /**
     * A panel that takes Escape and nothing else, with a focused button inside it.
     *
     * The button is not decoration: a key starts at the focused node and walks outwards, so a
     * handler on a panel with nothing focused inside it is never asked. That is `KeyRouter`'s
     * documented shape and it is what made this the realistic screen in the test this one replaces.
     */
    private class EscapeScreen : UiScreen {

        val escapes: AtomicInteger = AtomicInteger()

        val thread: AtomicReference<Thread?> = AtomicReference(null)

        @Composable
        override fun content() {
            thread.compareAndSet(null, Thread.currentThread())
            Box(
                Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.key == Key.Escape) {
                            escapes.incrementAndGet()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                Button(
                    "RESUME",
                    {},
                    Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT),
                    initialFocus = true,
                )
            }
        }
    }

    private companion object {

        const val WIDTH = 320
        const val HEIGHT = 240

        const val BUTTON_WIDTH = 160f
        const val BUTTON_HEIGHT = 40f

        /** The toolkit's own `DEFAULT_FAMILY`: a `Button`'s label is styled from the skin, not here. */
        const val FONT = "default"

        /** The size the default button style asks for. A style whose size is not registered throws. */
        const val FONT_SIZE = 16

        /** No scancode and no modifiers: GLFW passes both, and Kool's mapping reads neither. */
        const val NO_SCANCODE = 0
        const val NO_MODIFIERS = 0

        /**
         * Escape is one of the 41 special keys `GlfwInput.KEY_CODE_MAP` renames, so its code is
         * Kool's own `-9` rather than GLFW's `256`. A binding on 256 would never fire, which is why
         * this reads Kool's constant instead of GLFW's.
         */
        val ESCAPE = KeyboardInput.KEY_ESC.code

        /** W is not in that map, so its code is the raw GLFW key: `GLFW_KEY_W`, which is `'W'.code`. */
        val W = GLFW.GLFW_KEY_W
    }
}
