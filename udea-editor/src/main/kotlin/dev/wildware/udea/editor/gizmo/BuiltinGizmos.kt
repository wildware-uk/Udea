package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.reflect.KMutableProperty1

/*
 * The editor's built-in gizmos: in 2D (issue #236) move, resize, rotate, and the radius and range
 * rings; in 3D (issue #237) translate, rotate and scale.
 *
 * Each is a public function on [GizmoScope] that declares handles through [GizmoScope.handle] and
 * marks through [GizmoScope.mark], and nothing else - so a built-in has no private path. The
 * gizmos `udea-codegen` generates from `@PositionHandle`, `@SizeHandle`, `@RotationHandle`,
 * `@ScaleHandle`, `@RadiusHandle` and `@RangeHandle` are one call to one of these, and a hand-written gizmo calls
 * the same function the same way.
 *
 * Every function takes a field twice: as a property reference, which names it for the write, and as
 * the value the caller read off the component, which the handles are placed from and every drag is
 * measured against. The editor keeps the handle it pressed for the whole drag while the drag changes
 * the component live, so a response that read the component again would compound its own writes.
 *
 * Every handle is aligned to [GizmoTarget.axes], so the editor's world/local switch turns them all -
 * except [rotationRings]', which says why.
 */

/**
 * Moves an entity on the ground plane: an arrow along each of the target's X and Y, which holds the
 * drag to that line, and a square between them for a free drag. Each moves the entity by exactly how
 * far the drag moved, snapped to the grid.
 *
 * An arrow writes only the fields its line can change: with the world's axes the X arrow writes [x]
 * alone, so snapping the drag never nudges [y] sideways onto the grid. An arrow turned with the entity
 * runs across both, and writes both.
 */
public fun <C : Component<C>> GizmoScope<C>.moveHandles(
    target: GizmoTarget<C>,
    x: KMutableProperty1<C, Float>,
    x0: Float,
    y: KMutableProperty1<C, Float>,
    y0: Float,
) {
    val at = WorldPoint(x0, y0, 0f)
    fun move(acrossX: Boolean, acrossY: Boolean): DragScope<C>.(Drag) -> Unit = { drag ->
        if (acrossX) write(x, x0 + drag.dx, Snap.Grid)
        if (acrossY) write(y, y0 + drag.dy, Snap.Grid)
    }
    for (axis in listOf(Axis.X, Axis.Y)) {
        val direction = target.axes.direction(axis)
        val arrow = move(abs(direction.x) > ACROSS, abs(direction.y) > ACROSS)
        handle(at, HandleShape.Arrow(axis), DragConstraint.Along(axis), target.axes, arrow)
    }
    handle(at, HandleShape.PlaneSquare(Plane.XY), DragConstraint.Across(Plane.XY), target.axes, move(acrossX = true, acrossY = true))
}

/** Less of a world axis than this in an arrow's direction, and a drag along the arrow does not change it. */
private const val ACROSS: Float = 1e-6f

/**
 * Moves an entity in 3D (issue #237): an arrow along each of the target's X, Y and Z, which holds the
 * drag to that line, and a square in each of its XY, YZ and XZ planes, which holds it to that plane.
 * Each moves the entity by exactly how far the drag moved, snapped to the grid.
 *
 * Like [moveHandles], a handle writes only the fields its line or plane can change: with the world's
 * axes the X arrow writes [x] alone and the XY square [x] and [y], so snapping never nudges a field
 * the handle cannot move. Turned with the entity, a handle writes every field it runs across.
 */
