package dev.wildware.udea.editor.gizmo

/**
 * What the Scene tab shows of the gizmos for the selection, in one frame (issue #236): the handles a
 * press can take, and the marks drawn under them. Built by `EditorGizmos.frameFor`.
 */
internal class GizmoFrame(val handles: List<ShownHandle>, val marks: List<Mark>) {

    override fun toString(): String = "GizmoFrame(${handles.size} handles, ${marks.size} marks)"

    companion object {
        val EMPTY: GizmoFrame = GizmoFrame(emptyList(), emptyList())

        /** One entity's [handles] exactly where each was declared, and no marks: a test's frame. */
        fun of(handles: List<Handle<*>>): GizmoFrame = GizmoFrame(
            handles.map { handle ->
                ShownHandle(handle.at, handle.shape, handle.constraint, handle.axes, listOf(HandlePart(handle, WorldPoint(0f, 0f))))
            },
            emptyList(),
        )
    }
}

/**
 * One handle as the Scene tab shows it: where, as what, held to what - and the handle of each selected
 * entity a drag on it drags ([parts]).
 *
 * With one entity selected it is that entity's handle. With several it is the first one's, moved to the
 * selection's centre, standing for the same handle of each.
 */
internal class ShownHandle(
    val at: WorldPoint,
    val shape: HandleShape,
    val constraint: DragConstraint,
    val axes: AxisFrame,
    val parts: List<HandlePart>,
) {
    /**
     * The field writes [drag] makes, measured where it was made - on this handle - and handed to each
     * entity's own handle moved by how far that handle is from this one, so each is measured from its
     * own place.
     */
    fun drag(drag: Drag): List<FieldWrite> = parts.flatMap { part -> part.handle.drag(drag.shifted(part.shift)) }

    override fun toString(): String = "ShownHandle($shape at $at, for ${parts.size})"
}

/** One selected entity's handle behind a [ShownHandle], and how far it is from where that is shown. */
internal class HandlePart(val handle: Handle<*>, val shift: WorldPoint)

/** This point moved by [by]. */
internal fun WorldPoint.shifted(by: WorldPoint): WorldPoint = WorldPoint(x + by.x, y + by.y, z + by.z)

/** Both ends of this drag moved by [by]. */
internal fun Drag.shifted(by: WorldPoint): Drag = Drag(start.shifted(by), at.shifted(by))

/**
 * This shape drawn somewhere else, moved by [by]: the one shape with a world point in it, a
 * [HandleShape.Line], moves its far end too.
 */
internal fun HandleShape.shifted(by: WorldPoint): HandleShape = when (this) {
    is HandleShape.Line -> HandleShape.Line(to.shifted(by))
    HandleShape.Point, HandleShape.BoxCorner, HandleShape.Sphere,
    is HandleShape.Arrow, is HandleShape.PlaneSquare, is HandleShape.Ring, is HandleShape.BoxEdge, is HandleShape.Circle,
    -> this
}
