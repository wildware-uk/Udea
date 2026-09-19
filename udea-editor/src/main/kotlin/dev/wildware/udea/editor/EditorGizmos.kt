package dev.wildware.udea.editor

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.editor.gizmo.AxisFrame
import dev.wildware.udea.editor.gizmo.FieldWrite
import dev.wildware.udea.editor.gizmo.Gizmo
import dev.wildware.udea.editor.gizmo.GizmoFrame
import dev.wildware.udea.editor.gizmo.GizmoPlacement
import dev.wildware.udea.editor.gizmo.GizmoRegistry
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.Guide
import dev.wildware.udea.editor.gizmo.HandlePart
import dev.wildware.udea.editor.gizmo.Placement
import dev.wildware.udea.editor.gizmo.ShownHandle
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.editor.gizmo.built
import dev.wildware.udea.editor.gizmo.shifted

/**
 * The gizmos an editor offers over a game's world (issue #236): the game's generated
 * `<Game>GizmoRegistry`, run for whatever is selected in the Scene tab.
 *
 * ```kotlin
 * EditorSession(..., gizmos = EditorGizmos(MobaGizmoRegistry, host.world, netIds, components, placement,
 *     GizmoPreferences.load(path)))
 * ```
 *
 * ## Reading, never writing
 *
 * Each frame the Scene tab draws, every gizmo whose component every selected entity carries is run for
 * each of them, reading the live component off [world] on the render thread - the thread the world is
 * stepped on, so a gizmo never sees half a tick. That is all it does with the world. A drag's writes
 * reach it through `editor.*` edit sessions, named by the component names [components] gives the tool
 * surface, so a person dragging and an agent calling get the same edit and the same undo.
 *
 * ## Several selected
 *
 * One set of handles, at the selection's centre: each handle is the first selected entity's, moved by
 * how far that entity is from the centre, and dragging it drags the same handle of every selected
 * entity by the same amount, each measured from its own place ([ShownHandle]). A gizmo that declares a
 * different number of handles for different entities is not offered for them together; its guides are
 * drawn round each entity where it is.
 *
 * @param placement where each entity is and which way it faces: the game says, since the engine has no
 *   one position component.
 * @param preferences the Scene tab's snapping and axes.
 */
public class EditorGizmos(
    private val registry: GizmoRegistry,
    private val world: World,
    private val netIds: NetIdIndex,
    private val components: AgentComponentIndex,
    private val placement: GizmoPlacement,
    internal val preferences: GizmoPreferences = GizmoPreferences(),
) {

    /** The handles and guides [selection] shows now. Render thread. */
    internal fun frameFor(selection: List<NetId>): GizmoFrame {
        val placed = selection.mapNotNull { id ->
            val entity = netIds.resolveOrNull(id) ?: return@mapNotNull null
            placement.place(world, entity)?.let { Placed(id, entity, it) }
        }
        if (placed.isEmpty()) return GizmoFrame.EMPTY
        val centre = WorldPoint(
            placed.sumOf { it.place.origin.x.toDouble() }.toFloat() / placed.size,
            placed.sumOf { it.place.origin.y.toDouble() }.toFloat() / placed.size,
            placed.sumOf { it.place.origin.z.toDouble() }.toFloat() / placed.size,
        )
        // Several entities turn with the first one's axes, so one drag means one direction for all.
        val axes = if (preferences.axes == GizmoAxes.Local) placed.first().place.axes else AxisFrame.WORLD
        val handles = ArrayList<ShownHandle>()
        val guides = ArrayList<Guide>()
        for (gizmo in registry.gizmos) collect(gizmo, placed, centre, axes, handles, guides)
        return GizmoFrame(handles, guides)
    }

    /**
     * `Component.field` as the tool surface spells [write]'s field, or `null` when no component the
     * tools know is [FieldWrite.component] - a gizmo for a component the game never registered with
     * its agent surface, which no edit session can address.
     */
    internal fun pathOf(write: FieldWrite): String? =
        components.findByType(write.component)?.let { "${it.name}.${write.field.value}" }

    /** Runs [gizmo] for every placed entity, when every one carries its component. */
    private fun <C : Component<C>> collect(
        gizmo: Gizmo<C>,
        placed: List<Placed>,
        centre: WorldPoint,
        axes: AxisFrame,
        handles: MutableList<ShownHandle>,
        guides: MutableList<Guide>,
    ) {
        val builds = placed.map { entry ->
            val component = componentOf(entry.entity, gizmo.component) ?: return
            gizmo.built(GizmoTarget(entry.id, component, entry.place.origin, axes))
        }
        for (build in builds) guides += build.guides
        val count = builds.first().handles.size
        if (builds.any { it.handles.size != count }) return
        val lead = placed.first().place.origin
        val shift = WorldPoint(centre.x - lead.x, centre.y - lead.y, centre.z - lead.z)
        for (index in 0 until count) {
            val first = builds.first().handles[index]
            val at = first.at.shifted(shift)
            val parts = builds.map { build ->
                val handle = build.handles[index]
                HandlePart(handle, WorldPoint(handle.at.x - at.x, handle.at.y - at.y, handle.at.z - at.z))
            }
            handles += ShownHandle(at, first.shape.shifted(shift), first.constraint, first.axes, parts)
        }
    }

    /**
     * [entity]'s [type] component, or `null` when it has none.
     *
     * Fleks reads a component through a reified type, and `C` here is only known to be some component:
     * the presence check is by id, and the read is cast back to the `C` [type] names - which it is,
     * since a component is stored under its own type.
     */
    private fun <C : Component<C>> componentOf(entity: Entity, type: ComponentType<C>): C? = with(world) {
        if (!(entity has type)) return null
        @Suppress("UNCHECKED_CAST")
        entity.getOrNull(type as ComponentType<Component<*>>) as C?
    }

    override fun toString(): String = "EditorGizmos(${registry.gizmos.size} gizmos, ${preferences})"

    /** A selected entity the game placed. */
    private class Placed(val id: NetId, val entity: Entity, val place: Placement)
}