public fun <C : Component<C>> GizmoScope<C>.translateHandles(
    target: GizmoTarget<C>,
    x: KMutableProperty1<C, Float>,
    x0: Float,
    y: KMutableProperty1<C, Float>,
    y0: Float,
    z: KMutableProperty1<C, Float>,
    z0: Float,
) {
    val at = WorldPoint(x0, y0, z0)
    val axes = target.axes
    /** The move along [directions]: each field any of them has some of, moved by the drag. */
    fun move(vararg directions: Axis): DragScope<C>.(Drag) -> Unit {
        val runs = directions.map(axes::direction)
        val acrossX = runs.any { abs(it.x) > ACROSS }
        val acrossY = runs.any { abs(it.y) > ACROSS }
        val acrossZ = runs.any { abs(it.z) > ACROSS }
        return { drag ->
            if (acrossX) write(x, x0 + drag.dx, Snap.Grid)
            if (acrossY) write(y, y0 + drag.dy, Snap.Grid)
            if (acrossZ) write(z, z0 + drag.dz, Snap.Grid)
        }
    }
    for (axis in Axis.entries) handle(at, HandleShape.Arrow(axis), DragConstraint.Along(axis), axes, move(axis))
    for (plane in TAB_PLANES) {
        val (first, second) = axesOf(plane)
        handle(at, HandleShape.PlaneTab(plane), DragConstraint.Across(plane), axes, move(first, second))
    }
}

/** The order the plane squares are declared in: the ground plane first, then the two upright ones. */
private val TAB_PLANES: List<Plane> = listOf(Plane.XY, Plane.YZ, Plane.XZ)

/** The two axes [plane] runs along. */
private fun axesOf(plane: Plane): Pair<Axis, Axis> = when (plane) {
    Plane.XY -> Axis.X to Axis.Y
    Plane.YZ -> Axis.Y to Axis.Z
    Plane.XZ -> Axis.X to Axis.Z
}

/** The plane square to [axis]: what a ring about it is dragged across. */
private fun planeSquareTo(axis: Axis): Plane = when (axis) {
    Axis.X -> Plane.YZ
    Axis.Y -> Plane.XZ
    Axis.Z -> Plane.XY
}

/**
 * Turns an entity in 3D (issue #237): a ring about each of the three axes its angles turn it about,
 * [rotationX], [rotationY] and [rotationZ] - radians, applied about X first, then Y, then Z, the order
 * `Transform3D`'s are. Dragging a ring turns **its own field alone**, by the angle the drag went round
 * the ring's axis, snapped to the angle step.
 *
 * So each ring is drawn about the axis its field really turns the entity about, which is not always a
 * world axis: [rotationZ] is applied last, so its ring is about Z; [rotationY] is applied before
 * the turn about Z, so its ring is about Y turned by [rotationZ]; [rotationX] is applied first, so its
 * ring is about X turned by both of the others. The rings follow the entity's angles whether the
 * editor's axes switch is on world or local - with no turn they are the world's axes - because a ring
 * about any other axis would have to change more than one angle to turn the entity about it.
 */
public fun <C : Component<C>> GizmoScope<C>.rotationRings(
    target: GizmoTarget<C>,
    rotationX: KMutableProperty1<C, Float>,
    rotationX0: Float,
    rotationY: KMutableProperty1<C, Float>,
    rotationY0: Float,
    rotationZ: KMutableProperty1<C, Float>,
    rotationZ0: Float,
) {
    val origin = target.origin
    // Each frame's own axis is the ring's: X of the whole turn, Y of the turns after X, Z of none but Z.
    val rings = listOf(
        Triple(Axis.X, rotationX to rotationX0, AxisFrame.euler(rotationX0, rotationY0, rotationZ0)),
        Triple(Axis.Y, rotationY to rotationY0, AxisFrame.euler(0f, rotationY0, rotationZ0)),
        Triple(Axis.Z, rotationZ to rotationZ0, AxisFrame.euler(0f, 0f, rotationZ0)),
    )
    for ((axis, field, frame) in rings) {
        val (property, value0) = field
        val about = frame.direction(axis)
        handle(origin, HandleShape.Ring(axis), DragConstraint.Across(planeSquareTo(axis)), frame) { drag ->
            write(property, value0 + drag.turnAbout(origin, about), Snap.Angle)
        }
    }
}

