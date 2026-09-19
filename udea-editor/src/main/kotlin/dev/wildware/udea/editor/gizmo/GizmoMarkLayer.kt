package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer

/**
 * Draws the [Mark]s of [gizmos] for every selected entity into a view (issue #243): the read-only
 * half of a gizmo, such as the [BoneOverlayGizmo]'s skeleton.
 *
 * A [GizmoLayer], so what it draws goes into the view's own pass and never into a capture: set on
 * the Scene tab it is drawn there, and on the Game tab only while that tab's gizmo toggle is on. It
 * goes over the layer the view already had ([under]): that is drawn first and gets every press, since
 * a mark has nothing to grab.
 *
 * Handles are not drawn here: drawing them, and dragging them, is the interactive layer's
 * (epic #231, issue #236). Both read the same public [Gizmo] declarations, and both draw a mark
 * through the one [HandlePainter].
 *
 * @param selection the entities to draw for, read once a frame: the editor author's selection.
 * @param gizmos every gizmo on offer; each is drawn for a selected entity that carries its component.
 * @param under the view's layer before this one - a launcher's handles - or `null`.
 */
internal class GizmoMarkLayer(
    private val world: World,
    private val netIds: NetIdIndex,
    private val selection: () -> List<NetId>,
    private val gizmos: List<Gizmo<*>>,
    private val under: GizmoLayer? = null,
) : GizmoLayer {

    private val painter = HandlePainter()

    override fun draw(canvas: GizmoCanvas) {
        under?.draw(canvas)
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
        painter.draw(canvas, marks)
    }

    /**
     * Where [entity] stands, for a gizmo that draws around it: its `Transform3D` if it has one, the
     * world origin otherwise. The bone overlay reads its joints and not this.
     */
    private fun originOf(entity: Entity): WorldPoint = with(world) {
        val transform = entity.getOrNull(Transform3D) ?: return WorldPoint(0f, 0f, 0f)
        WorldPoint(transform.x, transform.y, transform.z)
    }

    override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean = under?.press(canvas, viewX, viewY) ?: false

    override fun toString(): String = "GizmoMarkLayer(${gizmos.size} gizmos, under=$under)"
}
