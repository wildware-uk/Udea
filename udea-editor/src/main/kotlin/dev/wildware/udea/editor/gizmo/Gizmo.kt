package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId

/**
 * An editor-only control drawn in world space: handles a person drags to change one component
 * (epic #231, issue #233).
 *
 * **This is the public gizmo API, and the editor has no other.** Every gizmo the editor ships - move,
 * resize and rotate, 2D and 3D - is written against exactly this, as is every gizmo a game writes and
 * every gizmo `udea-codegen` generates from a handle annotation. If a built-in needs something, it
 * goes here, where a game can have it too.
 *
 * Two rules shape it:
 *
 * - **A gizmo never mutates the world.** [build] declares handles; a drag on one answers
 *   [FieldWrite]s, which the editor feeds into an edit session (`editor.begin_edit` /
 *   `editor.update_edit`), so a person and an agent reach the world by the same tools and the same
 *   undo. The component [GizmoTarget.component] hands over is for reading.
 * - **A gizmo is never shipped.** It implements this `udea-editor` type, so it lives in a game's
 *   `editor` source set, and `UDEA-MG-012` fails the build if one reaches a release classpath.
 *
 * A game's gizmos are listed in its generated `<Game>GizmoRegistry` - the hand-written ones found by
 * this supertype, the generated ones from the handle annotations - so a gizmo is registered by
 * existing, not by a line somebody has to remember.
 */
public interface Gizmo<C : Component<C>> {

    /** The component this gizmo edits. The editor offers it on entities carrying that component. */
    public val component: ComponentType<C>

    /**
     * Declares this frame's handles for [target], through [GizmoScope.handle].
     *
     * Called every frame the target is selected, and again for every frame of a drag, so a handle
     * follows the value it drives. Read the component here; never write it.
     */
    public fun GizmoScope<C>.build(target: GizmoTarget<C>)
}

/**
 * The handles [this] gizmo declares for [target], in the order it declared them.
 *
 * The one way to run a gizmo: the editor calls it every frame, and a game's own test calls it to
 * check its gizmo headless, as `udea-codegen`'s tests check a generated gizmo against a hand-written
 * one. It never touches the world.
 */
public fun <C : Component<C>> Gizmo<C>.handles(target: GizmoTarget<C>): List<Handle<C>> = built(target).declared()

/**
 * The marks [this] gizmo draws for [target] - the guides it shows with nothing to grab
 * ([GizmoScope.mark]) - in the order it drew them. The editor's way to draw a gizmo, and a game
 * test's way to check a read-only one, as [handles] is for handles. It never touches the world.
 */
public fun <C : Component<C>> Gizmo<C>.marks(target: GizmoTarget<C>): List<Mark> = built(target).marked()

/** One run of [Gizmo.build] for [target]: its handles and its marks. */
internal fun <C : Component<C>> Gizmo<C>.built(target: GizmoTarget<C>): GizmoScope<C> {
    val scope = GizmoScope(target.entity, component)
    with(this) { scope.build(target) }
    return scope
}

/**
 * The entity a gizmo is built for: which one, the component it edits, and where it is.
 *
 * @property entity the entity, by `NetId` as every tool call names it - never a Fleks `Entity`.
 * @property component the entity's component, **to read**. The drag responses write through
 *   [DragScope.write], and nothing else may change it.
 * @property origin the entity's position in the world, from whatever places it. A gizmo whose
 *   component is not itself a position - a radius, a range, a size - draws around this point.
 */
public class GizmoTarget<C : Component<C>>(
    public val entity: NetId,
    public val component: C,
    public val origin: WorldPoint,
) {
    override fun toString(): String = "GizmoTarget($entity at $origin)"
}

/**
 * Every gizmo a game's editor offers: what KSP generates, as `<Game>GizmoRegistry`, in the game's
 * `editor` source set.
 *
 * It lists the gizmo generated for each handle annotation on the game's components, and every
 * hand-written [Gizmo] in the `editor` source set, in ascending class-name order. Like the module
 * registries it is named statically, so nothing is looked up by name or by reflection.
 */
public interface GizmoRegistry {

    /** Every gizmo, in ascending fully-qualified class-name order. */
    public val gizmos: List<Gizmo<*>>
}
