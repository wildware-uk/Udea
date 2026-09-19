package dev.wildware.udea.editor

import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.editor.gizmo.AxisFrame
import dev.wildware.udea.editor.gizmo.HandlePainter
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.render.view.ViewPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where to aim a pointer at the built-in 3D gizmos (issue #237), for the tests that drag them: world
 * points on each handle, found through the view's own projection ([drawnAt]) and nothing else.
 *
 * A drag aimed by world points has an answer worked out in the world - how far between two points on
 * an arrow, what angle between two on a ring - so a test never reads its expected change off pixels.
 * What this does know of the handle painter is how big it draws things, in pixels ([HandlePainter]'s
 * constants), and that it sizes a ring or a plane square by the longest of its three axes on screen.
 */
internal class GizmoAim(private val drawnAt: (WorldPoint) -> ViewPoint) {

    /** The world distance along [direction] from [from] that is drawn [pixels] view pixels away from it. */
    fun pixelsOut(from: WorldPoint, direction: WorldPoint, pixels: Float, farthest: Float = 4f): Float {
        val start = drawnAt(from)
        var low = 0f
        var high = farthest
        repeat(SEARCH_STEPS) {
            val mid = (low + high) / 2f
            if (apart(drawnAt(from.plus(direction, mid)), start) < pixels) low = mid else high = mid
        }
        return (low + high) / 2f
    }

    /** World units per view pixel as the painter sizes a handle in [axes] at [at]: by the longest axis drawn there. */
    fun worldPerPixel(at: WorldPoint, axes: AxisFrame = AxisFrame.WORLD): Float {
        val start = drawnAt(at)
        return 1f / listOf(axes.x, axes.y, axes.z).maxOf { apart(drawnAt(at.plus(it, 1f)), start) }
    }

    /** The middle of the plane square across [u] and [v] at [at]: halfway between its near and far sides. */
    fun planeTab(at: WorldPoint, u: WorldPoint, v: WorldPoint): WorldPoint {
        val out = worldPerPixel(at) * (HandlePainter.TAB_NEAR + HandlePainter.TAB_FAR) / 2f
        return at.plus(u, out).plus(v, out)
    }

    /**
     * The three rings [t] shows, in the order its gizmo declares them - X's, Y's, Z's - each as the
     * two directions its circle lies across, right-handed about its axis, and its world radius.
     */
    fun rings(t: Transform3D): List<Ring> {
        val centre = WorldPoint(t.x, t.y, t.z)
        return listOf(
            AxisFrame.euler(t.rotationX, t.rotationY, t.rotationZ).let { Ring(centre, it.y, it.z, radius(centre, it)) },
            AxisFrame.euler(0f, t.rotationY, t.rotationZ).let { Ring(centre, it.z, it.x, radius(centre, it)) },
            AxisFrame.euler(0f, 0f, t.rotationZ).let { Ring(centre, it.x, it.y, radius(centre, it)) },
        )
    }

    /**
     * Where round ring [index] of [rings] to take it, as a share of a whole turn: the first of
     * [GRIPS] places round it drawn outside the arrows and plane squares, which a ring seen from an
     * angle crosses and gives way to, and clear of the rings declared after it, which are drawn over
     * it and take a press where they cross. Fails when there is no such place.
     */
    fun ringGrip(rings: List<Ring>, index: Int): Float {
        val ring = rings[index]
        val centre = drawnAt(ring.centre)
        val others = rings.drop(index + 1).flatMap { other ->
            (0 until RIM_SAMPLES).map { drawnAt(other.at(it.toFloat() / RIM_SAMPLES)) }
        }
        return (0 until GRIPS).map { it.toFloat() / GRIPS }.firstOrNull { turns ->
            val grip = drawnAt(ring.at(turns))
            apart(grip, centre) >= HandlePainter.ARROW_LENGTH + CLEAR && others.all { apart(it, grip) >= CLEAR }
        } ?: error("no part of ring $index is drawn clear of the other handles")
    }

    private fun radius(centre: WorldPoint, frame: AxisFrame): Float = worldPerPixel(centre, frame) * HandlePainter.RING_RADIUS

    /** One ring: its centre, the two directions it lies across, and its world radius. */
    class Ring(val centre: WorldPoint, val u: WorldPoint, val v: WorldPoint, val radius: Float) {

        /** The point [turns] of a whole turn round the rim from [u] towards [v]. */
        fun at(turns: Float): WorldPoint {
            val angle = 2f * PI.toFloat() * turns
            return centre.plus(u, cos(angle) * radius).plus(v, sin(angle) * radius)
        }
    }

    private companion object {
        const val SEARCH_STEPS = 50
        const val RIM_SAMPLES = 360
        const val GRIPS = 32

        /** View pixels of room round a grip: clear of the painter's grab distance with some to spare. */
        const val CLEAR = 10f

        fun apart(a: ViewPoint, b: ViewPoint): Float = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
    }
}

internal fun WorldPoint.plus(direction: WorldPoint, by: Float): WorldPoint =
    WorldPoint(x + direction.x * by, y + direction.y * by, z + direction.z * by)

internal fun WorldPoint.minus(other: WorldPoint): WorldPoint = WorldPoint(x - other.x, y - other.y, z - other.z)

internal fun WorldPoint.times(by: Float): WorldPoint = WorldPoint(x * by, y * by, z * by)

internal fun WorldPoint.length(): Float = sqrt(x * x + y * y + z * z)

internal fun dot(a: WorldPoint, b: WorldPoint): Float = a.x * b.x + a.y * b.y + a.z * b.z
