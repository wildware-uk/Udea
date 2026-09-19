package dev.wildware.udea.editor.gizmo

import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.ViewPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the editor draws every [HandleShape] and [Mark], and where a press takes a handle (issue #236).
 *
 * One path for every gizmo, the built-ins and a game's alike: a gizmo declares a shape at a world
 * point in a frame of axes, and this draws it and hit-tests it, so a built-in gets nothing a game's
 * gizmo does not.
 *
 * **Handles keep their size on screen.** Each is measured in view pixels from the point its world
 * position projects to, so zooming out never shrinks one past being grabbed. An arrow or a ring finds
 * which way its axes run on screen by projecting a step along them, and nothing else about the camera,
 * so the same code draws a handle in the 2D view and in the 3D one. A [HandleShape.Circle] is the one
 * world-sized shape: a range ring is drawn at the range. A press takes a handle where it is drawn: within [GRAB] pixels of an
 * arrow's shaft, a ring's rim, or a grip's square.
 *
 * Render thread only, like the canvas; the scratch points are reused rather than allocated per frame.
 */
internal class HandlePainter {

    private val from = ViewPoint()
    private val to = ViewPoint()
    private val step = ViewPoint()

    // --- drawing -------------------------------------------------------------------------------

    /**
     * Draws [mark] in the mark colour with a dark edge: a line to its far end, a circle at its world
     * size, and any other shape as a dot - a mark is a place to look, and an arrow's direction means
     * nothing to a hand that cannot grab it. The one way a mark is drawn, the bone overlay's included.
     */
    fun draw(canvas: GizmoCanvas, mark: Mark) {
        when (val shape = mark.shape) {
            is HandleShape.Line -> if (canvas.project(mark.at, from) && canvas.project(shape.to, to)) {
                markLine(canvas, from.x, from.y, to.x, to.y)
            }
            is HandleShape.Circle -> ring(canvas, mark.at, AxisFrame.WORLD, shape.normal, shape.radius) { x0, y0, x1, y1 ->
                markLine(canvas, x0, y0, x1, y1)
            }
            HandleShape.Point, HandleShape.BoxCorner, HandleShape.Sphere,
            is HandleShape.Arrow, is HandleShape.PlaneSquare, is HandleShape.Ring, is HandleShape.BoxEdge,
            -> if (canvas.project(mark.at, from)) {
                val outer = MARK_DOT + 2f * MARK_EDGE
                canvas.fill(from.x - outer / 2f, from.y - outer / 2f, outer, outer, MARK_OUTLINE)
                canvas.fill(from.x - MARK_DOT / 2f, from.y - MARK_DOT / 2f, MARK_DOT, MARK_DOT, MARK)
            }
        }
    }

    /** Draws [marks]: every line and circle first, so each dot sits on top of the lines that meet at it. */
    fun draw(canvas: GizmoCanvas, marks: List<Mark>) {
        for (mark in marks) if (mark.shape.isStroke()) draw(canvas, mark)
        for (mark in marks) if (!mark.shape.isStroke()) draw(canvas, mark)
    }

    private fun HandleShape.isStroke(): Boolean = this is HandleShape.Line || this is HandleShape.Circle

    private fun markLine(canvas: GizmoCanvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        canvas.line(x0, y0, x1, y1, MARK_LINE + 2f * MARK_EDGE, MARK_OUTLINE)
        canvas.line(x0, y0, x1, y1, MARK_LINE, MARK)
    }

    /** Draws a handle of [shape] at [at], its axes [axes]; [lit] when it is the one being dragged. */
    fun draw(canvas: GizmoCanvas, at: WorldPoint, shape: HandleShape, axes: AxisFrame, lit: Boolean) {
        if (!canvas.project(at, from)) return
        val x = from.x
        val y = from.y
        when (shape) {
            is HandleShape.Arrow -> {
                if (!onScreen(canvas, at, axes.direction(shape.axis))) return
                val colour = if (lit) LIT else colourOf(shape.axis)
                val tipX = x + step.x * ARROW_LENGTH
                val tipY = y + step.y * ARROW_LENGTH
                stroke(canvas, x + step.x * ARROW_START, y + step.y * ARROW_START, tipX, tipY, colour)
                // The head: two strokes back from the tip, either side of the shaft.
                for (side in HEAD_SIDES) {
                    val backX = -step.x * HEAD_COS - side * step.y * HEAD_SIN
                    val backY = -step.y * HEAD_COS + side * step.x * HEAD_SIN
                    stroke(canvas, tipX, tipY, tipX + backX * HEAD_LENGTH, tipY + backY * HEAD_LENGTH, colour)
                }
            }
            is HandleShape.PlaneSquare -> {
                val half = SQUARE / 2f
                canvas.fill(x - half, y - half, SQUARE, SQUARE, if (lit) LIT_FILL else PLANE_FILL)
                outline(canvas, x, y, half, if (lit) LIT else PLANE)
            }
            is HandleShape.Ring -> {
                val colour = if (lit) LIT else colourOf(shape.normal)
                ring(canvas, at, axes, shape.normal, radius = null) { x0, y0, x1, y1 -> stroke(canvas, x0, y0, x1, y1, colour) }
            }
            is HandleShape.Line -> {
                if (canvas.project(shape.to, to)) stroke(canvas, x, y, to.x, to.y, if (lit) LIT else GRIP)
                grip(canvas, x, y, DOT, if (lit) LIT else GRIP)
            }
            HandleShape.Point -> grip(canvas, x, y, DOT, if (lit) LIT else GRIP)
            HandleShape.Sphere -> grip(canvas, x, y, SPHERE, if (lit) LIT else GRIP)
            HandleShape.BoxCorner -> grip(canvas, x, y, DOT, if (lit) LIT else BOX)
            is HandleShape.BoxEdge -> grip(canvas, x, y, EDGE, if (lit) LIT else BOX)
            is HandleShape.Circle -> {
                val colour = if (lit) LIT else GRIP
                ring(canvas, at, axes, shape.normal, shape.radius) { x0, y0, x1, y1 -> stroke(canvas, x0, y0, x1, y1, colour) }
            }
        }
    }

    // --- hitting -------------------------------------------------------------------------------

    /** Whether a press at view pixel ([x], [y]) lands on the handle [draw] draws for the same arguments. */
    fun hits(canvas: GizmoCanvas, at: WorldPoint, shape: HandleShape, axes: AxisFrame, x: Float, y: Float): Boolean {
        if (!canvas.project(at, from)) return false
        val cx = from.x
        val cy = from.y
        return when (shape) {
            is HandleShape.Arrow -> onScreen(canvas, at, axes.direction(shape.axis)) &&
                distanceToSegment(x, y, cx + step.x * ARROW_START, cy + step.y * ARROW_START, cx + step.x * ARROW_LENGTH, cy + step.y * ARROW_LENGTH) <= GRAB
            is HandleShape.PlaneSquare -> within(x, y, cx, cy, SQUARE / 2f + GRAB_MARGIN)
            is HandleShape.Ring -> onRim(canvas, at, axes, shape.normal, radius = null, x, y)
            is HandleShape.Circle -> onRim(canvas, at, axes, shape.normal, shape.radius, x, y)
            HandleShape.Sphere -> within(x, y, cx, cy, SPHERE / 2f + GRAB_MARGIN)
            HandleShape.Point, HandleShape.BoxCorner, is HandleShape.BoxEdge, is HandleShape.Line -> within(x, y, cx, cy, GRAB)
        }
    }

    /** Whether ([x], [y]) is within [GRAB] pixels of the rim [ring] walks for the same arguments. */
    private fun onRim(canvas: GizmoCanvas, at: WorldPoint, axes: AxisFrame, normal: Axis, radius: Float?, x: Float, y: Float): Boolean {
        var hit = false
        ring(canvas, at, axes, normal, radius) { x0, y0, x1, y1 ->
            if (distanceToSegment(x, y, x0, y0, x1, y1) <= GRAB) hit = true
        }
        return hit
    }

    // --- geometry ------------------------------------------------------------------------------

    /**
     * Sets [step] to the unit direction on screen that world [direction] runs in from [at], and says
     * whether there is one: an axis pointing straight at the 3D eye has none.
     */
    private fun onScreen(canvas: GizmoCanvas, at: WorldPoint, direction: WorldPoint): Boolean {
        if (!canvas.project(at, from)) return false
        val probe = WorldPoint(at.x + direction.x, at.y + direction.y, at.z + direction.z)
        if (!canvas.project(probe, to)) return false
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < DEGENERATE) return false
        step.x = dx / length
        step.y = dy / length
        return true
    }

    /**
     * Walks a circle about [centre] in the plane of [axes] square to [normal], as [SEGMENTS] straight
     * pieces handed to [segment] in view pixels: of world [radius], or - when that is `null` - of
     * [RING_RADIUS] pixels on screen, which is what makes a ring handle keep its size.
     */
    private inline fun ring(
        canvas: GizmoCanvas,
        centre: WorldPoint,
        axes: AxisFrame,
        normal: Axis,
        radius: Float?,
        segment: (Float, Float, Float, Float) -> Unit,
    ) {
        val (first, second) = inPlane(normal)
        val u = axes.direction(first)
        val v = axes.direction(second)
        val worldRadius = radius ?: run {
            // How many pixels one world unit along the plane's first axis is, here.
            if (!onScreen(canvas, centre, u)) return
            val probe = WorldPoint(centre.x + u.x, centre.y + u.y, centre.z + u.z)
            canvas.project(centre, from)
            canvas.project(probe, to)
            val pixelsPerUnit = sqrt((to.x - from.x) * (to.x - from.x) + (to.y - from.y) * (to.y - from.y))
            RING_RADIUS / pixelsPerUnit
        }
        var hasLast = false
        var lastX = 0f
        var lastY = 0f
        for (index in 0..SEGMENTS) {
            val angle = index * TURN / SEGMENTS
            val c = cos(angle) * worldRadius
            val s = sin(angle) * worldRadius
            val point = WorldPoint(centre.x + u.x * c + v.x * s, centre.y + u.y * c + v.y * s, centre.z + u.z * c + v.z * s)
            if (!canvas.project(point, to)) {
                hasLast = false
                continue
            }
            if (hasLast) segment(lastX, lastY, to.x, to.y)
            lastX = to.x
            lastY = to.y
            hasLast = true
        }
    }

    private fun stroke(canvas: GizmoCanvas, x0: Float, y0: Float, x1: Float, y1: Float, colour: Rgba) {
        // A dark edge under every stroke, so a handle reads over any sprite.
        canvas.line(x0, y0, x1, y1, STROKE + 2f * EDGE_WIDTH, SHADOW)
        canvas.line(x0, y0, x1, y1, STROKE, colour)
    }

    private fun grip(canvas: GizmoCanvas, x: Float, y: Float, size: Float, colour: Rgba) {
        val half = size / 2f
        canvas.fill(x - half - EDGE_WIDTH, y - half - EDGE_WIDTH, size + 2f * EDGE_WIDTH, size + 2f * EDGE_WIDTH, SHADOW)
        canvas.fill(x - half, y - half, size, size, colour)
    }

    private fun outline(canvas: GizmoCanvas, x: Float, y: Float, half: Float, colour: Rgba) {
        stroke(canvas, x - half, y - half, x + half, y - half, colour)
        stroke(canvas, x + half, y - half, x + half, y + half, colour)
        stroke(canvas, x + half, y + half, x - half, y + half, colour)
        stroke(canvas, x - half, y + half, x - half, y - half, colour)
    }

    override fun toString(): String = "HandlePainter"

    internal companion object {

        /** How far from a shaft, a rim or a grip's centre a press still takes it, in view pixels. */
        const val GRAB: Float = 6f

        /** How much further than its drawn edge a square is still grabbed, in view pixels. */
        const val GRAB_MARGIN: Float = 2f

        /** An arrow's length from the handle's point to its tip, in view pixels. */
        const val ARROW_LENGTH: Float = 56f

        /** Where an arrow's shaft starts, in view pixels out from the point: clear of the square there. */
        const val ARROW_START: Float = 12f

        /** A ring handle's radius, in view pixels: outside the arrows, so the two never cross. */
        const val RING_RADIUS: Float = 80f

        /** The free square's side, in view pixels. */
        const val SQUARE: Float = 14f

        /** A grip's side - a point, a box corner - in view pixels. */
        const val DOT: Float = 9f

        /** A box side's grip, a little smaller than a corner's. */
        const val EDGE: Float = 7f

        /** A 3D ball's side, in view pixels. */
        const val SPHERE: Float = 12f

        /** A mark drawn as a dot - a joint - in view pixels. */
        const val MARK_DOT: Float = 7f

        /** A mark's line - a bone, a box's outline, a range - in view pixels. */
        const val MARK_LINE: Float = 2f

        /** The dark edge round every mark, in view pixels. */
        const val MARK_EDGE: Float = 1f

        const val STROKE: Float = 2.5f
        const val EDGE_WIDTH: Float = 1f

        /** An arrowhead's strokes: this long, at this angle either side of the shaft. */
        const val HEAD_LENGTH: Float = 10f
        private val HEAD_COS: Float = cos(PI / 6).toFloat()
        private val HEAD_SIN: Float = sin(PI / 6).toFloat()
        private val HEAD_SIDES = floatArrayOf(1f, -1f)

        /** Straight pieces in a drawn circle: smooth at a ring handle's size and at a range's. */
        const val SEGMENTS: Int = 64

        /** Shorter than this on screen, in pixels, and an axis is pointing at the eye. */
        const val DEGENERATE: Float = 1e-4f

        private const val TURN: Float = (2.0 * PI).toFloat()

        val X_AXIS: Rgba = Rgba.of(0.95f, 0.3f, 0.3f)
        val Y_AXIS: Rgba = Rgba.of(0.35f, 0.85f, 0.35f)
        val Z_AXIS: Rgba = Rgba.of(0.35f, 0.6f, 1f)
        val PLANE: Rgba = Rgba.of(1f, 0.9f, 0.2f)
        val PLANE_FILL: Rgba = Rgba.of(1f, 0.9f, 0.2f, 0.35f)
        val GRIP: Rgba = Rgba.of(1f, 0.9f, 0.2f)
        val BOX: Rgba = Rgba.of(0.95f, 0.95f, 0.95f)

        /** What a mark is drawn in: a warm yellow that no sprite or model in `moba` is. */
        val MARK: Rgba = Rgba.of(1f, 0.82f, 0.1f, 1f)

        /** The dark edge round every mark, so it reads over a light model and a dark floor alike. */
        val MARK_OUTLINE: Rgba = Rgba.of(0.05f, 0.05f, 0.08f, 1f)
        val LIT: Rgba = Rgba.of(1f, 1f, 1f)
        val LIT_FILL: Rgba = Rgba.of(1f, 1f, 1f, 0.45f)
        val SHADOW: Rgba = Rgba.of(0f, 0f, 0f, 0.55f)

        fun colourOf(axis: Axis): Rgba = when (axis) {
            Axis.X -> X_AXIS
            Axis.Y -> Y_AXIS
            Axis.Z -> Z_AXIS
        }

        /** The two axes a plane square to [normal] is spanned by, in right-handed order. */
        fun inPlane(normal: Axis): Pair<Axis, Axis> = when (normal) {
            Axis.X -> Axis.Y to Axis.Z
            Axis.Y -> Axis.Z to Axis.X
            Axis.Z -> Axis.X to Axis.Y
        }

        fun within(x: Float, y: Float, cx: Float, cy: Float, half: Float): Boolean = abs(x - cx) <= half && abs(y - cy) <= half

        /** The distance from ([x], [y]) to the segment from ([x0], [y0]) to ([x1], [y1]). */
        fun distanceToSegment(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0
            val dy = y1 - y0
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0f) 0f else (((x - x0) * dx + (y - y0) * dy) / lengthSquared).coerceIn(0f, 1f)
            val px = x0 + dx * t - x
            val py = y0 + dy * t - y
            return sqrt(px * px + py * py)
        }
    }
}

/** [GizmoCanvas.project] for a [WorldPoint]. */
private fun GizmoCanvas.project(point: WorldPoint, out: ViewPoint): Boolean = project(point.x, point.y, point.z, out)
