package dev.wildware.udea.render.kool

import de.fabmax.kool.input.KeyEvent
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.LocalKeyCode
import de.fabmax.kool.input.UniversalKeyCode
import dev.wildware.udea.assets.InputKey
import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.InputBindings
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.KeyPhase
import dev.wildware.udea.render.input.KeyStroke
import dev.wildware.udea.render.input.UiInput
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The input contract issue #224 had to put back after LibGDX left: **the interface goes first**, and
 * what it takes never reaches the gameplay binding underneath.
 *
 * This is `UiInputOrderTest`'s guarantee on the new renderer. That test drove `GdxKeyboard.install`
 * and an `InputMultiplexer`; both are gone with LibGDX (issue #211), and Kool's `InputStack` cannot
 * stand in for the multiplexer, because a handler there blocks a whole category of input or none of
 * it and never answers per event. So the ordering moved into [KoolKeyboard], which asks [UiInput]
 * before it records anything, and this is the test of that.
 *
 * ## The negative is half the test
 *
 * "The keyboard saw nothing" is a green result for a chain wired backwards *and* for a chain not
 * wired at all - a keyboard nobody ever delivers to looks exactly like one the interface is
 * correctly shielding. So every consumed case has a declined case beside it.
 *
 * ## No context
 *
 * Kool's `KeyEvent` is a plain data class, so the whole ordering is driven here with no window, no
 * GL and no toolkit: the interface is a recording fake. That the real Kool delivers to this handler
 * at all, and that the real toolkit answers from the render thread, is `GlKoolInputTest`'s job -
 * these two tests are the halves of one claim and neither is the other's evidence.
 */
class KoolKeyboardOrderTest {

    private val keyboard = KoolKeyboard(TakesEscape)

    @AfterTest
    fun detach() {
        keyboard.close()
    }

    @Test
    fun `a key the interface takes never reaches the keyboard`() {
        keyboard.onKeyEvents(listOf(down(ESCAPE)))

        assertFalse(
            keyboard.isKeyDown(ESCAPE),
            "Escape closed a panel AND was held down for the gameplay binding under it",
        )
        assertEquals(0, keyboard.pressesSince(ESCAPE), "the press reached the game as well")
    }

    @Test
    fun `a key the interface declines still reaches the keyboard`() {
        keyboard.onKeyEvents(listOf(down(W)))

        assertTrue(keyboard.isKeyDown(W), "walking forward stopped working")
        assertEquals(1, keyboard.pressesSince(W), "the press was lost between Kool and the game")
    }

    @Test
    fun `a key the interface takes is marked consumed for whatever is under it`() {
        val escape = down(ESCAPE)
        val w = down(W)

        keyboard.onKeyEvents(listOf(escape, w))

        assertTrue(escape.isConsumed, "Kool's event was not marked, so a lower handler would see it")
        assertFalse(w.isConsumed, "a key nobody wanted was marked consumed")
    }

    @Test
    fun `a key already consumed above is not recorded`() {
        val w = down(W).apply { isConsumed = true }

        keyboard.onKeyEvents(listOf(w))

        assertFalse(keyboard.isKeyDown(W), "something above had already taken W and the game saw it anyway")
    }

    @Test
    fun `releasing a key the interface took does not leave it held`() {
        keyboard.onKeyEvents(listOf(down(W)))
        keyboard.onKeyEvents(listOf(up(W)))

        assertFalse(keyboard.isKeyDown(W), "W stayed down after it was released")
    }

    @Test
    fun `a press the interface declined becomes an intent`() {
        val bindings = InputBindings(
            actions = listOf(ActionBinding(name = "test/walk", keys = listOf(W))),
            axes = emptyList(),
        )
        val source = DeviceIntent(bindings, keyboard)
        val intent = Intent(bindings.catalog)
        val walk = bindings.catalog.action("test/walk")

        keyboard.onKeyEvents(listOf(down(W)))
        source.sample(intent)

        assertTrue(intent.isPressed(walk), "the key was held but the intent was not")
        assertEquals(1, intent.pressCount(walk), "the edge was lost on the way to the intent")
    }

    @Test
    fun `a press the interface took becomes no intent`() {
        val bindings = InputBindings(
            actions = listOf(ActionBinding(name = "test/menu", keys = listOf(ESCAPE))),
            axes = emptyList(),
        )
        val source = DeviceIntent(bindings, keyboard)
        val intent = Intent(bindings.catalog)
        val menu = bindings.catalog.action("test/menu")

        keyboard.onKeyEvents(listOf(down(ESCAPE)))
        source.sample(intent)

        assertFalse(intent.isPressed(menu), "closing the panel also fired the binding behind it")
        assertEquals(0, intent.pressCount(menu), "closing the panel also fired the binding behind it")
    }

    @Test
    fun `a sample spends the counted presses`() {
        keyboard.onKeyEvents(listOf(down(W), up(W), down(W), up(W)))

        assertEquals(2, keyboard.pressesSince(W), "two taps between ticks should be two presses")
        keyboard.endSample()
        assertEquals(0, keyboard.pressesSince(W), "the presses were counted twice")
    }

    @Test
    fun `a key whose character a text field took never reaches the keyboard`() {
        val typing = KoolKeyboard(TakesText)
        try {
            val w = down(W)
            typing.onKeyEvents(listOf(w, typed('w')))
            typing.onKeyEvents(listOf(up(W)))

            assertEquals(0, typing.pressesSince(W), "typing 'w' into a text field also pressed W for the game")
            assertFalse(typing.isKeyDown(W), "typing 'w' into a text field left W held for the game")
            assertTrue(w.isConsumed, "the key that typed the character was not marked consumed")
        } finally {
            typing.close()
        }
    }

    @Test
    fun `a key held down in a text field never becomes held for the game`() {
        val typing = KoolKeyboard(TakesText)
        try {
            typing.onKeyEvents(listOf(down(W), typed('w'), repeat(W), typed('w', repeated = true)))

            assertFalse(typing.isKeyDown(W), "the platform's auto-repeat put W down for the game")
            assertEquals(0, typing.pressesSince(W), "holding a letter in a text field pressed it for the game")
        } finally {
            typing.close()
        }
    }

    @Test
    fun `a key whose character the interface declined still reaches the keyboard`() {
        keyboard.onKeyEvents(listOf(down(W), typed('w')))

        assertTrue(keyboard.isKeyDown(W), "a focused control that takes no text swallowed W")
        assertEquals(1, keyboard.pressesSince(W), "a focused control that takes no text swallowed W's press")
    }

    @Test
    fun `a character only speaks for the key immediately before it`() {
        val typing = KoolKeyboard(TakesText)
        try {
            val s = InputKey.S
            typing.onKeyEvents(listOf(down(W), down(s), typed('s')))

            assertEquals(1, typing.pressesSince(W), "W typed nothing, and a later key's character took it anyway")
            assertEquals(0, typing.pressesSince(s), "S typed 's' into the field and still pressed S")
        } finally {
            typing.close()
        }
    }

    @Test
    fun `a key with nothing after it in the frame is recorded`() {
        val typing = KoolKeyboard(TakesText)
        try {
            typing.onKeyEvents(listOf(down(W)))

            assertEquals(1, typing.pressesSince(W), "a key waiting for a character that never came was lost")
        } finally {
            typing.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /** An interface that wants Escape and nothing else. */
    private object TakesEscape : UiInput {
        override fun onKey(event: KeyStroke): Boolean = event.key == ESCAPE
    }

    /** A focused text field: it takes every printable character and no key. */
    private object TakesText : UiInput {
        override fun onKey(event: KeyStroke): Boolean = event.phase == KeyPhase.Character
    }

    private fun repeat(key: InputKey) = KeyEvent(
        UniversalKeyCode(codeOf(key)),
        LocalKeyCode(codeOf(key)),
        KeyboardInput.KEY_EV_DOWN or KeyboardInput.KEY_EV_REPEATED,
        0,
    )

    /** What Kool queues from GLFW's character callback: the code point as the key code, and the char. */
    private fun typed(char: Char, repeated: Boolean = false) = KeyEvent(
        UniversalKeyCode(char.code),
        LocalKeyCode(char.code),
        KeyboardInput.KEY_EV_CHAR_TYPED or (if (repeated) KeyboardInput.KEY_EV_REPEATED else 0),
        0,
        char,
    )

    private fun down(key: InputKey) = KeyEvent(
        UniversalKeyCode(codeOf(key)),
        LocalKeyCode(codeOf(key)),
        KeyboardInput.KEY_EV_DOWN,
        0,
    )

    private fun up(key: InputKey) = KeyEvent(
        UniversalKeyCode(codeOf(key)),
        LocalKeyCode(codeOf(key)),
        KeyboardInput.KEY_EV_UP,
        0,
    )

    /**
     * The code Kool reports for [key] on the desktop, for building an event by hand. This test is
     * about ordering, not the table: which physical key arrives as which name is
     * `GlKoolKeyTableTest`'s, through Kool's real callback.
     */
    private fun codeOf(key: InputKey): Int = when (key) {
        ESCAPE -> KeyboardInput.KEY_ESC.code
        W -> 'W'.code
        InputKey.S -> 'S'.code
        else -> error("this test builds events for Escape, W and S only, not $key")
    }

    private companion object {
        val ESCAPE = InputKey.Escape
        val W = InputKey.W
    }
}
