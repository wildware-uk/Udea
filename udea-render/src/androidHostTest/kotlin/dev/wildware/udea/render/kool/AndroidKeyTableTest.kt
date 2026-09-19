package dev.wildware.udea.render.kool

import android.view.KeyEvent
import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.input.PlatformInputAndroid
import dev.wildware.udea.assets.InputKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android table, checked key by key against **Kool's own Android key map** (issue #228).
 *
 * There is no device on the build box, so this cannot press a key. What it can do is ask the code
 * that would: `PlatformInputAndroid.onKey` resolves a key event as
 * `KEY_CODE_MAP[event.keyCode] ?: event.keyCode`, so the code Kool hands the game for a physical key
 * is a function of that map and nothing else. This reads the map out of the kool-core-android AAR
 * this module is built against - the same bytes a phone runs - and runs each physical key through it.
 *
 * The expectation names each physical key by Android's `KeyEvent.KEYCODE_*` and each answer by
 * [InputKey], so it shares nothing with the table under test: a table entry holding the wrong number
 * fails here under that key's name. Every key is checked, because the way a table goes wrong is one
 * key at a time.
 *
 * ## Why reflection, in a test
 *
 * `KEY_CODE_MAP` is private, and it is the one statement of what Kool's Android backend sends. A test
 * that re-typed the map would agree with whatever this module's table says and prove nothing, which
 * is the mistake issue #228 records from #224. The host test runs with Android's mockable jar
 * returning default values (`udea-render/build.gradle.kts`), because the class's static initialiser
 * also builds a `MotionEvent.PointerCoords` it never uses here.
 */
class AndroidKeyTableTest {

    /** What Kool 0.19.0's Android backend reports for the physical key [androidKeyCode]. */
    private fun koolCode(androidKeyCode: Int): Int = (koolMap()[androidKeyCode]?.code) ?: androidKeyCode

    @Suppress("UNCHECKED_CAST")
    private fun koolMap(): Map<Int, KeyCode> {
        val field = PlatformInputAndroid::class.java.getDeclaredField("KEY_CODE_MAP")
        field.isAccessible = true
        return field.get(null) as Map<Int, KeyCode>
    }

