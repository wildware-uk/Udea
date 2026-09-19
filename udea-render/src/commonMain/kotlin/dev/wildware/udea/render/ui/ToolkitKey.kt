package dev.wildware.udea.render.ui

import dev.wildware.composegl.ui.input.Key
import dev.wildware.udea.assets.InputKey

/**
 * [key] as the toolkit names it, or [Key.Unknown] for a key with no name (issue #228).
 *
 * This used to be `KeyTable`, a second table from Kool's key codes to the toolkit's (issues #224 and
 * #230), and it had to know everything `KoolKeyTable` knows about which backend sends which number.
 * There is one table now: `KoolKeyTable` turns a backend's code into an [InputKey] for the game and
 * the interface alike, and this is only a translation between two vocabularies of names, with no
 * backend and no number in it. An exhaustive `when`, so a key added to [InputKey] does not compile
 * until the interface has been told what it is.
 *
 * The toolkit does not tell left from right on a modifier, or the keypad's Enter from the main one,
 * so those pairs meet here. A [Key.Unknown] is representable, routed and not a crash, which is the
 * behaviour ComposeGL's own `Key` KDoc asks for.
 */
internal fun toolkitKey(key: InputKey?): Key = when (key) {
    null -> Key.Unknown
    InputKey.A -> Key.A
    InputKey.B -> Key.B
    InputKey.C -> Key.C
    InputKey.D -> Key.D
    InputKey.E -> Key.E
    InputKey.F -> Key.F
    InputKey.G -> Key.G
    InputKey.H -> Key.H
    InputKey.I -> Key.I
    InputKey.J -> Key.J
    InputKey.K -> Key.K
    InputKey.L -> Key.L
    InputKey.M -> Key.M
    InputKey.N -> Key.N
    InputKey.O -> Key.O
    InputKey.P -> Key.P
    InputKey.Q -> Key.Q
    InputKey.R -> Key.R
    InputKey.S -> Key.S
    InputKey.T -> Key.T
    InputKey.U -> Key.U
    InputKey.V -> Key.V
    InputKey.W -> Key.W
    InputKey.X -> Key.X
    InputKey.Y -> Key.Y
    InputKey.Z -> Key.Z
    InputKey.Digit0 -> Key.Digit0
    InputKey.Digit1 -> Key.Digit1
    InputKey.Digit2 -> Key.Digit2
    InputKey.Digit3 -> Key.Digit3
    InputKey.Digit4 -> Key.Digit4
    InputKey.Digit5 -> Key.Digit5
    InputKey.Digit6 -> Key.Digit6
    InputKey.Digit7 -> Key.Digit7
    InputKey.Digit8 -> Key.Digit8
    InputKey.Digit9 -> Key.Digit9
    InputKey.Space -> Key.Space
    InputKey.Minus -> Key.Minus
    InputKey.Equals -> Key.Equals
    InputKey.LeftBracket -> Key.LeftBracket
    InputKey.RightBracket -> Key.RightBracket
    InputKey.Backslash -> Key.Backslash
    InputKey.Semicolon -> Key.Semicolon
    InputKey.Apostrophe -> Key.Apostrophe
    InputKey.Grave -> Key.Grave
    InputKey.Comma -> Key.Comma
    InputKey.Period -> Key.Period
    InputKey.Slash -> Key.Slash
    InputKey.F1 -> Key.F1
    InputKey.F2 -> Key.F2
    InputKey.F3 -> Key.F3
    InputKey.F4 -> Key.F4
    InputKey.F5 -> Key.F5
    InputKey.F6 -> Key.F6
    InputKey.F7 -> Key.F7
    InputKey.F8 -> Key.F8
    InputKey.F9 -> Key.F9
    InputKey.F10 -> Key.F10
    InputKey.F11 -> Key.F11
    InputKey.F12 -> Key.F12
    InputKey.Left -> Key.Left
    InputKey.Right -> Key.Right
    InputKey.Up -> Key.Up
    InputKey.Down -> Key.Down
    InputKey.Home -> Key.Home
    InputKey.End -> Key.End
    InputKey.PageUp -> Key.PageUp
    InputKey.PageDown -> Key.PageDown
    InputKey.Enter, InputKey.NumpadEnter -> Key.Enter
    InputKey.Escape -> Key.Escape
    InputKey.Tab -> Key.Tab
    InputKey.Backspace -> Key.Backspace
    InputKey.Delete -> Key.Delete
    InputKey.Insert -> Key.Insert
    InputKey.LeftShift, InputKey.RightShift -> Key.Shift
    InputKey.LeftControl, InputKey.RightControl -> Key.Control
    InputKey.LeftAlt, InputKey.RightAlt -> Key.Alt
    InputKey.LeftSuper, InputKey.RightSuper -> Key.Meta
}
