package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.ViewPoint

/**
 * Draws the [Mark]s of [gizmos] for every selected entity into a view (issue #243): the read-only
 * half of a gizmo, such as the [BoneOverlayGizmo]'s skeleton.
 *
 * A [GizmoLayer], so what it draws goes into the view's own pass and never into a capture: set on
 * the Scene tab it is drawn there, and on the Game tab only while that tab's gizmo toggle is on. It
 * takes no press - a mark has nothing to grab - so a click on one goes on to the camera.
 *
 * Handles are not drawn here: drawing them, and dragging them, is the interactive layer's
 * (epic #231, issue #236). Both read the same public [Gizmo] declarations.
 *
 * @param selection the entities to draw for, read once a frame: the editor author's selection.
 * @param gizmos every gizmo on offer; each is drawn for a selected entity that carries its component.
 */
internal class GizmoMarkLayer(
    private val world: World,
    private val netIds: NetIdIndex,
    private val selection: () -> List<NetId>,
    private val gizmos: List<Gizmo<*>>,
) : GizmoLayer {

    private val from = ViewPoint()
    private val to = ViewPoint()

    override fun draw(canvas: GizmoCanvas) {
        for (id in selection()) {
            val entity = netIds.resolveOrNull(id) ?: continue
            // Every component the entity carries, once: Fleks reads one by a reified type, which a
            // list of gizmos of different components cannot name. The editor draws a handful of
            // selected entities a frame, so the list this allocates is not a per-tick cost.
            val components = world.snapshotOf(entity).components
            val origin = originOf(entity)
            for (gizmo in gizmos) {
                val component = components.firstOrNull { it.type() === gizmo.component } ?: continue
                drawFor(canvas, gizmo.anyComponent(), id, component, origin)
            }
        }
    }

    /**
     * [this] gizmo with its component type forgotten, so one generic [drawFor] serves every entry of
     * a mixed list. Sound because [draw] hands each gizmo only the component whose type is that
     * gizmo's own [Gizmo.component].
     */
    @Suppress("UNCHECKED_CAST")
    private fun Gizmo<*>.anyComponent(): Gizmo<Nothing> = this as Gizmo<Nothing>

    private fun <C : Component<C>> drawFor(canvas: GizmoCanvas, gizmo: Gizmo<C>, id: NetId, component: Component<*>, origin: WorldPoint) {
        @Suppress("UNCHECKED_CAST")
        val marks = gizmo.marks(GizmoTarget(id, component as C, origin))
        // Lines first, so every dot sits on top of the lines that meet at it.
        for (mark in marks) {
            val line = mark.shape as? HandleShape.Line ?: continue
            if (!canvas.project(mark.at.x, mark.at.y, mark.at.z, from)) continue
            if (!canvas.project(line.to.x, line.to.y, line.to.z, to)) continue
            canvas.line(from.x, from.y, to.x, to.y, LINE_WIDTH + 2f * OUTLINE, OUTLINE_COLOUR)
            canvas.line(from.x, from.y, to.x, to.y, LINE_WIDTH, MARK_COLOUR)
        }
        for (mark in marks) {
            if (mark.shape is HandleShape.Line) continue
            if (!canvas.project(mark.at.x, mark.at.y, mark.at.z, from)) continue
            dot(canvas, from.x, from.y)
        }
    }

    /**
     * Where [entity] stands, for a gizmo that draws around it: its `Transform3D` if it has one, the
     * world origin otherwise. The bone overlay reads its joints and not this.
     */
    private fun originOf(entity: Entity): WorldPoint = with(world) {
        val transform = entity.getOrNull(Transform3D) ?: return WorldPoint(0f, 0f, 0f)
        WorldPoint(transform.x, transform.y, transform.z)
    }

    /**
     * A dot every non-line shape is drawn as: a mark is a place to look, and its shape's direction
     * or ring means nothing to a hand that cannot grab it.
     */
    private fun dot(canvas: GizmoCanvas, x: Float, y: Float) {
        val outer = DOT_SIZE + 2f * OUTLINE
        canvas.fill(x - outer / 2f, y - outer / 2f, outer, outer, OUTLINE_COLOUR)
        canvas.fill(x - DOT_SIZE / 2f, y - DOT_SIZE / 2f, DOT_SIZE, DOT_SIZE, MARK_COLOUR)
    }

    override fun toString(): String = "GizmoMarkLayer(${gizmos.size} gizmos)"

    internal companion object {
        /** What a mark is drawn in: a warm yellow that no sprite or model in `moba` is. */
        internal val MARK_COLOUR: Rgba = Rgba.of(1f, 0.82f, 0.1f, 1f)

        /** The dark edge round every mark, so it reads over a light model and a dark floor alike. */
        internal val OUTLINE_COLOUR: Rgba = Rgba.of(0.05f, 0.05f, 0.08f, 1f)

        /** A joint's dot, in view pixels. */
        internal const val DOT_SIZE: Float = 7f

        /** A bone's line, in view pixels. */
        internal const val LINE_WIDTH: Float = 2f

        /** The dark edge's width, in view pixels. */
        internal const val OUTLINE: Float = 1f
    }
}