    @Test
    fun `every named key Android can report arrives as itself`() {
        val wrong = PHYSICAL.mapNotNull { (androidKey, expected) ->
            val code = koolCode(androidKey)
            val seen = platformKeyTable.keyOf(code)
            if (seen == expected) null else "$expected: Android key $androidKey reaches the game as Kool code $code, which the table reads as $seen"
        }
        assertTrue(wrong.isEmpty(), "Android keys the table misreads:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `the only named keys Android leaves out are the ones Kool gives a letter's code`() {
        // Every key is either answered above or listed below, so a key added to InputKey with no
        // Android mapping fails here rather than being silently unbindable on a phone.
        assertEquals(
            InputKey.entries.toSet(),
            PHYSICAL.map { it.second }.toSet() + COLLIDING.map { it.second },
            "every named key must be either mapped for Android or explained as a collision",
        )
        // ...and each of those really does collide with a letter, so the table is not merely
        // missing it. If Kool stops folding these into 65..90, this fails and the key can be mapped.
        val stillDistinct = COLLIDING.filter { (androidKey, _) ->
            platformKeyTable.keyOf(koolCode(androidKey)) !in LETTERS
        }
        assertTrue(
            stillDistinct.isEmpty(),
            "these keys no longer share a letter's code on Kool's Android backend, so the table can " +
                "map them now: ${stillDistinct.map { it.second }}",
        )
    }

    private companion object {

        val LETTERS: Set<InputKey> = ('A'..'Z').map { InputKey.valueOf(it.toString()) }.toSet()

        /** Each physical key by Android's constant, and the name it must arrive as. */
        val PHYSICAL: List<Pair<Int, InputKey>> = listOf(
            KeyEvent.KEYCODE_A to InputKey.A, KeyEvent.KEYCODE_B to InputKey.B,
            KeyEvent.KEYCODE_C to InputKey.C, KeyEvent.KEYCODE_D to InputKey.D,
            KeyEvent.KEYCODE_E to InputKey.E, KeyEvent.KEYCODE_F to InputKey.F,
            KeyEvent.KEYCODE_G to InputKey.G, KeyEvent.KEYCODE_H to InputKey.H,
            KeyEvent.KEYCODE_I to InputKey.I, KeyEvent.KEYCODE_J to InputKey.J,
            KeyEvent.KEYCODE_K to InputKey.K, KeyEvent.KEYCODE_L to InputKey.L,
            KeyEvent.KEYCODE_M to InputKey.M, KeyEvent.KEYCODE_N to InputKey.N,
            KeyEvent.KEYCODE_O to InputKey.O, KeyEvent.KEYCODE_P to InputKey.P,
            KeyEvent.KEYCODE_Q to InputKey.Q, KeyEvent.KEYCODE_R to InputKey.R,
            KeyEvent.KEYCODE_S to InputKey.S, KeyEvent.KEYCODE_T to InputKey.T,
            KeyEvent.KEYCODE_U to InputKey.U, KeyEvent.KEYCODE_V to InputKey.V,
            KeyEvent.KEYCODE_W to InputKey.W, KeyEvent.KEYCODE_X to InputKey.X,
            KeyEvent.KEYCODE_Y to InputKey.Y, KeyEvent.KEYCODE_Z to InputKey.Z,
            KeyEvent.KEYCODE_0 to InputKey.Digit0, KeyEvent.KEYCODE_1 to InputKey.Digit1,
            KeyEvent.KEYCODE_2 to InputKey.Digit2, KeyEvent.KEYCODE_3 to InputKey.Digit3,
            KeyEvent.KEYCODE_4 to InputKey.Digit4, KeyEvent.KEYCODE_5 to InputKey.Digit5,
            KeyEvent.KEYCODE_6 to InputKey.Digit6, KeyEvent.KEYCODE_7 to InputKey.Digit7,
            KeyEvent.KEYCODE_8 to InputKey.Digit8, KeyEvent.KEYCODE_9 to InputKey.Digit9,
            KeyEvent.KEYCODE_SPACE to InputKey.Space,
            KeyEvent.KEYCODE_COMMA to InputKey.Comma,
            KeyEvent.KEYCODE_PERIOD to InputKey.Period,
            KeyEvent.KEYCODE_F1 to InputKey.F1, KeyEvent.KEYCODE_F2 to InputKey.F2,
            KeyEvent.KEYCODE_F3 to InputKey.F3, KeyEvent.KEYCODE_F4 to InputKey.F4,
            KeyEvent.KEYCODE_F5 to InputKey.F5, KeyEvent.KEYCODE_F6 to InputKey.F6,
            KeyEvent.KEYCODE_F7 to InputKey.F7, KeyEvent.KEYCODE_F8 to InputKey.F8,
            KeyEvent.KEYCODE_F9 to InputKey.F9, KeyEvent.KEYCODE_F10 to InputKey.F10,
            KeyEvent.KEYCODE_F11 to InputKey.F11, KeyEvent.KEYCODE_F12 to InputKey.F12,
            KeyEvent.KEYCODE_DPAD_LEFT to InputKey.Left,
            KeyEvent.KEYCODE_DPAD_RIGHT to InputKey.Right,
            KeyEvent.KEYCODE_DPAD_UP to InputKey.Up,
            KeyEvent.KEYCODE_DPAD_DOWN to InputKey.Down,
            KeyEvent.KEYCODE_MOVE_HOME to InputKey.Home,
            KeyEvent.KEYCODE_MOVE_END to InputKey.End,
            KeyEvent.KEYCODE_PAGE_UP to InputKey.PageUp,
            KeyEvent.KEYCODE_PAGE_DOWN to InputKey.PageDown,
            KeyEvent.KEYCODE_ENTER to InputKey.Enter,
            KeyEvent.KEYCODE_NUMPAD_ENTER to InputKey.NumpadEnter,
            KeyEvent.KEYCODE_ESCAPE to InputKey.Escape,
            KeyEvent.KEYCODE_TAB to InputKey.Tab,
            KeyEvent.KEYCODE_DEL to InputKey.Backspace,
            KeyEvent.KEYCODE_FORWARD_DEL to InputKey.Delete,
            KeyEvent.KEYCODE_INSERT to InputKey.Insert,
            KeyEvent.KEYCODE_SHIFT_LEFT to InputKey.LeftShift,
            KeyEvent.KEYCODE_SHIFT_RIGHT to InputKey.RightShift,
            KeyEvent.KEYCODE_CTRL_LEFT to InputKey.LeftControl,
            KeyEvent.KEYCODE_CTRL_RIGHT to InputKey.RightControl,
            KeyEvent.KEYCODE_ALT_LEFT to InputKey.LeftAlt,
            KeyEvent.KEYCODE_ALT_RIGHT to InputKey.RightAlt,
            KeyEvent.KEYCODE_META_LEFT to InputKey.LeftSuper,
            KeyEvent.KEYCODE_META_RIGHT to InputKey.RightSuper,
        )

        /**
         * The punctuation keys Kool 0.19.0's Android backend reports with a letter's code, which the
         * table therefore leaves out - see the Android table's KDoc.
         */
        val COLLIDING: List<Pair<Int, InputKey>> = listOf(
            KeyEvent.KEYCODE_GRAVE to InputKey.Grave,
            KeyEvent.KEYCODE_MINUS to InputKey.Minus,
            KeyEvent.KEYCODE_EQUALS to InputKey.Equals,
            KeyEvent.KEYCODE_LEFT_BRACKET to InputKey.LeftBracket,
            KeyEvent.KEYCODE_RIGHT_BRACKET to InputKey.RightBracket,
            KeyEvent.KEYCODE_BACKSLASH to InputKey.Backslash,
            KeyEvent.KEYCODE_SEMICOLON to InputKey.Semicolon,
            KeyEvent.KEYCODE_APOSTROPHE to InputKey.Apostrophe,
            KeyEvent.KEYCODE_SLASH to InputKey.Slash,
        )
    }
}
