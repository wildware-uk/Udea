package dev.wildware.udea.editor.gizmo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A point in world space.
 *
 * Plain floats, as components store them (owner, 2026-09-19): a handle reads fields and writes
 * fields, and nothing in between holds a vector object. A 2D game's points sit on the ground plane,
 * so [z] defaults to zero.
 */
public data class WorldPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
) {
    /** The straight-line distance to [other]. */
    public fun distanceTo(other: WorldPoint): Float {
        val dx = other.x - x
        val dy = other.y - y
        val dz = other.z - z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/** A world axis. Z is up; the ground plane is [Plane.XY]. */
public enum class Axis { X, Y, Z }

/** A plane through two world axes. [XY] is the ground plane, where a 2D game lives. */
public enum class Plane { XY, XZ, YZ }

/**
 * What the editor holds a handle to while it is dragged, so a drag means one thing whatever the
 * camera is doing: the editor projects the pointer onto this, and hands the gizmo the point.
 */
public sealed interface DragConstraint {

    /** Along a line through the handle, parallel to [axis]. */
    public data class Along(val axis: Axis) : DragConstraint

    /** Across a plane through the handle, parallel to [plane]. */
    public data class Across(val plane: Plane) : DragConstraint

    /** Across the plane through the handle that faces the camera: a free drag in 3D. */
    public data object ViewPlane : DragConstraint
}

/**
 * What the editor draws for a handle.
 *
 * **Every shape keeps a constant size on screen.** None of them carries a world size, so zooming the
 * camera out cannot shrink a handle past being grabbed; the editor sizes each in view pixels. The
 * one world-space dimension is [Line]'s far end, which is a place rather than a size.
 */
public sealed interface HandleShape {

    /** A dot: a grip with no direction. */
    public data object Point : HandleShape

    /** An arrow pointing along [axis]: moves along it. */
    public data class Arrow(val axis: Axis) : HandleShape

    /** A small square lying in [plane]: moves across it. */
    public data class PlaneSquare(val plane: Plane) : HandleShape

    /** A ring around [normal]: turns about it. */
    public data class Ring(val normal: Axis) : HandleShape

    /** A box corner: resizes. */
    public data object BoxCorner : HandleShape

    /** A line from the handle to [to], a world point: a radius spoke, a tether. */
    public data class Line(val to: WorldPoint) : HandleShape

    /** A ball: a free grip in 3D. */
    public data object Sphere : HandleShape
}

/**
 * One drag of a handle, as the editor measured it: two points on the handle's [DragConstraint].
 *
 * Both are where the *pointer* is, held to the constraint, not where the handle's centre is - a
 * person grabs a handle off-centre. So a response computes a change, `now - start`, and adds it to
 * the value it read when it declared the handle; the helpers below are the changes the built-in
 * and generated gizmos use.
 *
 * @property start where the drag began.
 * @property at where the drag is now.
 */
public data class Drag(
    val start: WorldPoint,
    val at: WorldPoint,
) {
    /** How far the drag has moved along X. */
    public val dx: Float get() = at.x - start.x

    /** How far the drag has moved along Y. */
    public val dy: Float get() = at.y - start.y

    /** How far the drag has moved along Z. */
    public val dz: Float get() = at.z - start.z

    /** How much further from [centre] the drag is now than when it began: a radius's change. */
    public fun stretchFrom(centre: WorldPoint): Float = at.distanceTo(centre) - start.distanceTo(centre)

    /**
     * How much a box centred on [centre] grows along [axis] when its corner follows this drag: twice
     * the change in the corner's distance from the centre on that axis, because both faces move.
     */
    public fun spread(axis: Axis, centre: WorldPoint): Float =
        2f * (abs(on(axis, at) - on(axis, centre)) - abs(on(axis, start) - on(axis, centre)))

    /**
     * The angle in radians the drag has turned about the up axis through [centre], anticlockwise
     * seen from above, the short way round: never more than half a turn either way, so a drag that
     * crosses the back of the ring does not jump by a whole turn.
     */
    public fun turnAbout(centre: WorldPoint): Float {
        val from = atan2(start.y - centre.y, start.x - centre.x)
        val to = atan2(at.y - centre.y, at.x - centre.x)
        val turn = to - from
        return when {
            turn > PI_F -> turn - 2f * PI_F
            turn <= -PI_F -> turn + 2f * PI_F
            else -> turn
        }
    }

    private fun on(axis: Axis, point: WorldPoint): Float = when (axis) {
        Axis.X -> point.x
        Axis.Y -> point.y
        Axis.Z -> point.z
    }

    private companion object {
        const val PI_F: Float = PI.toFloat()
    }
}
