package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId
import kotlin.reflect.KMutableProperty1

/**
 * Where a [Gizmo] declares its handles, inside [Gizmo.build].
 *
 * Built by [handles] and nowhere else, so every handle carries the entity and the component its
 * writes are for without a gizmo having to repeat them.
 */
public class GizmoScope<C : Component<C>> internal constructor(
    private val entity: NetId,
    private val component: ComponentType<C>,
) {

    private val declared = ArrayList<Handle<C>>()

    private val guides = ArrayList<Guide>()

    /**
     * Declares one handle.
     *
     * @param at where the handle sits, in world space.
     * @param shape what the editor draws there. Every shape keeps its size on screen.
     * @param constraint the line or plane the editor holds the handle to while it is dragged.
     * @param axes the frame [shape] and [constraint] name their axes in: an [HandleShape.Arrow] along
     *   [Axis.X] points along `axes.x`, and a drag [DragConstraint.Along] it is held to that line. The
     *   world's by default; pass [GizmoTarget.axes] to follow the editor's world/local switch.
     * @param respond turns a [Drag] into field writes, through [DragScope.write]. The editor keeps
     *   the handle it pressed for the whole drag and calls this with every move, while the drag
     *   itself changes the component live - so compute from what this frame of [Gizmo.build] read,
     *   captured in a local, never from the component inside the response.
     */
    public fun handle(
        at: WorldPoint,
        shape: HandleShape,
        constraint: DragConstraint,
        axes: AxisFrame = AxisFrame.WORLD,
        respond: DragScope<C>.(Drag) -> Unit,
    ) {
        declared += Handle(at, shape, constraint, axes, entity, component, respond)
    }

    /** Declares a [Guide]: drawn in world space under the handles, and never grabbed. */
    public fun guide(guide: Guide) {
        guides += guide
    }

    internal fun declared(): List<Handle<C>> = declared

    internal fun guides(): List<Guide> = guides
}

/**
 * One handle a gizmo declared: where it is, what it looks like, how it may move, and what dragging
 * it writes.
 *
 * A value the editor draws and hit-tests (G3 to G5) and a test reads. [drag] is the only thing it
 * does, and all that does is answer writes.
 */
public class Handle<C : Component<C>> internal constructor(
    /** Where the handle sits, in world space. */
    public val at: WorldPoint,
    /** What the editor draws at [at]. */
    public val shape: HandleShape,
    /** The line or plane a drag holds the handle to. */
    public val constraint: DragConstraint,
    /** The frame [shape] and [constraint] name their axes in. */
    public val axes: AxisFrame,
    private val entity: NetId,
    private val component: ComponentType<C>,
    private val respond: DragScope<C>.(Drag) -> Unit,
) {

    /**
     * The field writes [drag] makes, in the order the response made them. The world is untouched:
     * the editor turns these into an edit session's update.
     *
     * @throws IllegalArgumentException when the response writes one field twice, or writes a value
     *   that is not a finite number - a gizmo defect, reported where it happened rather than written
     *   into the world.
     */
    public fun drag(drag: Drag): List<FieldWrite> {
        val scope = DragScope(entity, component)
        scope.respond(drag)
        return scope.written()
    }

    override fun toString(): String = "Handle($shape at $at, $constraint)"
}

/**
 * What a handle's drag response writes with.
 *
 * Only [write] exists, and it records rather than sets: the answer to a drag is a list of writes.
 */
public class DragScope<C : Component<C>> internal constructor(
    private val entity: NetId,
    private val component: ComponentType<C>,
) {

    private val writes = ArrayList<FieldWrite>(1)

    /**
     * Records that [field] should become [value].
     *
     * A property reference, so a hand-written gizmo names a field the compiler has checked, exactly
     * as a generated one does. Only its name is read - a callable reference carries it, with no
     * reflection library involved - and it is never used to set anything.
     *
     * @param snap what kind of value this is, so the editor can round it to the person's grid or
     *   angle step (issue #236). The gizmo never rounds: the steps are preferences, not gizmo code.
     */
    public fun write(field: KMutableProperty1<C, Float>, value: Float, snap: Snap = Snap.None) {
        val name = FieldName(field.name)
        require(value.isFinite()) { "a drag wrote $value to '${name.value}', which is not a finite number" }
        require(writes.none { it.field == name }) { "a drag wrote '${name.value}' twice; each field is written once per drag" }
        writes += FieldWrite(entity, component, name, value, snap)
    }

    internal fun written(): List<FieldWrite> = writes
}

/**
 * One field a drag changes: on which entity, in which component, which field, to what - and how the
 * editor may round it ([snap]).
 *
 * What the editor turns into an edit session's `Component.field=value`, so a drag reaches the world
 * through the same `editor.*` tools an agent calls.
 */
public data class FieldWrite(
    val entity: NetId,
    val component: ComponentType<*>,
    val field: FieldName,
    val value: Float,
    val snap: Snap = Snap.None,
)

/** A component field's declared name, as the component's own property is called. */
@JvmInline
public value class FieldName(public val value: String) {
    override fun toString(): String = value
}
