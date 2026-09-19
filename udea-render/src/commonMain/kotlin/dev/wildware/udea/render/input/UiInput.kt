package dev.wildware.udea.render.input

import dev.wildware.udea.assets.InputKey

/**
 * Which way a key event is going, or that it is a character rather than a key at all.
 *
 * [Character] is separate from [Down] on purpose, and it is the toolkit's rule as much as this
 * engine's: one Shift and one A are two key events and one character, and an input method produces
 * many key events and one character much later. A backend that sent the character as a key press
 * inserted a square for backspace, and a held backspace deleted nothing.
 */
public enum class KeyPhase {

    /** The key went down. */
    Down,

    /** The platform is repeating a key that is still held. */
    Repeat,

    /** The key came up. */
    Up,

    /** A character the platform has committed. [KeyStroke.character] carries it. */
    Character,
}

/**
 * One key event as the engine passes it around: what happened, to which key, with what held.
 *
 * Named neither after Kool nor after ComposeGL, so the ordering it exists for - [UiInput] first,
 * then the game - can be driven by a test with no window, no context and no toolkit. `KoolKeyboard`
 * translates Kool's events into these, and `UiLayer` translates these into the toolkit's.
 *
 * @param key which key, by name - the vocabulary [KeyboardState.isKeyDown] and [ActionBinding.keys]
 *   speak, translated from the backend's code by `KoolKeyTable` (issue #228). `null` for a key the
 *   table does not name, and for a [KeyPhase.Character] event, which is a character and not a key.
 * @param character the committed character, and only when [phase] is [KeyPhase.Character].
 */
public class KeyStroke(
    public val key: InputKey?,
    public val phase: KeyPhase,
    public val character: Char = NO_CHARACTER,
    public val shift: Boolean = false,
    public val control: Boolean = false,
    public val alt: Boolean = false,
    public val meta: Boolean = false,
) {

    override fun toString(): String = "KeyStroke($key, $phase)"

    public companion object {

        /** What [character] is when this is not a [KeyPhase.Character] event. */
        public const val NO_CHARACTER: Char = '\u0000'
    }
}

/**
 * The interface's **first refusal** on a key.
 *
 * Pointers are [UiPointers], beside this rather than inside it: the toolkit's verdict on a pointer is
 * per frame, a frame late and pushed, where this one is per event, immediate and returned.
 *
 * ```kotlin
 * override fun onKey(event: KeyStroke): Boolean {
 *     if (ui.onKey(event)) return true   // a text field ate it
 *     return world.onKey(event)          // otherwise it is the game's
 * }
 * ```
 *
 * A player typing a save-game name must not also strafe, and Escape closing a panel must not also
 * fire whatever Escape is bound to underneath. That is the whole of it, and it is why this returns a
 * boolean rather than being a listener: "the interface took it" is an answer the game needs before
 * it decides anything.
 *
 * ## The order is not the input stack's
 *
 * `KoolKeyboard` asks this before it records anything, rather than relying on where each handler
 * sits on Kool's `InputStack`. Kool's stack blocks whole categories of input per handler and has no
 * per-event answer, so an ordering built on it would be "the interface blocks every key or none".
 * The answer here is per event, which is what makes a key the interface does not want still reach
 * the game.
 *
 * ## The render thread
 *
 * Every call happens on the render thread. The ComposeGL implementation goes straight to the
 * toolkit's routers, which move focus and walk the node tree and are not thread-safe. `UiLayer`'s
 * KDoc names the single-thread configuration this rests on and the test that guards it.
 */
public fun interface UiInput {

    /** Whether the interface took [event], so the game must never see it. */
    public fun onKey(event: KeyStroke): Boolean

    public companion object {

        /**
         * No interface: every key is the game's.
         *
         * Not a null check at the call site, for the reason [IntentSource.NONE] is not one either -
         * a game whose interface silently stopped taking input would look identical.
         */
        public val NONE: UiInput = UiInput { false }
    }
}
