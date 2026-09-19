package dev.wildware.udea.render.gl

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
import dev.wildware.udea.render.input.UiInput
import dev.wildware.udea.render.kool.KoolKeyboard
import org.lwjgl.glfw.GLFW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #228's guard on the desktop table: **every physical key becomes the binding named after it,
 * and no other.**
 *
 * A game binds [InputKey]s and `KoolKeyTable` owns the numbers, so the way this goes wrong is one
 * entry holding the wrong number - and it goes wrong silently, a key at a time, which is why every
 * key is pressed rather than a sample. One binding per [InputKey], all of them live at once; each
 * physical key goes in as GLFW's constant through **Kool's installed GLFW key callback**, so Kool and
 * not this test decides the code; and the intent that comes out must fire exactly the one binding
 * whose name the test wrote beside that GLFW constant.
 *
 * That shape is what makes a wrong table fail. An entry holding a number GLFW never sends fires
 * nothing for its key; an entry holding another key's number fires the wrong binding; two keys
 * swapped fail under both names. The last block is the negative from #224's lesson: numbers a wrong
 * table would plausibly hold - `'w'.code`, which is `UniversalKeyCode('W')` on Kool's `main` - and
 * real keys the engine does not name go in through the same callback and must fire nothing at all.
 *
 * Both sides of each case are written out by name, so the expectation shares nothing with the table.
 */
class GlKoolKeyTableTest {

