package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler

/**
 * Whether Shift is held, for the Scene tab's Shift-click (issue #235).
 *
 * ComposeGL's pointer events carry no modifiers, and its key events do, so the window listens to
 * every key event that reaches it and keeps the one bit the pointer needs. It takes none of them:
 * a key the window heard still reaches whatever else wanted it.
 *
 * Render thread only, like the window's other handlers.
 */
internal class EditorKeys : KeyHandler {

    /** Whether Shift was down at the last key event the window heard. */
    var shift: Boolean = false
        private set

    override fun onKey(event: KeyEvent): Boolean {
        shift = when {
            event.key == Key.Shift -> event.type == KeyEventType.Down
            else -> event.modifiers.shift
        }
        return false
    }

    override fun toString(): String = "EditorKeys(shift=$shift)"
}
