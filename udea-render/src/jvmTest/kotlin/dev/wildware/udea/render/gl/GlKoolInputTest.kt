package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.udea.assets.InputKey
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderRegistry
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
                ActionBinding(name = "test/walk", keys = listOf(InputKey.W)),
                ActionBinding(name = "test/menu", keys = listOf(InputKey.Escape)),
            ) + PRINTABLE.map { ActionBinding(name = it.action, keys = listOf(it.input)) },
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
                "W never became an intent. The binding is on InputKey.W; the keys the table read " +
                    "from the physical keys pressed were ${watcher.keys}. This is where a game's " +
                    "controls asset stops matching the keyboard, and it is silent everywhere else.",
            )
            assertEquals(1, walking.pressCount(walk), "W's press edge was lost between Kool and the intent")

            // Which *name* each physical key arrives as, for every key, is `GlKoolKeyTableTest`'s.
            assertTrue(keyboard.isKeyDown(InputKey.W), "W was pressed and is not held: ${watcher.keys}")

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

            // 4. Every key the toolkit names, pressed on Kool's own GLFW callback into a focused
            //    control that takes whatever it recognises (issue #230). The code is Kool's; the key
            //    the control saw is the table's answer to it; the intent is the game's.
            backend.release(GLFW.GLFW_KEY_ESCAPE)
            val recorder = KeyRecordingScreen()
            ui.show(recorder)
            awaitFrames(frames, frames.count.get() + 3)
            backend.onRenderThread { sample(source, bindings) } // spends anything left from above

            val wrong = mutableListOf<String>()
            for (case in PRINTABLE + SPECIAL) {
                backend.onRenderThread { recorder.downs.clear() }
                backend.press(case.glfw)
                backend.release(case.glfw)
                awaitFrames(frames, frames.count.get() + 2)
                val seen = backend.onRenderThread { recorder.downs.toList() }
                val typed = backend.onRenderThread { sample(source, bindings) }
                if (seen != listOf(case.key)) {
                    wrong += "${case.name}: GLFW key ${case.glfw} reached the focused control as $seen, " +
                        "not [${case.key}]"
                }
                if (case in PRINTABLE && typed.pressCount(bindings.catalog.action(case.action)) != 0) {
                    wrong += "${case.name}: the focused control was offered it and it became an intent anyway"
                }
            }
            assertTrue(
                wrong.isEmpty(),
                "A key pressed into a focused control must arrive as the key the control recognises, " +
                    "and nothing the control takes may become an intent. If only letters are wrong " +
                    "after a Kool upgrade, look first at `UniversalKeyCode(Char)`: Kool 0.19.0 " +
                    "uppercases the character and Kool main lowercases it (KeyCode.kt line 16), while " +
                    "GLFW sends a letter as its ASCII uppercase in both. The table must be keyed on " +
                    "what GLFW sends, never on that constructor.\n" + wrong.joinToString("\n"),
            )

            // 5. Typing into a focused text field is typing, not playing (issue #230). GLFW reports
            //    a letter twice - the key, then the character it typed - and the real `TextField`
            //    takes only the character. The key must not become an intent either.
            val field = TextFieldScreen()
            ui.show(field)
            awaitFrames(frames, frames.count.get() + 3)
            backend.onRenderThread { sample(source, bindings) }
            for (case in LETTERS) backend.type(case)
            awaitFrames(frames, frames.count.get() + 3)
            val typing = backend.onRenderThread { sample(source, bindings) }
            assertEquals(
                LETTERS.joinToString("") { it.typed.toString() },
                field.text.get(),
                "the text field did not receive the letters typed into it, so nothing below says " +
                    "anything about typing",
            )
            val moved = LETTERS.filter { typing.pressCount(bindings.catalog.action(it.action)) != 0 }
            assertTrue(
                moved.isEmpty(),
                "typing into a focused text field also pressed $moved for the game: a player writing " +
                    "their name would walk while doing it",
            )
            assertTrue(
                LETTERS.none { keyboard.isKeyDown(it.input) },
                "a letter typed into the text field is still held down for the game",
            )

            // 6. ...but a focused control that does not take text leaves every letter with the game.
            //    The rule follows the interface's answer, so a focused button swallows nothing.
            ui.show(screen)
            awaitFrames(frames, frames.count.get() + 3)
            backend.onRenderThread { sample(source, bindings) }
            for (case in LETTERS) backend.type(case)
            awaitFrames(frames, frames.count.get() + 3)
            val buttoned = backend.onRenderThread { sample(source, bindings) }
            val swallowed = LETTERS.filter { buttoned.pressCount(bindings.catalog.action(it.action)) != 1 }
            assertTrue(
                swallowed.isEmpty(),
                "with a button focused, ${swallowed.map { it.name }} did not become exactly one intent each",
            )

            // 7. ...and with nothing shown, every letter is the game's.
            ui.show(null)
            awaitFrames(frames, frames.count.get() + 3)
            for (case in LETTERS) backend.type(case)
            awaitFrames(frames, frames.count.get() + 3)
            val unfocused = backend.onRenderThread { sample(source, bindings) }
            val lost = LETTERS.filter { unfocused.pressCount(bindings.catalog.action(it.action)) != 1 }
            assertTrue(
                lost.isEmpty(),
                "with no interface shown, ${lost.map { it.name }} did not become exactly one intent each",
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

    /**
     * Types [case]'s letter the way GLFW reports a real keystroke: the key callback and then the
     * character callback from the same poll, so Kool queues them together, and the release after.
     */
    private fun KoolBackend.type(case: Case) {
        onRenderThread {
            val window = GLFW.glfwGetCurrentContext()
            check(window != 0L) { "no GLFW window is current on the render thread" }
            invokeKeyCallback(window, case.glfw, GLFW.GLFW_PRESS)
            val chars = checkNotNull(GLFW.glfwSetCharCallback(window, null)) {
                "Kool installed no GLFW character callback, so this test would be typing into nothing"
            }
            try {
                chars.invoke(window, case.typed.code)
            } finally {
                GLFW.glfwSetCharCallback(window, chars)
            }
        }
        release(case.glfw)
    }

    /**
     * The real layer, with a note of which thread it was asked on and which keys it was offered.
     *
     * [keys] is not decoration: when an assertion about a key fails, the useful thing to print is the
     * key the table actually read, and this is the only place it is visible.
     */
    private class ThreadWatchingUi(private val delegate: UiInput) : UiInput {

        val thread: AtomicReference<Thread?> = AtomicReference(null)

        /** Every key offered, in order, as the table named it. Written and read on the render thread. */
        val keys: MutableList<InputKey?> = java.util.concurrent.CopyOnWriteArrayList()

        override fun onKey(event: KeyStroke): Boolean {
            thread.compareAndSet(null, Thread.currentThread())
            keys += event.key
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

    /**
     * A panel that takes every key the toolkit recognises, with a focused button inside it - the
     * shape of a key-binding capture, or a menu with letter shortcuts.
     *
     * It takes anything but [Key.Unknown], so a key the table fails to name is *declined* and falls
     * through to the game: exactly issue #230, where a focused control never took a letter and the
     * letter became an intent.
     */
    private class KeyRecordingScreen : UiScreen {

        /** The toolkit key of every key-down offered, in order. Render thread only. */
        val downs: MutableList<Key> = mutableListOf()

        @Composable
        override fun content() {
            Box(
                Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.Down) downs += event.key
                        event.key != Key.Unknown
                    },
            ) {
                Button(
                    "BIND",
                    {},
                    Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT),
                    initialFocus = true,
                )
            }
        }
    }

    /**
     * One physical key: the constant GLFW hands Kool's callback, and the toolkit key it must become.
     * Written out by name on both sides, so the expectation shares nothing with the table under test.
     */
    private class Case(val name: String, val glfw: Int, val key: Key) {

        /** The name a game binds a [PRINTABLE] key by. */
        val input: InputKey get() = INPUT_NAMES.getValue(name)

        /** The binding a [PRINTABLE] key fires when the interface declines it. */
        val action: String = "test/key-$name"

        /** The character GLFW's character callback reports for a [LETTERS] key with no shift held. */
        val typed: Char get() = name.single().lowercaseChar()

        override fun toString(): String = name
    }

    /** A real `TextField`, focused, and what it holds - written by the toolkit on the render thread. */
    private class TextFieldScreen : UiScreen {

        val text: AtomicReference<String> = AtomicReference("")

        @Composable
        override fun content() {
            var value by remember { mutableStateOf("") }
            TextField(
                value,
                {
                    value = it
                    text.set(it)
                },
                Modifier.size(BUTTON_WIDTH, BUTTON_HEIGHT),
                initialFocus = true,
            )
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

        /** The [InputKey] each [PRINTABLE] case's name stands for. */
        val INPUT_NAMES: Map<String, InputKey> =
            ('A'..'Z').associate { it.toString() to InputKey.valueOf(it.toString()) } +
                ('0'..'9').associate { it.toString() to InputKey.valueOf("Digit$it") } +
                mapOf(
                    "Space" to InputKey.Space, "Minus" to InputKey.Minus, "Equal" to InputKey.Equals,
                    "LeftBracket" to InputKey.LeftBracket, "RightBracket" to InputKey.RightBracket,
                    "Backslash" to InputKey.Backslash, "Semicolon" to InputKey.Semicolon,
                    "Apostrophe" to InputKey.Apostrophe, "Grave" to InputKey.Grave,
                    "Comma" to InputKey.Comma, "Period" to InputKey.Period, "Slash" to InputKey.Slash,
                )

        /** Every letter, because the failure in issue #230 was per key. */
        val LETTERS = listOf(
            Case("A", GLFW.GLFW_KEY_A, Key.A), Case("B", GLFW.GLFW_KEY_B, Key.B),
            Case("C", GLFW.GLFW_KEY_C, Key.C), Case("D", GLFW.GLFW_KEY_D, Key.D),
            Case("E", GLFW.GLFW_KEY_E, Key.E), Case("F", GLFW.GLFW_KEY_F, Key.F),
            Case("G", GLFW.GLFW_KEY_G, Key.G), Case("H", GLFW.GLFW_KEY_H, Key.H),
            Case("I", GLFW.GLFW_KEY_I, Key.I), Case("J", GLFW.GLFW_KEY_J, Key.J),
            Case("K", GLFW.GLFW_KEY_K, Key.K), Case("L", GLFW.GLFW_KEY_L, Key.L),
            Case("M", GLFW.GLFW_KEY_M, Key.M), Case("N", GLFW.GLFW_KEY_N, Key.N),
            Case("O", GLFW.GLFW_KEY_O, Key.O), Case("P", GLFW.GLFW_KEY_P, Key.P),
            Case("Q", GLFW.GLFW_KEY_Q, Key.Q), Case("R", GLFW.GLFW_KEY_R, Key.R),
            Case("S", GLFW.GLFW_KEY_S, Key.S), Case("T", GLFW.GLFW_KEY_T, Key.T),
            Case("U", GLFW.GLFW_KEY_U, Key.U), Case("V", GLFW.GLFW_KEY_V, Key.V),
            Case("W", GLFW.GLFW_KEY_W, Key.W), Case("X", GLFW.GLFW_KEY_X, Key.X),
            Case("Y", GLFW.GLFW_KEY_Y, Key.Y), Case("Z", GLFW.GLFW_KEY_Z, Key.Z),
        )

        /**
         * The keys Kool reports by their raw GLFW constant, because none of them is in
         * `GlfwInput`'s map - so a binding names them by that constant.
         */
        val PRINTABLE = LETTERS + listOf(
            Case("0", GLFW.GLFW_KEY_0, Key.Digit0), Case("1", GLFW.GLFW_KEY_1, Key.Digit1),
            Case("2", GLFW.GLFW_KEY_2, Key.Digit2), Case("3", GLFW.GLFW_KEY_3, Key.Digit3),
            Case("4", GLFW.GLFW_KEY_4, Key.Digit4), Case("5", GLFW.GLFW_KEY_5, Key.Digit5),
            Case("6", GLFW.GLFW_KEY_6, Key.Digit6), Case("7", GLFW.GLFW_KEY_7, Key.Digit7),
            Case("8", GLFW.GLFW_KEY_8, Key.Digit8), Case("9", GLFW.GLFW_KEY_9, Key.Digit9),
            Case("Space", GLFW.GLFW_KEY_SPACE, Key.Space),
            Case("Minus", GLFW.GLFW_KEY_MINUS, Key.Minus),
            Case("Equal", GLFW.GLFW_KEY_EQUAL, Key.Equals),
            Case("LeftBracket", GLFW.GLFW_KEY_LEFT_BRACKET, Key.LeftBracket),
            Case("RightBracket", GLFW.GLFW_KEY_RIGHT_BRACKET, Key.RightBracket),
            Case("Backslash", GLFW.GLFW_KEY_BACKSLASH, Key.Backslash),
            Case("Semicolon", GLFW.GLFW_KEY_SEMICOLON, Key.Semicolon),
            Case("Apostrophe", GLFW.GLFW_KEY_APOSTROPHE, Key.Apostrophe),
            Case("Grave", GLFW.GLFW_KEY_GRAVE_ACCENT, Key.Grave),
            Case("Comma", GLFW.GLFW_KEY_COMMA, Key.Comma),
            Case("Period", GLFW.GLFW_KEY_PERIOD, Key.Period),
            Case("Slash", GLFW.GLFW_KEY_SLASH, Key.Slash),
        )

        /** The keys Kool renames to its own negative codes, which the table maps by Kool's constants. */
        val SPECIAL = listOf(
            Case("F1", GLFW.GLFW_KEY_F1, Key.F1), Case("F2", GLFW.GLFW_KEY_F2, Key.F2),
            Case("F3", GLFW.GLFW_KEY_F3, Key.F3), Case("F4", GLFW.GLFW_KEY_F4, Key.F4),
            Case("F5", GLFW.GLFW_KEY_F5, Key.F5), Case("F6", GLFW.GLFW_KEY_F6, Key.F6),
            Case("F7", GLFW.GLFW_KEY_F7, Key.F7), Case("F8", GLFW.GLFW_KEY_F8, Key.F8),
            Case("F9", GLFW.GLFW_KEY_F9, Key.F9), Case("F10", GLFW.GLFW_KEY_F10, Key.F10),
            Case("F11", GLFW.GLFW_KEY_F11, Key.F11), Case("F12", GLFW.GLFW_KEY_F12, Key.F12),
            Case("Left", GLFW.GLFW_KEY_LEFT, Key.Left), Case("Right", GLFW.GLFW_KEY_RIGHT, Key.Right),
            Case("Up", GLFW.GLFW_KEY_UP, Key.Up), Case("Down", GLFW.GLFW_KEY_DOWN, Key.Down),
            Case("Home", GLFW.GLFW_KEY_HOME, Key.Home), Case("End", GLFW.GLFW_KEY_END, Key.End),
            Case("PageUp", GLFW.GLFW_KEY_PAGE_UP, Key.PageUp),
            Case("PageDown", GLFW.GLFW_KEY_PAGE_DOWN, Key.PageDown),
            Case("Enter", GLFW.GLFW_KEY_ENTER, Key.Enter),
            Case("KeypadEnter", GLFW.GLFW_KEY_KP_ENTER, Key.Enter),
            Case("Escape", GLFW.GLFW_KEY_ESCAPE, Key.Escape),
            Case("Tab", GLFW.GLFW_KEY_TAB, Key.Tab),
            Case("Backspace", GLFW.GLFW_KEY_BACKSPACE, Key.Backspace),
            Case("Delete", GLFW.GLFW_KEY_DELETE, Key.Delete),
            Case("Insert", GLFW.GLFW_KEY_INSERT, Key.Insert),
            Case("LeftShift", GLFW.GLFW_KEY_LEFT_SHIFT, Key.Shift),
            Case("RightShift", GLFW.GLFW_KEY_RIGHT_SHIFT, Key.Shift),
            Case("LeftControl", GLFW.GLFW_KEY_LEFT_CONTROL, Key.Control),
            Case("RightControl", GLFW.GLFW_KEY_RIGHT_CONTROL, Key.Control),
            Case("LeftAlt", GLFW.GLFW_KEY_LEFT_ALT, Key.Alt),
            Case("RightAlt", GLFW.GLFW_KEY_RIGHT_ALT, Key.Alt),
            Case("LeftSuper", GLFW.GLFW_KEY_LEFT_SUPER, Key.Meta),
            Case("RightSuper", GLFW.GLFW_KEY_RIGHT_SUPER, Key.Meta),
        )
    }
}