/**
 * Scales an entity in 3D (issue #237): a box out along each of the target's X, Y and Z, and one in the
 * middle.
 *
 * - **An axis box** multiplies its own field by how much further from the entity the drag is, along
 *   that axis, than where it began: grabbed and pulled half as far out again, half as big again;
 *   pushed through the middle, nothing, never less.
 * - **The middle box** multiplies all three by one factor: a drag up and to the right - along world X
 *   and Z together, which is up and to the right from the editor's cameras - as long as the axis boxes
 *   stand off from the middle doubles every field, and as long the other way leaves nothing.
 *   Measured on screen ([Drag.unitsPerPixel]), since a scale has no world size to measure against.
 *
 * Scales are factors, so they are never snapped: the grid is a distance.
 */
public fun <C : Component<C>> GizmoScope<C>.scaleHandles(
    target: GizmoTarget<C>,
    x: KMutableProperty1<C, Float>,
    x0: Float,
    y: KMutableProperty1<C, Float>,
    y0: Float,
    z: KMutableProperty1<C, Float>,
    z0: Float,
) {
    val origin = target.origin
    val axes = target.axes
    val fields = listOf(x to x0, y to y0, z to z0)
    for ((axis, field) in Axis.entries.zip(fields)) {
        val (property, value0) = field
        val direction = axes.direction(axis)
        handle(origin, HandleShape.ScaleBox(axis), DragConstraint.Along(axis), axes) { drag ->
            write(property, maxOf(0f, value0 * drag.ratioAlong(origin, direction)))
        }
    }
    handle(origin, HandleShape.UniformBox, DragConstraint.ViewPlane, axes) { drag ->
        val factor = maxOf(0f, 1f + drag.along(UP_AND_RIGHT) / (HandlePainter.SCALE_REACH * drag.unitsPerPixel))
        for ((property, value0) in fields) write(property, value0 * factor)
    }
}

/** How much further from [centre] along [direction] the drag is than where it began, as a ratio; one when it began on [centre]. */
private fun Drag.ratioAlong(centre: WorldPoint, direction: WorldPoint): Float {
    fun offset(point: WorldPoint): Float =
        (point.x - centre.x) * direction.x + (point.y - centre.y) * direction.y + (point.z - centre.z) * direction.z
    val from = offset(start)
    return if (abs(from) < ACROSS) 1f else offset(at) / from
}

/** World X and Z together, as a unit direction: up and to the right from the editor's 2D and 3D cameras. */
private val UP_AND_RIGHT: WorldPoint = (1f / sqrt(2f)).let { WorldPoint(it, 0f, it) }

/**
 * Resizes a box centred on the target's origin, [width] along the target's X and [height] along its
 * Y: a handle on each corner, which changes both, and on the middle of each side, which changes the
 * one that side faces along. Both faces move with a drag, so the box stays centred. An outline
 * between the corners shows the box being changed. Sizes are never negative, and snap to the grid.
 */
