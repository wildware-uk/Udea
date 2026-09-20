package dev.wildware.udea.render.pick

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
import dev.wildware.udea.render.view.ViewPoint

/**
 * Where a world point lands on the picture, for [EntityPicker]: the one thing a picker needs from a
 * camera, and the only thing that differs between picking in the editor and picking in a game
 * (issue #262).
 *
 * The editor picks through its own orbit camera, which a drag moves; a game picks through the camera
 * it is played through, which a rig moves. Everything else about picking - which report is in front
 * of which, what a selection box touches, that an entity drawn twice is listed once - is the same
 * question with the same answer, and lives in [EntityPicker].
 *
 * ## Pixels, in whatever convention the implementation uses
 *
 * A picker only ever compares pixels with other pixels from the same projector, so it does not care
 * which corner they are measured from - and deliberately says nothing about it, because a projector
 * that quietly disagreed with its caller about which way `y` runs would produce a picker that is
 * wrong only in the top half of the screen. What matters is that [EntityPicker.under] is given a
 * point in the same convention this returns, and both shipped projectors document theirs:
 * [CameraPickProjector] is [CameraPick]'s, `y` up from the bottom.
 */
public interface PickProjector {

    /**
     * Writes into [out] the pixel the ground-plane point ([x], [y]) of a sprite's rectangle is drawn
     * at. Returns false when it is drawn nowhere.
     */
    public fun projectRect(x: Float, y: Float, out: ViewPoint): Boolean

    /**
     * Writes into [out] the pixel the world point ([x], [y], [z]) of a model's box is drawn at.
     * Returns false when it is behind the eye, and so drawn nowhere.
     */
    public fun projectBox(x: Float, y: Float, z: Float, out: ViewPoint): Boolean

    /**
     * How far in front of the eye world point ([x], [y], [z]) is, along the line of sight: what
     * decides which of two models under the pointer is in front.
     */
    public fun depthOf(x: Float, y: Float, z: Float): Float
}

/** One entity's rectangle of pixels, and what decides whether it is in front of another. */
public class PickedBounds internal constructor(
    /** The entity drawn over this rectangle. */
    public val entity: NetId,
    public val left: Float,
    public val bottom: Float,
    public val right: Float,
    public val top: Float,
    /** Which pickable source reported it, in the drawing order: a later one is drawn over an earlier. */
    internal val source: Int,
    /** How far in front of the eye a model's box centre is; zero for a sprite. */
    internal val depth: Float,
    /** The report's place among every report, so a later one is drawn later. */
    internal val order: Int,
) {

    /** Whether pixel ([x], [y]) is inside this rectangle. */
    public fun contains(x: Float, y: Float): Boolean = x in left..right && y in bottom..top

    override fun toString(): String = "PickedBounds($entity, $left..$right x $bottom..$top)"
}

/**
 * What is where on the picture: the entities the render systems report through [PickBounds], each
 * turned into the rectangle of pixels it covers, front-most first.
 *
 * ## It exists outside the editor, and that is the point of issue #262
 *
 * This is `ScenePicker` (issue #235) with the editor's camera lifted out of it into [PickProjector].
 * The editor still picks exactly as it did - `ScenePicker` is now three lines over this one - and a
 * *game* can ask the same question, which is what an RTS needs before it can target anything. There
 * is one implementation of the ordering rules rather than two that drift.
 *
 * ## Which is in front
 *
 * Of the entities under a point, the front-most comes first:
 *
 * - one reported by a source drawn later is in front of one reported by a source drawn earlier,
 *   because it is drawn over it;
 * - within one source, a model nearer the eye is in front of one further away, because the model
 *   pass is depth-tested;
 * - otherwise the one reported later is in front, because it was drawn later.
 *
 * An entity reported more than once - drawn by two systems - is listed once, where it is front-most.
 *
 * ## Threads
 *
 * The render thread: it asks the render systems, which read the world. Presentation only - reporting
 * reads the world and writes nothing, like drawing.
 */
