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

        /**
         * The world's axes turned [rotationX] radians about X, then [rotationY] about Y, then
         * [rotationZ] about Z (issue #237): the frame of an entity turned by a `Transform3D`'s three
         * angles, in the order its model is drawn with them. A turn about Z alone is [heading], and no
         * turn at all is exactly [WORLD].
         */
        public fun euler(rotationX: Float, rotationY: Float, rotationZ: Float): AxisFrame {
            val cx = cos(rotationX)
            val sx = sin(rotationX)
            val cy = cos(rotationY)
            val sy = sin(rotationY)
            val cz = cos(rotationZ)
            val sz = sin(rotationZ)
            // The columns of Rz * Ry * Rx. `0f - ...` rather than `-...` for [heading]'s reason.
            return AxisFrame(
                WorldPoint(cz * cy, sz * cy, 0f - sy),
                WorldPoint(cz * sy * sx - sz * cx, sz * sy * sx + cz * cx, cy * sx),
                WorldPoint(cz * sy * cx + sz * sx, sz * sy * cx - cz * sx, cy * cx),
            )
        }
    }
}
