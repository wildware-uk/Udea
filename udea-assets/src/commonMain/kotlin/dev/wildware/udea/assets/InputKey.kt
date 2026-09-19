package dev.wildware.udea.assets

/**
 * A keyboard key, by name: what a game binds and what a controls asset writes (issue #228).
 *
 * ```kotlin
 * binding(name = "attack_binding", control = reference("control/attack"), input = key(InputKey.Space))
 * ```
 *
 * ## Why a name and not a number
 *
 * A key code is a backend's vocabulary, and the backends disagree. The controls asset used to hold
 * LibGDX's numbers (W was 51), which under Kool on the desktop is the digit 3; Kool's desktop
 * backend reports a letter by GLFW's constant and a special key by a small negative code of Kool's
 * own, so one integer field held two schemes; and Kool's Android backend numbers the digits and the
 * space bar by Android's `KEYCODE_*`, which is a third. A game could not write a number that meant
 * the same key everywhere, and a wrong one compiled, validated and ran.
 *
 * So the asset names the key and the renderer owns the numbers: `udea-render`'s `KoolKeyTable` is the
 * one place a name becomes a backend's code, with a table per backend. This type is here, in the
 * asset model and below the renderer, because a `.udea.kts` compiles against the asset model and must
 * not drag Kool onto its classpath. It carries no code of any kind, deliberately: a number here would
 * be one backend's number again.
 *
 * ## Which key a name means
 *
 * The **physical** key, by its position on a US layout - [W] is the key above [S] wherever the
 * letter printed on it has moved. That is what a movement binding means, and it is what Kool's
 * *universal* key code reports; `KoolKeyTable`'s KDoc says why the table reads that half of a Kool
 * key event and not the local one.
 *
 * The set is the keys `udea-render`'s interface layer names as well, so a menu and a binding speak
 * the same names: the letters, the digits, the space bar and the unshifted punctuation, and the
 * special keys - the function row, the cursor keys, the editing keys and the modifiers, with left
 * and right kept apart because a game may bind one and not the other.
 */
public enum class InputKey {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,

    Digit0, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9,

    Space,
    Minus,
    Equals,
    LeftBracket,
    RightBracket,
    Backslash,
    Semicolon,
    Apostrophe,
    Grave,
    Comma,
    Period,
    Slash,

    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,

    Left,
    Right,
    Up,
    Down,
    Home,
    End,
    PageUp,
    PageDown,
    Enter,
    NumpadEnter,
    Escape,
    Tab,
    Backspace,
    Delete,
    Insert,
    LeftShift,
    RightShift,
    LeftControl,
    RightControl,
    LeftAlt,
    RightAlt,
    LeftSuper,
    RightSuper,
}