    @Test
    fun `every physical key fires the binding named after it, and nothing else does`() {
        GlAvailability.require()
        assertEquals(
            InputKey.entries.toSet(),
            DESKTOP.map { it.second }.toSet(),
            "every named key must be pressed below, or the table can be wrong for it unnoticed",
        )
        assertEquals(DESKTOP.size, DESKTOP.map { it.second }.distinct().size, "a key is listed twice")

        val registry = RenderRegistry()
        val frames = FrameProbe()
        registry.overlay({ frames })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-kool-key-table",
                windowWidth = SIZE,
                windowHeight = SIZE,
                renderWidth = SIZE,
                renderHeight = SIZE,
            ),
            registry,
        )
        val bindings = InputBindings(
            actions = InputKey.entries.map { ActionBinding(name = actionName(it), keys = listOf(it)) },
            axes = emptyList(),
        )

        try {
            backend.drive(
                GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend),
            )
            // No interface: every key is the game's, so a key that fires nothing is the table's fault.
            val keyboard = backend.onRenderThread { KoolKeyboard(UiInput.NONE) }
            val source = backend.onRenderThread { DeviceIntent(bindings, keyboard) }
            awaitFrames(frames, frames.count.get() + 3)
            backend.onRenderThread { fired(source, bindings) }

            val wrong = mutableListOf<String>()
            for ((glfwKey, expected) in DESKTOP) {
                val fired = strike(backend, frames, source, bindings, glfwKey)
                if (fired != listOf(expected)) {
                    wrong += "$expected: GLFW key $glfwKey fired $fired"
                }
            }
            for ((glfwKey, what) in UNNAMED) {
                val fired = strike(backend, frames, source, bindings, glfwKey)
                if (fired.isNotEmpty()) wrong += "$what (GLFW $glfwKey) is no named key, and fired $fired"
            }
            assertTrue(
                wrong.isEmpty(),
                "Each physical key must fire exactly the binding named after it. The desktop table is " +
                    "`KoolKeyTable.jvm.kt`; a key GLFW passes through arrives as its GLFW constant, and a " +
                    "special key as Kool's own negative code (Escape is -9, not GLFW's 256):\n" +
                    wrong.joinToString("\n"),
            )

            backend.onRenderThread { keyboard.close() }
        } finally {
            backend.close()
        }
    }

    /** Presses and releases [glfwKey] on Kool's callback, and returns every binding that fired. */
    private fun strike(
        backend: KoolBackend,
        frames: FrameProbe,
        source: DeviceIntent,
        bindings: InputBindings,
        glfwKey: Int,
    ): List<InputKey> {
        backend.press(glfwKey)
        awaitFrames(frames, frames.count.get() + 2)
        backend.release(glfwKey)
        awaitFrames(frames, frames.count.get() + 2)
        return backend.onRenderThread { fired(source, bindings) }
    }

    /** One tick's sample: the keys whose binding counted a press. Render thread. */
    private fun fired(source: DeviceIntent, bindings: InputBindings): List<InputKey> {
        val intent = Intent(bindings.catalog).also { source.sample(it) }
        return InputKey.entries.filter { intent.pressCount(bindings.catalog.action(actionName(it))) > 0 }
    }

    private fun actionName(key: InputKey): String = "test/key-${key.name}"

    private companion object {

        const val SIZE = 64

        /** Each physical key by GLFW's constant, and the name it must arrive as. */
        val DESKTOP: List<Pair<Int, InputKey>> = listOf(
            GLFW.GLFW_KEY_A to InputKey.A, GLFW.GLFW_KEY_B to InputKey.B,
            GLFW.GLFW_KEY_C to InputKey.C, GLFW.GLFW_KEY_D to InputKey.D,
            GLFW.GLFW_KEY_E to InputKey.E, GLFW.GLFW_KEY_F to InputKey.F,
            GLFW.GLFW_KEY_G to InputKey.G, GLFW.GLFW_KEY_H to InputKey.H,
            GLFW.GLFW_KEY_I to InputKey.I, GLFW.GLFW_KEY_J to InputKey.J,
            GLFW.GLFW_KEY_K to InputKey.K, GLFW.GLFW_KEY_L to InputKey.L,
            GLFW.GLFW_KEY_M to InputKey.M, GLFW.GLFW_KEY_N to InputKey.N,
            GLFW.GLFW_KEY_O to InputKey.O, GLFW.GLFW_KEY_P to InputKey.P,
            GLFW.GLFW_KEY_Q to InputKey.Q, GLFW.GLFW_KEY_R to InputKey.R,
            GLFW.GLFW_KEY_S to InputKey.S, GLFW.GLFW_KEY_T to InputKey.T,
            GLFW.GLFW_KEY_U to InputKey.U, GLFW.GLFW_KEY_V to InputKey.V,
            GLFW.GLFW_KEY_W to InputKey.W, GLFW.GLFW_KEY_X to InputKey.X,
            GLFW.GLFW_KEY_Y to InputKey.Y, GLFW.GLFW_KEY_Z to InputKey.Z,
            GLFW.GLFW_KEY_0 to InputKey.Digit0, GLFW.GLFW_KEY_1 to InputKey.Digit1,
            GLFW.GLFW_KEY_2 to InputKey.Digit2, GLFW.GLFW_KEY_3 to InputKey.Digit3,
            GLFW.GLFW_KEY_4 to InputKey.Digit4, GLFW.GLFW_KEY_5 to InputKey.Digit5,
            GLFW.GLFW_KEY_6 to InputKey.Digit6, GLFW.GLFW_KEY_7 to InputKey.Digit7,
            GLFW.GLFW_KEY_8 to InputKey.Digit8, GLFW.GLFW_KEY_9 to InputKey.Digit9,
            GLFW.GLFW_KEY_SPACE to InputKey.Space,
            GLFW.GLFW_KEY_MINUS to InputKey.Minus,
            GLFW.GLFW_KEY_EQUAL to InputKey.Equals,
            GLFW.GLFW_KEY_LEFT_BRACKET to InputKey.LeftBracket,
            GLFW.GLFW_KEY_RIGHT_BRACKET to InputKey.RightBracket,
            GLFW.GLFW_KEY_BACKSLASH to InputKey.Backslash,
            GLFW.GLFW_KEY_SEMICOLON to InputKey.Semicolon,
            GLFW.GLFW_KEY_APOSTROPHE to InputKey.Apostrophe,
            GLFW.GLFW_KEY_GRAVE_ACCENT to InputKey.Grave,
            GLFW.GLFW_KEY_COMMA to InputKey.Comma,
            GLFW.GLFW_KEY_PERIOD to InputKey.Period,
            GLFW.GLFW_KEY_SLASH to InputKey.Slash,
            GLFW.GLFW_KEY_F1 to InputKey.F1, GLFW.GLFW_KEY_F2 to InputKey.F2,
            GLFW.GLFW_KEY_F3 to InputKey.F3, GLFW.GLFW_KEY_F4 to InputKey.F4,
            GLFW.GLFW_KEY_F5 to InputKey.F5, GLFW.GLFW_KEY_F6 to InputKey.F6,
            GLFW.GLFW_KEY_F7 to InputKey.F7, GLFW.GLFW_KEY_F8 to InputKey.F8,
            GLFW.GLFW_KEY_F9 to InputKey.F9, GLFW.GLFW_KEY_F10 to InputKey.F10,
            GLFW.GLFW_KEY_F11 to InputKey.F11, GLFW.GLFW_KEY_F12 to InputKey.F12,
            GLFW.GLFW_KEY_LEFT to InputKey.Left, GLFW.GLFW_KEY_RIGHT to InputKey.Right,
            GLFW.GLFW_KEY_UP to InputKey.Up, GLFW.GLFW_KEY_DOWN to InputKey.Down,
            GLFW.GLFW_KEY_HOME to InputKey.Home, GLFW.GLFW_KEY_END to InputKey.End,
            GLFW.GLFW_KEY_PAGE_UP to InputKey.PageUp,
            GLFW.GLFW_KEY_PAGE_DOWN to InputKey.PageDown,
            GLFW.GLFW_KEY_ENTER to InputKey.Enter,
            GLFW.GLFW_KEY_KP_ENTER to InputKey.NumpadEnter,
            GLFW.GLFW_KEY_ESCAPE to InputKey.Escape,
            GLFW.GLFW_KEY_TAB to InputKey.Tab,
            GLFW.GLFW_KEY_BACKSPACE to InputKey.Backspace,
            GLFW.GLFW_KEY_DELETE to InputKey.Delete,
            GLFW.GLFW_KEY_INSERT to InputKey.Insert,
            GLFW.GLFW_KEY_LEFT_SHIFT to InputKey.LeftShift,
            GLFW.GLFW_KEY_RIGHT_SHIFT to InputKey.RightShift,
            GLFW.GLFW_KEY_LEFT_CONTROL to InputKey.LeftControl,
            GLFW.GLFW_KEY_RIGHT_CONTROL to InputKey.RightControl,
            GLFW.GLFW_KEY_LEFT_ALT to InputKey.LeftAlt,
            GLFW.GLFW_KEY_RIGHT_ALT to InputKey.RightAlt,
            GLFW.GLFW_KEY_LEFT_SUPER to InputKey.LeftSuper,
            GLFW.GLFW_KEY_RIGHT_SUPER to InputKey.RightSuper,
        )

        /** Keys the engine does not name, and numbers a wrong table would hold. Each must fire nothing. */
        val UNNAMED: List<Pair<Int, String>> = listOf(
            'w'.code to "'w'.code, what UniversalKeyCode('W') gives on Kool main",
            GLFW.GLFW_KEY_F13 to "F13",
            GLFW.GLFW_KEY_KP_1 to "keypad 1",
            GLFW.GLFW_KEY_WORLD_1 to "the non-US key WORLD_1",
        )
    }
}
