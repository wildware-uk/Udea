package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import kotlin.math.abs
import kotlin.reflect.KMutableProperty1

/*
 * The editor's built-in 2D gizmos (issue #236): move, resize, rotate, and the radius and range rings.
 *
 * Each is a public function on [GizmoScope] that declares handles through [GizmoScope.handle] and
 * marks through [GizmoScope.mark], and nothing else - so a built-in has no private path. The
 * gizmos `udea-codegen` generates from `@PositionHandle`, `@SizeHandle`, `@RotationHandle`,
 * `@RadiusHandle` and `@RangeHandle` are one call to one of these, and a hand-written gizmo calls
 * the same function the same way.
 *
 * Every function takes a field twice: as a property reference, which names it for the write, and as
 * the value the caller read off the component, which the handles are placed from and every drag is
 * measured against. The editor keeps the handle it pressed for the whole drag while the drag changes
 * the component live, so a response that read the component again would compound its own writes.
 *
 * Every handle is aligned to [GizmoTarget.axes], so the editor's world/local switch turns them all.
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
