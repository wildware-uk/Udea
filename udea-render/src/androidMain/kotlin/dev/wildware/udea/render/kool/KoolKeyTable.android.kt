package dev.wildware.udea.render.kool

import android.view.KeyEvent
import dev.wildware.udea.assets.InputKey

/**
 * Kool's Android backend (issue #228), read from `PlatformInputAndroid` in the kool-core-android
 * 0.19.0 AAR because it is not the desktop's.
 *
 * `onKey` builds the code as `KEY_CODE_MAP[event.keyCode] ?: event.keyCode`, the same shape as the
 * desktop's, but the map holds more: beside the special keys it renames `KEYCODE_A`..`KEYCODE_Z`
 * (29..54) to the letter's ASCII uppercase, 65..90 - the numbers GLFW uses. Every other key is passed
 * through as Android's own `KEYCODE_*`, so here a digit is `KEYCODE_0` (7), not the character `'0'`
 * (48) the desktop reports, and the space bar is `KEYCODE_SPACE` (62), not 32. That is the whole
 * reason there is a table per backend: a controls asset holding the desktop's numbers would bind the
 * wrong digits on a phone and never see its space bar.
 *
 * The letters are written as the uppercase character because that is the number Kool's map
 * produces, and not through `UniversalKeyCode(Char)`, whose case flips between Kool releases.
 * `AndroidKeyTableTest` reads Kool's own map out of the AAR and checks every entry here against it.
 *
 * ## The keys this backend cannot tell apart, and why they are missing
 *
 * Because letters are renamed into 65..90 and every other key keeps its Android number, Kool 0.19.0
 * gives several physical keys the same code on this backend: `KEYCODE_GRAVE` (68) is `D`,
 * `KEYCODE_MINUS` (69) `E`, `KEYCODE_EQUALS` (70) `F`, `KEYCODE_LEFT_BRACKET` (71) `G`,
 * `KEYCODE_RIGHT_BRACKET` (72) `H`, `KEYCODE_BACKSLASH` (73) `I`, `KEYCODE_SEMICOLON` (74) `J`,
 * `KEYCODE_APOSTROPHE` (75) `K` and `KEYCODE_SLASH` (76) `L`. A key event with code 69 cannot say
 * which of E and minus was pressed, and no table can recover it. So those nine punctuation keys are
 * left out and the letters kept: a binding on [InputKey.Minus] does nothing on Android, where the
 * alternative - mapping 69 to minus - would make every E press a minus press. [KoolKeyTable] refuses
 * a table with a shared code, so this cannot be undone by accident.
 *
 * Keys outside [InputKey] collide the same way - `KEYCODE_MENU` (82) arrives as the code for `R` -
 * and nothing on this side of Kool can separate them either. It is Kool's map that would have to
 * change, and this KDoc and `AndroidKeyTableTest` are where that change would be noticed.
 */
internal actual val platformKeyTable: KoolKeyTable = KoolKeyTable(
    mapOf(
        InputKey.A to 'A'.code,
        InputKey.B to 'B'.code,
        InputKey.C to 'C'.code,
        InputKey.D to 'D'.code,
        InputKey.E to 'E'.code,
        InputKey.F to 'F'.code,
        InputKey.G to 'G'.code,
        InputKey.H to 'H'.code,
        InputKey.I to 'I'.code,
        InputKey.J to 'J'.code,
        InputKey.K to 'K'.code,
        InputKey.L to 'L'.code,
        InputKey.M to 'M'.code,
        InputKey.N to 'N'.code,
        InputKey.O to 'O'.code,
        InputKey.P to 'P'.code,
        InputKey.Q to 'Q'.code,
        InputKey.R to 'R'.code,
        InputKey.S to 'S'.code,
        InputKey.T to 'T'.code,
        InputKey.U to 'U'.code,
        InputKey.V to 'V'.code,
        InputKey.W to 'W'.code,
        InputKey.X to 'X'.code,
        InputKey.Y to 'Y'.code,
        InputKey.Z to 'Z'.code,
        InputKey.Digit0 to KeyEvent.KEYCODE_0,
        InputKey.Digit1 to KeyEvent.KEYCODE_1,
        InputKey.Digit2 to KeyEvent.KEYCODE_2,
        InputKey.Digit3 to KeyEvent.KEYCODE_3,
        InputKey.Digit4 to KeyEvent.KEYCODE_4,
        InputKey.Digit5 to KeyEvent.KEYCODE_5,
        InputKey.Digit6 to KeyEvent.KEYCODE_6,
        InputKey.Digit7 to KeyEvent.KEYCODE_7,
        InputKey.Digit8 to KeyEvent.KEYCODE_8,
        InputKey.Digit9 to KeyEvent.KEYCODE_9,
        InputKey.Space to KeyEvent.KEYCODE_SPACE,
        InputKey.Comma to KeyEvent.KEYCODE_COMMA,
        InputKey.Period to KeyEvent.KEYCODE_PERIOD,
    ),
)