public fun <C : Component<C>> GizmoScope<C>.sizeHandles(
    target: GizmoTarget<C>,
    width: KMutableProperty1<C, Float>,
    width0: Float,
    height: KMutableProperty1<C, Float>,
    height0: Float,
) {
    val origin = target.origin
    val axes = target.axes
    val across = axes.direction(Axis.X)
    val up = axes.direction(Axis.Y)
    // Anticlockwise from the corner at +X, +Y.
    val corners = CORNER_SIGNS.map { (sx, sy) -> origin.plus(across, sx * width0 / 2f).plus(up, sy * height0 / 2f) }
    for (index in corners.indices) mark(corners[index], HandleShape.Line(corners[(index + 1) % corners.size]))

    val widthFrom: DragScope<C>.(Drag) -> Unit = { drag -> write(width, maxOf(0f, width0 + drag.spread(Axis.X, origin, axes)), Snap.Grid) }
    val heightFrom: DragScope<C>.(Drag) -> Unit = { drag -> write(height, maxOf(0f, height0 + drag.spread(Axis.Y, origin, axes)), Snap.Grid) }
    for (corner in corners) {
        handle(corner, HandleShape.BoxCorner, DragConstraint.Across(Plane.XY), axes) { drag ->
            widthFrom(drag)
            heightFrom(drag)
        }
    }
    for (sign in SIDE_SIGNS) handle(origin.plus(across, sign * width0 / 2f), HandleShape.BoxEdge(Axis.X), DragConstraint.Along(Axis.X), axes, widthFrom)
    for (sign in SIDE_SIGNS) handle(origin.plus(up, sign * height0 / 2f), HandleShape.BoxEdge(Axis.Y), DragConstraint.Along(Axis.Y), axes, heightFrom)
}

/**
 * Turns an entity about the up axis: a ring round the target's origin. The drag turns [rotation], in
 * radians anticlockwise seen from above, by the angle it went round the origin, snapped to the angle
 * step.
 */
public fun <C : Component<C>> GizmoScope<C>.rotationHandle(
    target: GizmoTarget<C>,
    rotation: KMutableProperty1<C, Float>,
    rotation0: Float,
) {
    val origin = target.origin
    handle(origin, HandleShape.Ring(Axis.Z), DragConstraint.Across(Plane.XY), target.axes) { drag ->
        write(rotation, rotation0 + drag.turnAbout(origin), Snap.Angle)
    }
}

/**
 * A radius round the target's origin - a body's own size, a collider: a circle of that radius, and a
 * dot on its rim along the target's X that sets [radius] to its distance from the origin.
 */
public fun <C : Component<C>> GizmoScope<C>.radiusHandle(
    target: GizmoTarget<C>,
    radius: KMutableProperty1<C, Float>,
    radius0: Float,
) {
    reach(target, radius, radius0, HandleShape.Point)
}

/**
 * A range round the target's origin - an attack range, an aggro radius: a circle of that range, and a
 * grip on its rim along the target's X, with a spoke back to the origin, that sets [range] to its
 * distance from the origin. The same drag as [radiusHandle], drawn as somewhere the entity reaches
 * rather than something it is.
 */
public fun <C : Component<C>> GizmoScope<C>.rangeHandle(
    target: GizmoTarget<C>,
    range: KMutableProperty1<C, Float>,
    range0: Float,
) {
    reach(target, range, range0, HandleShape.Line(target.origin))
}

/** [radiusHandle] and [rangeHandle], which differ only in the grip drawn on the rim. */
private fun <C : Component<C>> GizmoScope<C>.reach(
    target: GizmoTarget<C>,
    field: KMutableProperty1<C, Float>,
    value0: Float,
    grip: HandleShape,
) {
    val origin = target.origin
    mark(origin, HandleShape.Circle(value0))
    val rim = origin.plus(target.axes.direction(Axis.X), value0)
    handle(rim, grip, DragConstraint.Along(Axis.X), target.axes) { drag ->
        write(field, maxOf(0f, value0 + drag.stretchFrom(origin)), Snap.Grid)
    }
}

/** This point moved [distance] along the unit [direction]. */
private fun WorldPoint.plus(direction: WorldPoint, distance: Float): WorldPoint =
    WorldPoint(x + direction.x * distance, y + direction.y * distance, z + direction.z * distance)

/** A box's corners, as which side of the centre each is on along X and Y, anticlockwise from +X +Y. */
private val CORNER_SIGNS: List<Pair<Float, Float>> = listOf(1f to 1f, -1f to 1f, -1f to -1f, 1f to -1f)

/** The two sides of a box along one axis: the far one first. */
private val SIDE_SIGNS: List<Float> = listOf(1f, -1f)
