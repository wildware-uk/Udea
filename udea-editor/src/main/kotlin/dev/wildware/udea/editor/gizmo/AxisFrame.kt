package dev.wildware.udea.editor.gizmo

import kotlin.math.cos
import kotlin.math.sin

/**
 * Which way a handle's axes point: three unit directions in world space, one for each [Axis].
 *
 * The editor's world/local switch (issue #236) is this and nothing more. With **world** axes every
 * target is handed [WORLD], so an arrow along [Axis.X] points along the world's X. With **local**
 * axes a target is handed its entity's own frame - [heading] for a 2D entity turned on the ground
 * plane - so the same arrow points along the entity's own X. A gizmo passes [GizmoTarget.axes] to
 * [GizmoScope.handle], and the editor draws the handle's shape and holds its drag in that frame.
 *
 * Each direction is written as the [WorldPoint] one unit from the origin along it, because that is
 * the one point type this API has; nothing here is a position.
 */
public data class AxisFrame(
    /** The frame's X, as a unit direction in world space. */
    val x: WorldPoint,
    /** The frame's Y, as a unit direction in world space. */
    val y: WorldPoint,
    /** The frame's Z, as a unit direction in world space. */
    val z: WorldPoint,
) {

    /** The world direction [axis] points along in this frame. */
    public fun direction(axis: Axis): WorldPoint = when (axis) {
        Axis.X -> x
        Axis.Y -> y
        Axis.Z -> z
    }

    public companion object {

        /** The world's own axes: what every handle is aligned to with the switch on world. */
        public val WORLD: AxisFrame = AxisFrame(WorldPoint(1f, 0f, 0f), WorldPoint(0f, 1f, 0f), WorldPoint(0f, 0f, 1f))

        /**
         * The world's axes turned [radians] anticlockwise about Z, seen from above: the frame of a 2D
         * entity with that heading. Z, the up axis, is unchanged.
         */
        public fun heading(radians: Float): AxisFrame {
            val c = cos(radians)
            val s = sin(radians)
            // `0f - s`, not `-s`: a zero heading must be exactly [WORLD], and -0f is not 0f to a data class.
            return AxisFrame(WorldPoint(c, s, 0f), WorldPoint(0f - s, c, 0f), WORLD.z)
        }
    }
}