public class EntityPicker(
    /** Turns a world point into a pixel: the camera this picks through. */
    private val projector: PickProjector,
    /**
     * The pickable sources, **in the order they are drawn**, read afresh on every pick.
     *
     * A lambda rather than a list, because the answer changes: an editor view is given its systems
     * when the pipeline opens it, and a game's list is its pipeline's, which a scene swap replaces.
     */
    private val sources: () -> List<PickBounds>,
) {

    /** Every entity drawn under pixel ([x], [y]), front-most first, each once. */
    public fun under(x: Float, y: Float): List<NetId> = frontFirst { it.contains(x, y) }

    /**
     * Every entity whose drawn rectangle touches the rectangle of pixels from ([left], [bottom]) to
     * ([right], [top]), in any corner order, each once, front-most first.
     */
    public fun touching(left: Float, bottom: Float, right: Float, top: Float): List<NetId> {
        val minX = minOf(left, right)
        val maxX = maxOf(left, right)
        val minY = minOf(bottom, top)
        val maxY = maxOf(bottom, top)
        return frontFirst { it.left <= maxX && it.right >= minX && it.bottom <= maxY && it.top >= minY }
    }

    /** Each on-screen rectangle [entities] are drawn over, for outlining a selection. */
    public fun bounds(entities: Collection<NetId>): List<PickedBounds> {
        if (entities.isEmpty()) return emptyList()
        val wanted = entities.toSet()
        return collect().filter { it.entity in wanted }
    }

    /** The entities of every rectangle [where] holds for, front-most first, each once. */
    private inline fun frontFirst(where: (PickedBounds) -> Boolean): List<NetId> =
        collect().filter(where).sortedWith(FRONT_FIRST).map { it.entity }.distinct()

    /** Asks every pickable source, in drawing order, where its entities are. */
    private fun collect(): List<PickedBounds> {
        val sink = Collector()
        val found = sources()
        for (index in found.indices) {
            sink.source = index
            found[index].reportPickBounds(sink)
        }
        return sink.found
    }

    override fun toString(): String = "EntityPicker(${sources().size} pickable source(s), $projector)"

    /** Turns each report into pixels as it arrives. */
    private inner class Collector : PickSink {
        val found = ArrayList<PickedBounds>()
        var source = 0
        private val at = ViewPoint()

        override fun rect(entity: NetId, minX: Float, minY: Float, maxX: Float, maxY: Float) {
            var left = Float.POSITIVE_INFINITY
            var bottom = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var top = Float.NEGATIVE_INFINITY
            for (corner in 0 until RECT_CORNERS) {
                val x = if (corner and 1 == 0) minX else maxX
                val y = if (corner and 2 == 0) minY else maxY
                if (!projector.projectRect(x, y, at)) return
                left = minOf(left, at.x)
                bottom = minOf(bottom, at.y)
                right = maxOf(right, at.x)
                top = maxOf(top, at.y)
            }
            found += PickedBounds(entity, left, bottom, right, top, source, 0f, found.size)
        }

        override fun box(entity: NetId, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
            var left = Float.POSITIVE_INFINITY
            var bottom = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var top = Float.NEGATIVE_INFINITY
            for (corner in 0 until BOX_CORNERS) {
                val x = if (corner and 1 == 0) minX else maxX
                val y = if (corner and 2 == 0) minY else maxY
                val z = if (corner and 4 == 0) minZ else maxZ
                // A box reaching behind the eye has no rectangle on the picture; it is not picked.
                if (!projector.projectBox(x, y, z, at)) return
                left = minOf(left, at.x)
                bottom = minOf(bottom, at.y)
                right = maxOf(right, at.x)
                top = maxOf(top, at.y)
            }
            val depth = projector.depthOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
            found += PickedBounds(entity, left, bottom, right, top, source, depth, found.size)
        }
    }

    private companion object {
        const val BOX_CORNERS = 8

        /** A rectangle's four corners, taken the same way a box's eight are, `z` left out. */
        const val RECT_CORNERS = 4

        /** Later source first; then nearer the eye; then reported later. See the class KDoc. */
        val FRONT_FIRST: Comparator<PickedBounds> = compareByDescending<PickedBounds> { it.source }
            .thenBy { it.depth }
            .thenByDescending { it.order }
    }
}

/**
 * [EntityPicker] through the camera a 3D game is played through (issue #262): a [CameraPick], and the
 * height of the ground a flat sprite lies on.
 *
 * Its pixels are [CameraPick]'s - `x` right from the left edge, `y` **up** from the bottom - so a
 * pointer read from a backend is turned the right way up before it reaches [EntityPicker.under].
 * `WorldPointer` is where that happens.
 *
 * A sprite reports a **ground-plane** rectangle, in world units. In a 3D game that is a decal, a
 * selection ring, a footprint preview: something lying on the ground at [groundZ], which is why it
 * goes through the same camera as everything else rather than through a projection of its own.
 */
public class CameraPickProjector(
    private val pick: CameraPick,
    /** The height of the ground a flat sprite lies on, in world units. */
    public var groundZ: Float = 0f,
) : PickProjector {

    override fun projectRect(x: Float, y: Float, out: ViewPoint): Boolean = pick.project(x, y, groundZ, out)

    override fun projectBox(x: Float, y: Float, z: Float, out: ViewPoint): Boolean = pick.project(x, y, z, out)

    override fun depthOf(x: Float, y: Float, z: Float): Float = pick.depthOf(x, y, z)

    override fun toString(): String = "CameraPickProjector($pick, ground at $groundZ)"
}
