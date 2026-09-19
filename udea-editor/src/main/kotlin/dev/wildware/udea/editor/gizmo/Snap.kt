package dev.wildware.udea.editor.gizmo

/**
 * How the editor may round a value a drag writes, before it reaches the world (issue #236).
 *
 * A gizmo says what **kind** of value it writes, and never rounds anything itself: the steps, and
 * whether snapping is on at all, are the person's editor preferences, and Ctrl held during a drag
 * turns it off. So one gizmo snaps to a one-unit grid in one project and a sixteen-unit grid in
 * another without knowing either. Tools take exact values: an agent's `editor.update_edit` is never
 * snapped, and never needs to be.
 */
public enum class Snap {

    /** Written exactly as computed: a value with no grid, like a speed or a strength. */
    None,

    /** A distance or a position: rounded to the grid step. */
    Grid,

    /** An angle, in radians: rounded to the angle step. */
    Angle,
}
