package dev.wildware.moba.physics

import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Capsule
import dev.wildware.udea.core.physics.Chain
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.ShapeComponent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The precise half of [Box2DPhysicsWorld.overlap].
 *
 * ## Why this is written rather than delegated
 *
 * Box2D's `QueryAABB` is a broadphase: it reports every fixture whose **fat** AABB - the axis
 * aligned box Box2D pads by `aabbExtension` so a moving body does not re-insert into the tree
 * every step - touches the query box. Answering an overlap query with that set alone reports
 * bodies that are visibly not touching, and for the crowd separation this game uses it for,
 * "visibly not touching" is a unit being shoved by somebody a metre away.
 *
 * The exact test is `b2Distance`, and `gdx-box2d` does not bind it. Neither does it expose a
 * fixture's shape back to Java in a usable form. So the shapes each body was created from are
 * retained by [Box2DPhysicsWorld.Slot] and the narrowphase is done here, in Kotlin, against the
 * body's live pose.
 *
 * ## What is exact and what is not - stated, because the difference is a gameplay bug
 *
 * | query shape | target shape | test |
 * |---|---|---|
 * | [Circle] | [Circle] | exact |
 * | [Circle] | [Box] | exact, oriented |
 * | [Circle] | [Capsule] | exact, oriented |
 * | [Circle] | [Chain] | exact, per segment |
 * | [Box] | [Circle], [Capsule] | exact, oriented |
 * | [Box] | [Box] | exact while both are axis-aligned; **bounding circles** otherwise |
 * | [Box] | [Chain] | **bounding circle of the box against each segment** |
 * | [Capsule], [Chain] as the *query* | anything | **bounding circle** |
 *
 * A bounding-circle row is conservative: it never misses an overlap and it can report one that
 * is not there. That is the safe direction for a sensor query and the wrong direction for a
 * separation force, which is why the game queries with a [Circle] - every row it touches is
 * exact. The remaining rows are a real limit of this backend rather than an approximation
 * nobody noticed; `OverlapNarrowphaseTest` pins each of them, including the conservative ones,
 * so widening the table later is a visible change.
 *
 * Allocation-free: every method here takes floats and returns a boolean.
 */
internal object OverlapNarrowphase {

    /** The radius of the smallest circle at the shape's origin that contains it. */
    fun boundingRadius(shape: ShapeComponent): Float = when (shape) {
        is Circle -> shape.radius
        is Box -> sqrt(shape.halfWidth * shape.halfWidth + shape.halfHeight * shape.halfHeight)
        is Capsule -> shape.halfHeight + shape.radius
        is Chain -> {
            var furthest = 0f
            val vertices = shape.vertices
            var index = 0
            while (index < vertices.size) {
                val x = vertices[index]
                val y = vertices[index + 1]
                furthest = max(furthest, sqrt(x * x + y * y))
                index += 2
            }
            furthest
        }
    }

    /** True when any of [target]'s shapes, at its pose, touches [queryShape] at its pose. */
    fun overlaps(
        queryShape: ShapeComponent,
        queryX: Float,
        queryY: Float,
        queryAngle: Float,
        target: Box2DPhysicsWorld.Slot,
        targetX: Float,
        targetY: Float,
        targetAngle: Float,
    ): Boolean {
        for (shape in target.shapes) {
            if (pair(queryShape, queryX, queryY, queryAngle, shape, targetX, targetY, targetAngle)) {
                return true
            }
        }
        return false
    }

    /** One shape against one shape, dispatched on the pair. See the table on the object. */
    fun pair(
        a: ShapeComponent,
        ax: Float,
        ay: Float,
        aAngle: Float,
        b: ShapeComponent,
        bx: Float,
        by: Float,
        bAngle: Float,
    ): Boolean = when {
        a is Circle -> circleAgainst(ax, ay, a.radius, b, bx, by, bAngle)
        b is Circle -> circleAgainst(bx, by, b.radius, a, ax, ay, aAngle)

        a is Box && b is Box ->
            boxAgainstBox(a.halfWidth, a.halfHeight, ax, ay, aAngle, b.halfWidth, b.halfHeight, bx, by, bAngle)
        a is Box && b is Capsule -> capsuleAgainstBox(b, bx, by, bAngle, a, ax, ay, aAngle)
        b is Box && a is Capsule -> capsuleAgainstBox(a, ax, ay, aAngle, b, bx, by, bAngle)

        // Every remaining pair has a capsule or a chain on both sides, or a chain as the query
        // shape. Conservative, and the table on this object says so.
        else -> boundingCircles(a, ax, ay, b, bx, by)
    }

    /** A circle at `(cx, cy)` of [radius] against [shape] posed at `(sx, sy, sAngle)`. */
    private fun circleAgainst(
        cx: Float,
        cy: Float,
        radius: Float,
        shape: ShapeComponent,
        sx: Float,
        sy: Float,
        sAngle: Float,
    ): Boolean = when (shape) {
        is Circle -> withinDistance(cx, cy, sx, sy, radius + shape.radius)

        is Box -> circleTouchesBox(cx, cy, radius, sx, sy, sAngle, shape.halfWidth, shape.halfHeight)

        // A capsule is a box capped by two circles, and it is tested as exactly that rather
        // than as a segment-with-radius: the two are the same set, and this way the code and
        // the fixtures Box2D was given describe the same shape.
        is Capsule -> {
            val capX = -sin(sAngle) * shape.halfHeight
            val capY = cos(sAngle) * shape.halfHeight
            circleTouchesBox(cx, cy, radius, sx, sy, sAngle, shape.radius, shape.halfHeight) ||
                withinDistance(cx, cy, sx + capX, sy + capY, radius + shape.radius) ||
                withinDistance(cx, cy, sx - capX, sy - capY, radius + shape.radius)
        }

        is Chain -> chainTouchesCircle(shape, sx, sy, sAngle, cx, cy, radius)
    }

