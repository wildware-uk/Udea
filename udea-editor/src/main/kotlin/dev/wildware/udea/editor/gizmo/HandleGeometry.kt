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
 * What the editor draws for a handle or a [Mark].
 *
 * **Every shape but [Circle] keeps a constant size on screen.** They carry no world size, so zooming
 * the camera out cannot shrink a handle past being grabbed; the editor sizes each in view pixels.
 * [Line]'s far end is a world place rather than a size. [Circle] is the exception on purpose: it
 * shows how big something is, so it is drawn at that size.
 */
public sealed interface HandleShape {

    /** A dot: a grip with no direction. */
    public data object Point : HandleShape

    /** An arrow pointing along [axis]: moves along it. */
    public data class Arrow(val axis: Axis) : HandleShape

    /** A small square lying in [plane]: moves across it. */
    public data class PlaneSquare(val plane: Plane) : HandleShape

    /**
     * A small square lying in [plane], set off from the handle's point into the corner between the
     * plane's two axes and drawn in perspective (issue #237): so three of them - one per plane - sit
     * round one point without covering it or each other, the way a 3D move handle shows them. Moves
     * across [plane]. Its size and its offset are in view pixels.
     */
    public data class PlaneTab(val plane: Plane) : HandleShape

    /** A ring around [normal]: turns about it. */
    public data class Ring(val normal: Axis) : HandleShape

    /** A box corner: resizes. */
    public data object BoxCorner : HandleShape

    /** The middle of a box's side that faces along [axis]: resizes along that axis alone. */
    public data class BoxEdge(val axis: Axis) : HandleShape

    /** A line from the handle to [to], a world point: a radius spoke, a tether. */
    public data class Line(val to: WorldPoint) : HandleShape

    /** A ball: a free grip in 3D. */
    public data object Sphere : HandleShape

    /**
     * A small box out along [axis], beyond the arrows and the rings, on a thin stalk from the handle's
     * point (issue #237): scales along [axis].
     */
    public data class ScaleBox(val axis: Axis) : HandleShape

    /** A box on the handle's point itself (issue #237): scales every axis at once. */
    public data object UniformBox : HandleShape

    /**
     * A circle of world [radius] about the point, square to [normal] - a range, a reach, drawn at the
     * size it is (issue #236), so zooming changes it as it changes the world. What a range gizmo marks
     * round its grip; as a handle it is grabbed on its rim.
     */
    public data class Circle(val radius: Float, val normal: Axis = Axis.Z) : HandleShape
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
 * @property unitsPerPixel how many world units one view pixel covered at the handle when the drag
 *   began (issue #237): for a response that measures a drag against the handle's size on screen
 *   rather than against the world - the uniform scale box doubles a size for a drag as long as the
 *   axis boxes stand off, however big the world's units are. One when nothing measured it.
 */
public data class Drag(
    val start: WorldPoint,
    val at: WorldPoint,
    val unitsPerPixel: Float = 1f,
) {
    /** How far the drag has moved along X. */
    public val dx: Float get() = at.x - start.x

    /** How far the drag has moved along Y. */
    public val dy: Float get() = at.y - start.y

    /** How far the drag has moved along Z. */
    public val dz: Float get() = at.z - start.z

    /** How much further from [centre] the drag is now than when it began: a radius's change. */
    public fun stretchFrom(centre: WorldPoint): Float = at.distanceTo(centre) - start.distanceTo(centre)

    /** How far the drag has moved along [direction], a unit direction such as [AxisFrame.direction]. */
    public fun along(direction: WorldPoint): Float = dx * direction.x + dy * direction.y + dz * direction.z

    /**
     * How much a box centred on [centre] grows along [axis] of [axes] when its corner follows this
     * drag: twice the change in the corner's distance from the centre along that axis, because both
     * faces move. [axes] is the box's own frame; the world's by default.
     */
    public fun spread(axis: Axis, centre: WorldPoint, axes: AxisFrame = AxisFrame.WORLD): Float {
        val direction = axes.direction(axis)
        return 2f * (abs(offset(at, centre, direction)) - abs(offset(start, centre, direction)))
    }

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

    /**
     * The angle in radians the drag has turned about the line through [centre] along the unit
     * [axis], right-handed - anticlockwise looking back down [axis] - the short way round (issue
     * #237): what a ring about any axis turns by. Each end of the drag is measured where it lies
     * across the plane square to [axis], so a drag along the axis is no turn at all.
     */
    public fun turnAbout(centre: WorldPoint, axis: WorldPoint): Float {
        val ax = start.x - centre.x
        val ay = start.y - centre.y
        val az = start.z - centre.z
        val bx = at.x - centre.x
        val by = at.y - centre.y
        val bz = at.z - centre.z
        // (a x b) . axis is sin times both lengths across the plane; a . b less its part along the axis is cos.
        val sine = (ay * bz - az * by) * axis.x + (az * bx - ax * bz) * axis.y + (ax * by - ay * bx) * axis.z
        val alongA = ax * axis.x + ay * axis.y + az * axis.z
        val alongB = bx * axis.x + by * axis.y + bz * axis.z
        val cosine = ax * bx + ay * by + az * bz - alongA * alongB
        return atan2(sine, cosine)
    }

    /** How far [point] is from [centre] along [direction]. */
    private fun offset(point: WorldPoint, centre: WorldPoint, direction: WorldPoint): Float =
        (point.x - centre.x) * direction.x + (point.y - centre.y) * direction.y + (point.z - centre.z) * direction.z

    private companion object {
        const val PI_F: Float = PI.toFloat()
    }
}
