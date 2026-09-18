package dev.wildware.udea.render.kool

import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.KeyEvent
import dev.wildware.udea.render.input.KeyPhase
import dev.wildware.udea.render.input.KeyStroke
import dev.wildware.udea.render.input.KeyboardState
import dev.wildware.udea.render.input.UiInput

/**
 * Kool's keyboard, as the intent sampler needs it - **after the interface has had first refusal**.
 *
 * The one class that reads a real key on this renderer (issue #224). `GdxKeyboard` was it on LibGDX
 * and went with it; `DeviceIntent` has always talked to [KeyboardState] and has never named a
 * backend, so this is the whole of the change on the game's side: build one, hand it to a
 * `DeviceIntent`, and every binding works.
 *
 * ```kotlin
 * val keyboard = KoolKeyboard(ui)                       // `ui` is the UiLayer, or UiInput.NONE
 * val source = DeviceIntent(game.bindings, keyboard)
 * ```
 *
 * ## The ordering, and why it is here rather than on Kool's input stack
 *
 * Every event is offered to [ui] first. If the interface takes it, it is marked consumed for
 * anything below and **not recorded**, so no binding under the menu can fire from it; if the
 * interface declines, it is recorded exactly as a key nobody was listening for.
 *
 * Kool's `InputStack` is not what decides that, and could not be: a handler there sets
 * `blockAllKeyboardInput`, which blocks every key for every handler below it, so an interface using
 * it would eat W as readily as Escape. The stack still decides *whether this handler is asked* -
 * something above may have consumed an event already, and [onKeyEvents] honours that.
 *
 * ## Typing is judged on the character, not the key
 *
 * A keystroke arrives twice: the key going down, then the character it typed. A text field takes
 * only the character - a bare letter key means nothing to it - so asking about the key alone would
 * let "w" typed into a name field walk the hero as well (issue #230). So a key-down the interface
 * declines is held back for one event, and if the event after it is its character and the
 * interface takes *that*, the key was typing and is not recorded. Anything else and it is recorded
 * as it would have been. The rule follows the interface's answer: a focused button takes no text,
 * so it swallows no letters, and with no interface shown every key is the game's.
 *
 * It relies on the character following its key directly within one frame's events. GLFW reports
 * both from the same keystroke in the same poll, and Kool queues them in that order in one list.
 *
 * ## Counted edges, not a per-frame flag
 *
 * [pressesSince] counts and [endSample] spends, which is [KeyboardState]'s contract and the reason
 * a key tapped and released between two ticks is still exactly one press. A `justPressed` flag reset
 * per frame loses that tap outright and reports it twice when two ticks run in one frame.
 *
 * ## Threads
 *
 * Kool delivers key events while it polls input, which on this engine's host is the render thread -
 * the same thread [close] and the toolkit are touched on, and the same thread the simulation ticks
 * on, because `KoolThread` configures Kool with `asyncSceneUpdate = false`. `GlKoolInputTest` asserts
 * that identity rather than trusting it. The counters are read by `DeviceIntent.sample` on that same
 * thread, which is why nothing here is synchronised.
 */
public class KoolKeyboard(
    /** Asked before anything is recorded. [UiInput.NONE] when the game has no interface. */
    private val ui: UiInput = UiInput.NONE,
) : KeyboardState, AutoCloseable {

    /** Key codes held right now, in Kool's universal code table. */
    private val down = HashSet<Int>()

    /** Presses counted since [endSample], by key code. */
    private val presses = HashMap<Int, Int>()

    private val listener = InputStack.KeyboardListener { events, _ -> onKeyEvents(events) }

    private val handler = InputStack.InputHandler(HANDLER_NAME).also {
        it.keyboardListeners += listener
    }

    init {
        // The bottom of the stack: the game is what everything else gets first refusal ahead of.
        // It is belt to [ui]'s braces rather than the mechanism - see the class KDoc - and it is
        // what makes an editor or a console pushed on top work without knowing this class exists.
        InputStack.pushBottom(handler)
    }

    override fun isKeyDown(keycode: Int): Boolean = keycode in down

    override fun pressesSince(keycode: Int): Int = presses[keycode] ?: 0

    override fun endSample() {
        presses.clear()
    }

    /** Stops listening. After this, no key reaches the game through this keyboard. */
    override fun close() {
        InputStack.remove(handler)
        down.clear()
        presses.clear()
    }

    /**
     * One frame's key events, offered to the interface and then recorded.
     *
     * `internal` and separate from [listener] so the ordering can be driven with no context:
     * `KoolKeyboardOrderTest` calls it with hand-built events, and Kool calls it with real ones.
     */
    internal fun onKeyEvents(events: List<KeyEvent>) {
        // A key-down the interface declined, held back for one event: if the event after it is the
        // character it typed and the interface takes that, the key was typing and is not recorded.
        var typing: KeyEvent? = null
        for (event in events) {
            if (event.isConsumed) continue
            val taken = ui.onKey(strokeOf(event))
            val held = typing
            typing = null
            if (held != null) {
                if (taken && event.isCharTyped) held.isConsumed = true else record(held)
            }
            when {
                taken -> event.isConsumed = true
                event.isPressed && !event.isCharTyped -> typing = event
                else -> record(event)
            }
        }
        typing?.let(::record)
    }

    private fun record(event: KeyEvent) {
        // A character is not a key: it carries the codepoint in `keyCode`, so recording it would
        // report a key with a code no binding means and, for a capital, a code that is not even the
        // one the same physical key reports when pressed.
        if (event.isCharTyped) return
        val code = event.keyCode.code
        when {
            event.isPressed -> {
                down += code
                // Repeats do not count: a held key is one press and then a level, and counting the
                // platform's auto-repeat would fire an ability once per repeat interval.
                if (!event.isRepeated) presses[code] = (presses[code] ?: 0) + 1
            }

            event.isReleased -> down -= code
        }
    }

    private fun strokeOf(event: KeyEvent): KeyStroke = KeyStroke(
        keycode = event.keyCode.code,
        phase = when {
            event.isCharTyped -> KeyPhase.Character
            event.isRepeated && event.isPressed -> KeyPhase.Repeat
            event.isReleased -> KeyPhase.Up
            else -> KeyPhase.Down
        },
        character = if (event.isCharTyped) event.typedChar else KeyStroke.NO_CHARACTER,
        shift = event.isShiftDown,
        control = event.isCtrlDown,
        alt = event.isAltDown,
        meta = event.isSuperDown,
    )

    override fun toString(): String = "KoolKeyboard(ui=$ui)"

    private companion object {

        /** What this handler is called on Kool's stack, so a person reading a dump can tell. */
        const val HANDLER_NAME = "udea-game"
    }
}

