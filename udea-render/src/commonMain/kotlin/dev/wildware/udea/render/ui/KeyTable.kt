package dev.wildware.udea.render.ui

import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.UniversalKeyCode
import dev.wildware.composegl.ui.input.Key

/**
 * The one place Kool's key codes become the toolkit's, and the one place that has to move if the
 * renderer ever changes again.
 *
 * Two numberings, both deliberate and neither reducible to the other. Kool's universal codes are the
 * lowercase character for a printable key (`'w'` is 119) and a small negative number for everything
 * else; ComposeGL numbers its own `Key` from 1 so that a binding can be saved by name and survive a
 * backend change. So this is a table rather than arithmetic, and a key missing from it is
 * [Key.Unknown] - representable, routed, and not a crash, which is the behaviour ComposeGL's own
 * `Key` KDoc asks for.
 *
 * Only keys the toolkit names are here. A game binding F13 still works: that path never comes
 * through this table, because a game's bindings read [dev.wildware.udea.render.input.KeyboardState]
 * in Kool's own codes and this table exists solely to ask the interface whether it wants the key.
 */
internal object KeyTable {

    /** [code] in Kool's universal table, as the toolkit names it, or [Key.Unknown]. */
    fun toolkitKey(code: Int): Key = byKoolCode[code] ?: Key.Unknown

    private val byKoolCode: Map<Int, Key> = buildMap {
        ('a'..'z').forEach { letter ->
            put(UniversalKeyCode(letter).code, Key(Key.A.code + (letter - 'a')))
        }
        ('0'..'9').forEach { digit ->
            put(UniversalKeyCode(digit).code, Key(Key.Digit0.code + (digit - '0')))
        }
        listOf(
            KeyboardInput.KEY_F1 to Key.F1,
            KeyboardInput.KEY_F2 to Key.F2,
            KeyboardInput.KEY_F3 to Key.F3,
            KeyboardInput.KEY_F4 to Key.F4,
            KeyboardInput.KEY_F5 to Key.F5,
            KeyboardInput.KEY_F6 to Key.F6,
            KeyboardInput.KEY_F7 to Key.F7,
            KeyboardInput.KEY_F8 to Key.F8,
            KeyboardInput.KEY_F9 to Key.F9,
            KeyboardInput.KEY_F10 to Key.F10,
            KeyboardInput.KEY_F11 to Key.F11,
            KeyboardInput.KEY_F12 to Key.F12,
            KeyboardInput.KEY_CURSOR_LEFT to Key.Left,
            KeyboardInput.KEY_CURSOR_RIGHT to Key.Right,
            KeyboardInput.KEY_CURSOR_UP to Key.Up,
            KeyboardInput.KEY_CURSOR_DOWN to Key.Down,
            KeyboardInput.KEY_HOME to Key.Home,
            KeyboardInput.KEY_END to Key.End,
            KeyboardInput.KEY_PAGE_UP to Key.PageUp,
            KeyboardInput.KEY_PAGE_DOWN to Key.PageDown,
            KeyboardInput.KEY_ENTER to Key.Enter,
            KeyboardInput.KEY_NP_ENTER to Key.Enter,
            KeyboardInput.KEY_ESC to Key.Escape,
            KeyboardInput.KEY_TAB to Key.Tab,
            KeyboardInput.KEY_BACKSPACE to Key.Backspace,
            KeyboardInput.KEY_DEL to Key.Delete,
            KeyboardInput.KEY_INSERT to Key.Insert,
            KeyboardInput.KEY_SHIFT_LEFT to Key.Shift,
            KeyboardInput.KEY_SHIFT_RIGHT to Key.Shift,
            KeyboardInput.KEY_CTRL_LEFT to Key.Control,
            KeyboardInput.KEY_CTRL_RIGHT to Key.Control,
            KeyboardInput.KEY_ALT_LEFT to Key.Alt,
            KeyboardInput.KEY_ALT_RIGHT to Key.Alt,
            KeyboardInput.KEY_SUPER_LEFT to Key.Meta,
            KeyboardInput.KEY_SUPER_RIGHT to Key.Meta,
        ).forEach { (kool, key) -> put(kool.code, key) }
        // Space is a printable key Kool reports by its character, like a letter, and the toolkit
        // names. Without this line a menu could not be driven by the space bar.
        put(UniversalKeyCode(' ').code, Key.Space)
        listOf(
            '-' to Key.Minus,
            '=' to Key.Equals,
            '[' to Key.LeftBracket,
            ']' to Key.RightBracket,
            '\\' to Key.Backslash,
            ';' to Key.Semicolon,
            '\'' to Key.Apostrophe,
            '`' to Key.Grave,
            ',' to Key.Comma,
            '.' to Key.Period,
            '/' to Key.Slash,
        ).forEach { (char, key) -> put(UniversalKeyCode(char).code, key) }
    }
}
