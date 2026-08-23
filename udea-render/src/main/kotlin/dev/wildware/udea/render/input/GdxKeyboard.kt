package dev.wildware.udea.render.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor

/**
 * The **one** class in this engine that reads a physical keyboard. Everything else reads an
 * [Intent].
 *
 * ## Why it is an `InputProcessor` and not four `Gdx.input.isKeyJustPressed` calls
 *
 * `isKeyJustPressed` is reset once per **frame**. Two things follow and both are bugs a player
 * feels:
 *
 * - a frame that contains three ticks reports the same "just pressed" to all three, so one tap
 *   fires an ability three times;
 * - a key pressed *and released* inside one frame is never down at any sample point, so at 30fps
 *   a fast tap is simply lost.
 *
 * GLFW queues both events and LibGDX delivers them as callbacks when the window is polled, so
 * counting the down-edges as they arrive and spending the count at the tick boundary loses none
 * and repeats none. That is the whole of [pressesSince] and [endSample].
 *
 * ## It never consumes an event
 *
 * Every callback returns `false`, so this sits at the end of an [InputMultiplexer] and a scene2d
 * `Stage` in front of it still gets first refusal - a click on a button must not also fire the
 * gameplay binding under it. See [install].
 *
 * ## Threading
 *
 * Callbacks arrive on the thread that polls the window, which is the render thread, and that is
 * the same thread `GameLoop` ticks on in every mode this engine ships. Deliberately not
 * synchronised; see [KeyboardState].
 */
public class GdxKeyboard : KeyboardState, InputProcessor {

    /** Down-edges seen since the last [endSample], indexed by keycode. */
    private val presses = IntArray(Input.Keys.MAX_KEYCODE + 1)

    /** Our own view of what is down, used when there is no `Gdx.input` to ask. */
    private val down = BooleanArray(Input.Keys.MAX_KEYCODE + 1)

    override fun isKeyDown(keycode: Int): Boolean {
        if (keycode < 0 || keycode >= down.size) return false
        // The device's own answer where there is one: it survives a focus loss, which our
        // event-derived view does not (a window that loses focus mid-press may never deliver
        // the key-up, and the character would walk into a wall forever).
        return Gdx.input?.isKeyPressed(keycode) ?: down[keycode]
    }

    override fun pressesSince(keycode: Int): Int =
        if (keycode < 0 || keycode >= presses.size) 0 else presses[keycode]

    override fun endSample() {
        presses.fill(0)
    }

    override fun keyDown(keycode: Int): Boolean {
        if (keycode in presses.indices) {
            presses[keycode]++
            down[keycode] = true
        }
        return false
    }

    override fun keyUp(keycode: Int): Boolean {
        if (keycode in down.indices) down[keycode] = false
        return false
    }

    override fun keyTyped(character: Char): Boolean = false

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean = false

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean = false

    override fun touchCancelled(
        screenX: Int,
        screenY: Int,
        pointer: Int,
        button: Int,
    ): Boolean = false

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean = false

    override fun mouseMoved(screenX: Int, screenY: Int): Boolean = false

    override fun scrolled(amountX: Float, amountY: Float): Boolean = false

    override fun toString(): String = "GdxKeyboard"

    public companion object {

        /**
         * Makes [processors] the window's input chain, in order, and returns the multiplexer.
         *
         * Order is the contract: the scene2d `Stage` goes first so UI consumes what it is over,
         * and the [GdxKeyboard] goes last because it consumes nothing.
         *
         * **What is deliberately not in this chain: the agent overlay hotkey.** `GdxOverlayKey`
         * polls `Gdx.input.isKeyPressed` directly, upstream of everything here and upstream of
         * any [IntentSource]. That is issue #161's rule and it is structural rather than
         * enforced: an agent writes an [InjectedIntent], an injected intent is read by the
         * simulation, and no path leads from there back to the physical key that toggles the
         * human's overlay. If the hotkey were ever bound as an ordinary action, an agent could
         * turn off the panel that narrates what it is doing.
         *
         * Must be called on the render thread - `Gdx.input` has thread affinity like every other
         * `Gdx` static.
         *
         * @throws IllegalStateException when there is no input backend, which means it was called
         *   before the context existed or after it went away. Loud, because the failure it
         *   replaces is a game whose controls silently do nothing.
         */
        public fun install(vararg processors: InputProcessor): InputMultiplexer {
            val input = checkNotNull(Gdx.input) {
                "there is no Gdx.input to install an input chain on; call this on the render " +
                    "thread once the backend has started"
            }
            val multiplexer = InputMultiplexer(*processors)
            input.inputProcessor = multiplexer
            return multiplexer
        }
    }
}
