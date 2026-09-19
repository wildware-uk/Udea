package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler

/**
 * The keys the Scene tab's pointer needs (issues #235 and #236): whether Shift and Ctrl are held, and
 * Escape.
 *
 * ComposeGL's pointer events carry no modifiers, and its key events do, so the window listens to
 * every key event that reaches it and keeps the bits the pointer needs: Shift for Shift-click, Ctrl to
 * drag a gizmo without snapping. Escape is handed to [escape] - which cancels a gizmo drag - and taken
 * when that had something to cancel. Every other key the window hears still reaches whatever else
 * wanted it.
 *
 * Render thread only, like the window's other handlers.
 *
 * @param escape called when Escape goes down; answers whether it cancelled something.
 */
internal class EditorKeys(private val escape: () -> Boolean = { false }) : KeyHandler {

    /** Whether Shift was down at the last key event the window heard. */
    var shift: Boolean = false
        private set

    /** Whether Ctrl was down at the last key event the window heard. */
    var ctrl: Boolean = false
        private set

    override fun onKey(event: KeyEvent): Boolean {
        shift = when {
            event.key == Key.Shift -> event.type == KeyEventType.Down
            else -> event.modifiers.shift
        }
        ctrl = when {
            event.key == Key.Control -> event.type == KeyEventType.Down
            else -> event.modifiers.control
        }
        return event.key == Key.Escape && event.type == KeyEventType.Down && escape()
    }

    override fun toString(): String = "EditorKeys(shift=$shift, ctrl=$ctrl)"
}
