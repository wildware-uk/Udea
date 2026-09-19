package dev.wildware.udea.editor

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.PickSink
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport

/**
 * What is where in the Scene tab (issue #235): the entities the view's render systems report through
 * `PickBounds`, each turned into the rectangle of view pixels it covers.
 *
 * ## Which is in front
 *
 * Of the entities under a point, the front-most comes first:
 *
 * - one reported by a system the view draws later is in front of one reported by a system it draws
 *   earlier, because it is drawn over it;
 * - within one system, a model nearer the 3D eye is in front of one further away, because the model
 *   pass is depth-tested;
 * - otherwise the one the system reported later is in front, because it drew it later.
 *
 * An entity reported more than once - drawn by two systems - is listed once, where it is front-most.
 *
 * Positions are view pixels from the bottom left, as [WorldViewport.toView] maps a pointer. A
 * sprite's rectangle is projected through the view's 2D camera and a model's box through its 3D
 * orbit, because that is how each is drawn in a Scene view whichever camera a drag moves.
 *
 * Render thread only: it asks the render systems, which read the world.
 */
internal class ScenePicker(private val view: WorldViewport) {

    private val camera: EditorCamera = checkNotNull(view.camera) { "$view is the Game tab, which the editor does not pick in" }

    /** Every entity drawn under view pixel ([x], [y]), front-most first, each once. */
    fun under(x: Float, y: Float): List<NetId> = collect()
        .filter { it.contains(x, y) }
        .sortedWith(FRONT_FIRST)
        .map { it.entity }
        .distinct()

    /**
     * Every entity whose drawn rectangle touches the view rectangle from ([left], [bottom]) to
     * ([right], [top]), in any corner order, each once, front-most first.
     */
    fun touching(left: Float, bottom: Float, right: Float, top: Float): List<NetId> {
        val minX = minOf(left, right)
        val maxX = maxOf(left, right)
        val minY = minOf(bottom, top)
        val maxY = maxOf(bottom, top)
        return collect()
            .filter { it.left <= maxX && it.right >= minX && it.bottom <= maxY && it.top >= minY }
            .sortedWith(FRONT_FIRST)
            .map { it.entity }
            .distinct()
    }

    /** Each on-screen rectangle [entities] are drawn over, for outlining a selection. */
    fun bounds(entities: Collection<NetId>): List<Bounds> {
        if (entities.isEmpty()) return emptyList()
        val wanted = entities.toSet()
        return collect().filter { it.entity in wanted }
    }

    /** Asks every pickable system the view draws, in drawing order, where its entities are. */
    private fun collect(): List<Bounds> {
        val sink = Collector()
        val sources = view.pickBounds
        for (index in sources.indices) {
            sink.source = index
            sources[index].reportPickBounds(sink)
        }
        return sink.found
    }

    override fun toString(): String = "ScenePicker(${view.pickBounds.size} pickable systems)"

    /**
     * One entity's rectangle of view pixels, and what decides whether it is in front.
     *
     * @property source which pickable system reported it, in the view's drawing order.
     * @property depth how far in front of the 3D eye a model's box centre is; zero for a sprite.
     * @property order the report's place among every report, so a later one is drawn later.
     */
    internal class Bounds(
        val entity: NetId,
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float,
        val source: Int,
        val depth: Float,
        val order: Int,
    ) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in bottom..top

        override fun toString(): String = "Bounds($entity, $left..$right x $bottom..$top)"
    }

    /** Turns each report into view pixels as it arrives. */
    private inner class Collector : PickSink {
        val found = ArrayList<Bounds>()
        var source = 0
        private val at = ViewPoint()

        override fun rect(entity: NetId, minX: Float, minY: Float, maxX: Float, maxY: Float) {
            val projection = camera.projection
            val x0 = projection.pixelX(minX)
            val x1 = projection.pixelX(maxX)
            val y0 = projection.pixelY(minY)
            val y1 = projection.pixelY(maxY)
            found += Bounds(entity, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), source, 0f, found.size)
        }

        override fun box(entity: NetId, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
            var left = Float.POSITIVE_INFINITY
            var bottom = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var top = Float.NEGATIVE_INFINITY
            for (corner in 0 until CORNERS) {
                val x = if (corner and 1 == 0) minX else maxX
                val y = if (corner and 2 == 0) minY else maxY
                val z = if (corner and 4 == 0) minZ else maxZ
                // A box reaching behind the eye has no rectangle on the view; it is not picked.
                if (!camera.projectOrbit(x, y, z, at)) return
                left = minOf(left, at.x)
                bottom = minOf(bottom, at.y)
                right = maxOf(right, at.x)
                top = maxOf(top, at.y)
            }
            val depth = camera.depthOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
            found += Bounds(entity, left, bottom, right, top, source, depth, found.size)
        }
    }

    private companion object {
        const val CORNERS = 8

        /** Later system first; then nearer the eye; then reported later. See the class KDoc. */
        val FRONT_FIRST: Comparator<Bounds> = compareByDescending<Bounds> { it.source }
            .thenBy { it.depth }
            .thenByDescending { it.order }
    }
}
