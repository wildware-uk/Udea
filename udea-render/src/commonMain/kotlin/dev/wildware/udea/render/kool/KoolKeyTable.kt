package dev.wildware.udea.render.kool

import de.fabmax.kool.input.KeyboardInput
import dev.wildware.udea.assets.InputKey

/**
 * The one place a Kool key code becomes an [InputKey], per backend (issue #228).
 *
 * A game binds names and never numbers; this is where the numbers live. [KoolKeyboard] reads every
 * key event through [keyOf], and so does the interface layer, so a menu and a binding cannot
 * disagree about which key was pressed. [platformKeyTable] is the table for the backend this
 * build draws with: `jvm` is Kool's desktop backend over GLFW, and `android` is Kool's Android
 * backend. There is no web table because there is no web target: Kool 0.19.0 publishes no wasmJs
 * artifact, and `dev.wildware.udea.kotlin-multiplatform-render` builds `jvm` and `android` only.
 *
 * ## Two schemes in one number, which is why this is a table and not arithmetic
 *
 * Both of Kool's backends build a key event's code as `KEY_CODE_MAP[platformKey] ?: platformKey`.
 * The keys in that map - the function row, the cursor and editing keys, the modifiers, Enter and
 * Escape - come out as small **negative** codes of Kool's own ([KeyboardInput.KEY_ESC] is -9, where
 * GLFW's Escape is 256), and they are the same on both backends, so [SPECIAL] is shared. Every other
 * key comes out as the **platform's own number**, and that half differs: GLFW numbers a letter by its
 * ASCII uppercase and a digit by its character, and Android numbers them by `KeyEvent.KEYCODE_*`. So
 * each backend supplies its printable half and nothing else.
 *
 * ## The universal code, not the local one
 *
 * A Kool `KeyEvent` carries two codes. `keyCode` - the *universal* code - is the one above: which
 * physical key it was, by its position on a US layout. `localKeyCode` is what the key is labelled on
 * the player's layout: on the desktop Kool fills it from `glfwGetKeyName`, so on a French keyboard
 * the key in W's place reports a local `Z`. This table maps the **universal** code, because a binding
 * means a place under the player's fingers - walking forward is the key above S whatever is printed
 * on it - and because it is the half both backends fill the same way (Kool's Android backend sets the
 * local code equal to the universal one). A game that wants "the key labelled Q" rather than "the key
 * in Q's place" is asking for text, which the interface layer handles through the typed character,
 * not through a binding.
 *
 * ## Keyed on what the platform sends, never on `UniversalKeyCode(Char)`
 *
 * That constructor is a convenience whose case flips between Kool releases: 0.19.0 uppercases the
 * character and Kool's `main` lowercases it. Every entry here is a platform constant or one of
 * Kool's `KEY_*` constants, so a Kool upgrade that changes the helper changes nothing here - and the
 * tests that drive each backend's real key path (`GlKoolInputTest` on the desktop,
 * `AndroidKeyTableTest` against Kool's own Android map) are what go red if the backend itself moved.
 *
 * ## One code, one key
 *
 * The constructor refuses a table in which two keys share a code: [keyOf] could answer only one of
 * them, and the other key would silently do nothing. That is not hypothetical - see the Android
 * table's KDoc for the keys Kool 0.19.0 cannot tell apart on that backend, and why they are left out
 * of it rather than guessed.
 */
internal class KoolKeyTable(printable: Map<InputKey, Int>) {

    private val byCode: Map<Int, InputKey>

    init {
        val all = printable + SPECIAL
        val clashes = all.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        require(clashes.isEmpty()) {
            "two keys share one Kool code, so one of each pair could never be pressed: $clashes"
        }
        byCode = all.entries.associate { (key, code) -> code to key }
    }

    /** The key Kool reported as [code], or `null` for a key no game can bind. */
    fun keyOf(code: Int): InputKey? = byCode[code]

    override fun toString(): String = "KoolKeyTable(${byCode.size} keys)"

    companion object {

        /**
         * The keys Kool renames to its own codes on every backend. Kool's constants on both sides of
         * the arrow, so there is no number here to be wrong.
         */
        val SPECIAL: Map<InputKey, Int> = mapOf(
            InputKey.F1 to KeyboardInput.KEY_F1.code,
            InputKey.F2 to KeyboardInput.KEY_F2.code,
            InputKey.F3 to KeyboardInput.KEY_F3.code,
            InputKey.F4 to KeyboardInput.KEY_F4.code,
            InputKey.F5 to KeyboardInput.KEY_F5.code,
            InputKey.F6 to KeyboardInput.KEY_F6.code,
            InputKey.F7 to KeyboardInput.KEY_F7.code,
            InputKey.F8 to KeyboardInput.KEY_F8.code,
            InputKey.F9 to KeyboardInput.KEY_F9.code,
            InputKey.F10 to KeyboardInput.KEY_F10.code,
            InputKey.F11 to KeyboardInput.KEY_F11.code,
            InputKey.F12 to KeyboardInput.KEY_F12.code,
            InputKey.Left to KeyboardInput.KEY_CURSOR_LEFT.code,
            InputKey.Right to KeyboardInput.KEY_CURSOR_RIGHT.code,
            InputKey.Up to KeyboardInput.KEY_CURSOR_UP.code,
            InputKey.Down to KeyboardInput.KEY_CURSOR_DOWN.code,
            InputKey.Home to KeyboardInput.KEY_HOME.code,
            InputKey.End to KeyboardInput.KEY_END.code,
            InputKey.PageUp to KeyboardInput.KEY_PAGE_UP.code,
            InputKey.PageDown to KeyboardInput.KEY_PAGE_DOWN.code,
            InputKey.Enter to KeyboardInput.KEY_ENTER.code,
            InputKey.NumpadEnter to KeyboardInput.KEY_NP_ENTER.code,
            InputKey.Escape to KeyboardInput.KEY_ESC.code,
            InputKey.Tab to KeyboardInput.KEY_TAB.code,
            InputKey.Backspace to KeyboardInput.KEY_BACKSPACE.code,
            InputKey.Delete to KeyboardInput.KEY_DEL.code,
            InputKey.Insert to KeyboardInput.KEY_INSERT.code,
            InputKey.LeftShift to KeyboardInput.KEY_SHIFT_LEFT.code,
            InputKey.RightShift to KeyboardInput.KEY_SHIFT_RIGHT.code,
            InputKey.LeftControl to KeyboardInput.KEY_CTRL_LEFT.code,
            InputKey.RightControl to KeyboardInput.KEY_CTRL_RIGHT.code,
            InputKey.LeftAlt to KeyboardInput.KEY_ALT_LEFT.code,
            InputKey.RightAlt to KeyboardInput.KEY_ALT_RIGHT.code,
            InputKey.LeftSuper to KeyboardInput.KEY_SUPER_LEFT.code,
            InputKey.RightSuper to KeyboardInput.KEY_SUPER_RIGHT.code,
        )
    }
}

/** The table for the backend this target draws with: GLFW on `jvm`, Android on `android`. */
internal expect val platformKeyTable: KoolKeyTable
