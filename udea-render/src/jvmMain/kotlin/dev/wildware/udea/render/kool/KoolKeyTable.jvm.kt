package dev.wildware.udea.render.kool

import dev.wildware.udea.assets.InputKey
import org.lwjgl.glfw.GLFW

/**
 * Kool's desktop backend: the printable keys arrive as GLFW's own constant (issue #228).
 *
 * `GlfwInput` passes any key outside its special-key map through as the raw GLFW key, so a letter is
 * its ASCII uppercase (`GLFW_KEY_W` is 87) and a digit, the space bar or a punctuation key is its
 * character. Written as GLFW's constants rather than as those numbers, so the table reads as "the
 * physical key" and there is no literal here to mistype. `GlKoolInputTest` presses every one of them
 * on Kool's installed GLFW key callback and checks each arrives as its own name and no other.
 */
internal actual val platformKeyTable: KoolKeyTable = KoolKeyTable(
    mapOf(
        InputKey.A to GLFW.GLFW_KEY_A,
        InputKey.B to GLFW.GLFW_KEY_B,
        InputKey.C to GLFW.GLFW_KEY_C,
        InputKey.D to GLFW.GLFW_KEY_D,
        InputKey.E to GLFW.GLFW_KEY_E,
        InputKey.F to GLFW.GLFW_KEY_F,
        InputKey.G to GLFW.GLFW_KEY_G,
        InputKey.H to GLFW.GLFW_KEY_H,
        InputKey.I to GLFW.GLFW_KEY_I,
        InputKey.J to GLFW.GLFW_KEY_J,
        InputKey.K to GLFW.GLFW_KEY_K,
        InputKey.L to GLFW.GLFW_KEY_L,
        InputKey.M to GLFW.GLFW_KEY_M,
        InputKey.N to GLFW.GLFW_KEY_N,
        InputKey.O to GLFW.GLFW_KEY_O,
        InputKey.P to GLFW.GLFW_KEY_P,
        InputKey.Q to GLFW.GLFW_KEY_Q,
        InputKey.R to GLFW.GLFW_KEY_R,
        InputKey.S to GLFW.GLFW_KEY_S,
        InputKey.T to GLFW.GLFW_KEY_T,
        InputKey.U to GLFW.GLFW_KEY_U,
        InputKey.V to GLFW.GLFW_KEY_V,
        InputKey.W to GLFW.GLFW_KEY_W,
        InputKey.X to GLFW.GLFW_KEY_X,
        InputKey.Y to GLFW.GLFW_KEY_Y,
        InputKey.Z to GLFW.GLFW_KEY_Z,
        InputKey.Digit0 to GLFW.GLFW_KEY_0,
        InputKey.Digit1 to GLFW.GLFW_KEY_1,
        InputKey.Digit2 to GLFW.GLFW_KEY_2,
        InputKey.Digit3 to GLFW.GLFW_KEY_3,
        InputKey.Digit4 to GLFW.GLFW_KEY_4,
        InputKey.Digit5 to GLFW.GLFW_KEY_5,
        InputKey.Digit6 to GLFW.GLFW_KEY_6,
        InputKey.Digit7 to GLFW.GLFW_KEY_7,
        InputKey.Digit8 to GLFW.GLFW_KEY_8,
        InputKey.Digit9 to GLFW.GLFW_KEY_9,
        InputKey.Space to GLFW.GLFW_KEY_SPACE,
        InputKey.Minus to GLFW.GLFW_KEY_MINUS,
        InputKey.Equals to GLFW.GLFW_KEY_EQUAL,
        InputKey.LeftBracket to GLFW.GLFW_KEY_LEFT_BRACKET,
        InputKey.RightBracket to GLFW.GLFW_KEY_RIGHT_BRACKET,
        InputKey.Backslash to GLFW.GLFW_KEY_BACKSLASH,
        InputKey.Semicolon to GLFW.GLFW_KEY_SEMICOLON,
        InputKey.Apostrophe to GLFW.GLFW_KEY_APOSTROPHE,
        InputKey.Grave to GLFW.GLFW_KEY_GRAVE_ACCENT,
        InputKey.Comma to GLFW.GLFW_KEY_COMMA,
        InputKey.Period to GLFW.GLFW_KEY_PERIOD,
        InputKey.Slash to GLFW.GLFW_KEY_SLASH,
    ),
)