    /** A capsule against an oriented box: the capsule's own box, plus its two caps. */
    private fun capsuleAgainstBox(
        capsule: Capsule,
        capsuleX: Float,
        capsuleY: Float,
        capsuleAngle: Float,
        box: Box,
        boxX: Float,
        boxY: Float,
        boxAngle: Float,
    ): Boolean {
        val capX = -sin(capsuleAngle) * capsule.halfHeight
        val capY = cos(capsuleAngle) * capsule.halfHeight
        if (circleTouchesBox(capsuleX + capX, capsuleY + capY, capsule.radius, boxX, boxY, boxAngle, box.halfWidth, box.halfHeight)) {
            return true
        }
        if (circleTouchesBox(capsuleX - capX, capsuleY - capY, capsule.radius, boxX, boxY, boxAngle, box.halfWidth, box.halfHeight)) {
            return true
        }
        // The shaft. Two boxes, and the axis-aligned case is exact; the oriented one falls back
        // to bounding circles, which is the row the table calls conservative. Passed as floats
        // rather than as a fresh `Box`, because this runs once per candidate per query.
        return boxAgainstBox(
            capsule.radius,
            capsule.halfHeight,
            capsuleX,
            capsuleY,
            capsuleAngle,
            box.halfWidth,
            box.halfHeight,
            boxX,
            boxY,
            boxAngle,
        )
    }

    /** Two boxes: an exact interval test while both are axis-aligned, bounding circles if not. */
    private fun boxAgainstBox(
        aHalfWidth: Float,
        aHalfHeight: Float,
        ax: Float,
        ay: Float,
        aAngle: Float,
        bHalfWidth: Float,
        bHalfHeight: Float,
        bx: Float,
        by: Float,
        bAngle: Float,
    ): Boolean {
        if (abs(aAngle) > AXIS_ALIGNED_EPSILON || abs(bAngle) > AXIS_ALIGNED_EPSILON) {
            val aRadius = sqrt(aHalfWidth * aHalfWidth + aHalfHeight * aHalfHeight)
            val bRadius = sqrt(bHalfWidth * bHalfWidth + bHalfHeight * bHalfHeight)
            return withinDistance(ax, ay, bx, by, aRadius + bRadius)
        }
        return abs(ax - bx) <= aHalfWidth + bHalfWidth && abs(ay - by) <= aHalfHeight + bHalfHeight
    }

    /** The conservative fallback: each shape replaced by the circle that contains it. */
    private fun boundingCircles(
        a: ShapeComponent,
        ax: Float,
        ay: Float,
        b: ShapeComponent,
        bx: Float,
        by: Float,
    ): Boolean = withinDistance(ax, ay, bx, by, boundingRadius(a) + boundingRadius(b))

    /**
     * A circle against an oriented box.
     *
     * The circle's centre is rotated into the box's frame, clamped to the box, and the distance
     * back to the centre compared with the radius - the standard closest-point test, exact for
     * every angle, and the reason `circle` is the shape the game queries with.
     */
    private fun circleTouchesBox(
        cx: Float,
        cy: Float,
        radius: Float,
        boxX: Float,
        boxY: Float,
        boxAngle: Float,
        halfWidth: Float,
        halfHeight: Float,
    ): Boolean {
        val cosAngle = cos(boxAngle)
        val sinAngle = sin(boxAngle)
        val dx = cx - boxX
        val dy = cy - boxY
        val localX = dx * cosAngle + dy * sinAngle
        val localY = -dx * sinAngle + dy * cosAngle
        val clampedX = localX.coerceIn(-halfWidth, halfWidth)
        val clampedY = localY.coerceIn(-halfHeight, halfHeight)
        val offsetX = localX - clampedX
        val offsetY = localY - clampedY
        return offsetX * offsetX + offsetY * offsetY <= radius * radius
    }

    /** A chain against a circle: exact, because a chain is a list of segments. */
    private fun chainTouchesCircle(
        chain: Chain,
        chainX: Float,
        chainY: Float,
        chainAngle: Float,
        cx: Float,
        cy: Float,
        radius: Float,
    ): Boolean {
        val vertices = chain.vertices
        if (vertices.size < 4) return false
        val cosAngle = cos(chainAngle)
        val sinAngle = sin(chainAngle)
        var index = 0
        var previousX = 0f
        var previousY = 0f
        while (index < vertices.size) {
            val localX = vertices[index]
            val localY = vertices[index + 1]
            val worldX = chainX + localX * cosAngle - localY * sinAngle
            val worldY = chainY + localX * sinAngle + localY * cosAngle
            if (index > 0 && segmentWithin(previousX, previousY, worldX, worldY, cx, cy, radius)) return true
            previousX = worldX
            previousY = worldY
            index += 2
        }
        return false
    }

    /** True when `(px, py)` is within [radius] of the segment `(x0, y0) -> (x1, y1)`. */
    private fun segmentWithin(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        px: Float,
        py: Float,
        radius: Float,
    ): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0f) 0f else (((px - x0) * dx + (py - y0) * dy) / lengthSquared).coerceIn(0f, 1f)
        val nearestX = x0 + dx * t
        val nearestY = y0 + dy * t
        return withinDistance(px, py, nearestX, nearestY, radius)
    }

    private fun withinDistance(ax: Float, ay: Float, bx: Float, by: Float, distance: Float): Boolean {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy <= distance * distance
    }

    /** Below this many radians a box counts as axis aligned. About a fifth of a degree. */
    private const val AXIS_ALIGNED_EPSILON: Float = 3e-3f
}
