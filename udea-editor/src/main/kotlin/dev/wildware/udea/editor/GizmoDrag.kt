package dev.wildware.udea.editor

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.editor.gizmo.Axis
import dev.wildware.udea.editor.gizmo.Drag
import dev.wildware.udea.editor.gizmo.DragConstraint
import dev.wildware.udea.editor.gizmo.FieldWrite
import dev.wildware.udea.editor.gizmo.HandleLayer
import dev.wildware.udea.editor.gizmo.Plane
import dev.wildware.udea.editor.gizmo.ShownHandle
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.ViewPoint

/**
 * A drag on a gizmo handle in the Scene tab, as one edit session (issue #236).
 *
 * - **Press** opens `editor.begin_edit` over every entity and field the handle writes.
 * - **Each move** is an `editor.update_edit` carrying every entity's new values, which the tool writes
 *   live between ticks and files nothing for. Snapping is applied here, before the call, unless Ctrl
 *   is held: the tools take exact values.
 * - **Release** is `editor.commit_edit`: **one** undo entry however many moves it took.
 * - **Escape**, or the window losing the pointer, is `editor.cancel_edit`, which puts every starting
 *   value back and files nothing; the moves after it do nothing until the button comes up.
 *
 * Calls go one at a time, each after the last one's answer, so they reach the tools in order; a move
 * made while one is out replaces any move still waiting, since only the newest position matters.
 *
 * ## Where the pointer is, on the handle
 *
 * The pointer's view pixel is taken to the ground plane under it, then held to the handle's
 * [DragConstraint] - onto its line, or its plane - through the point where the handle was when it was
 * pressed. A drag is those two points: where the press was held, and where the pointer is held now.
 * That reading of the pointer is the 2D camera's; with the 3D camera on, a press on a handle does
 * nothing yet, because the 3D drags are issue #237's.
 *
 * Render thread only, like [EditorTools].
 */
internal class GizmoDrag(
    private val tools: EditorTools,
    private val gizmos: EditorGizmos,
    private val layer: HandleLayer,
    private val camera: EditorCamera,
    /** Whether Ctrl is held, read at every move. */
    private val ctrl: () -> Boolean,
    /** Told what the last drag did wrong, or `null` when it did nothing wrong. */
    private val told: (String?) -> Unit,
) {

    private val pointer = ViewPoint()

    /** The drag the button is holding, or `null`. */
    private var held: Held? = null

    /** Whether a drag is being held: the moves until the release are this one's. */
    val holding: Boolean get() = held != null

    /** A press at view pixel ([viewX], [viewY]) that the handle layer took. Starts the drag on its handle. */
    fun press(viewX: Float, viewY: Float) {
        val handle = layer.pressed ?: return
        if (camera.dimension != ViewDimension.TwoD) {
            layer.letGo()
            told("Dragging a handle in the 3D view comes with the 3D gizmos; switch the Scene tab to 2D")
            return
        }
        val start = constrain(handle, ground(viewX, viewY))
        val still = handle.drag(Drag(start, start))
        val paths = still.map { write -> gizmos.pathOf(write) ?: return refuse(write) }
        val drag = Held(handle, start)
        held = drag
        drag.inFlight = true
        val args = mapOf(
            "entities" to still.map { it.entity }.distinct().joinToString(",") { it.raw.toString() },
            "fields" to paths.distinct().joinToString(","),
        )
        tools.call(EditorInspector.BEGIN_EDIT, args) { answer ->
            drag.inFlight = false
            when (answer) {
                is AgentResult.Ok -> {
                    drag.session = EditorInspector.sessionIdOf(answer.json)
                    told(null)
                    flush(drag)
                }
                is AgentResult.Failed -> {
                    told("Not moved: ${answer.error.message}")
                    end(drag)
                }
            }
        }
    }

    /** The pointer moved to view pixel ([viewX], [viewY]) with the button held. */
    fun move(viewX: Float, viewY: Float) {
        val drag = held ?: return
        if (drag.cancelled) return
        val writes = drag.handle.drag(Drag(drag.start, constrain(drag.handle, ground(viewX, viewY))))
        val bypass = ctrl()
        drag.latest = writes.joinToString(",") { write -> "${write.entity.raw}:${gizmos.pathOf(write)}=${gizmos.preferences.snapped(write, bypass)}" }
        flush(drag)
    }

    /** The button came up: keep the drag as one undo entry. */
    fun release() {
        val drag = held ?: return
        drag.finishing = true
        flush(drag)
        end(drag)
    }

    /** Escape, or the pointer taken away: put everything back. */
    fun cancel() {
        val drag = held ?: return
        drag.cancelled = true
        flush(drag)
        end(drag)
    }

    /** Sends what [drag] owes the tools next, when its session is open and nothing is out. */
    private fun flush(drag: Held) {
        val session = drag.session ?: return
        if (drag.inFlight || drag.closed) return
        if (drag.cancelled) {
            close(drag, EditorInspector.CANCEL_EDIT, session)
            return
        }
        val latest = drag.latest
        if (latest != null && latest != drag.sent) {
            drag.inFlight = true
            drag.sent = latest
            tools.call(EditorInspector.UPDATE_EDIT, mapOf("sessionId" to session.toString(), "values" to latest)) { answer ->
                drag.inFlight = false
                if (answer is AgentResult.Failed) {
                    told("Not moved: ${answer.error.message}")
                    drag.cancelled = true
                }
                flush(drag)
            }
            return
        }
        if (drag.finishing) close(drag, EditorInspector.COMMIT_EDIT, session)
    }

    private fun close(drag: Held, tool: String, session: Int) {
        drag.closed = true
        tools.call(tool, mapOf("sessionId" to session.toString())) { answer ->
            if (answer is AgentResult.Failed) told("$tool refused: ${answer.error.message}")
        }
    }

    /** The button is no longer this drag's; its calls still finish as [flush] sends them. */
    private fun end(drag: Held) {
        if (held === drag) held = null
        layer.letGo()
    }

    private fun refuse(write: FieldWrite) {
        layer.letGo()
        told("Not moved: ${write.component} is not a component the tools can edit, so its gizmo cannot write ${write.field}")
    }

    /** The ground-plane point under view pixel ([viewX], [viewY]). */
    private fun ground(viewX: Float, viewY: Float): WorldPoint {
        camera.unproject(viewX, viewY, pointer)
        return WorldPoint(pointer.x, pointer.y)
    }

    override fun toString(): String = "GizmoDrag(holding=${held?.handle})"

    /**
     * One drag's edit session: the handle pressed and where the press was held to it, the session id
     * once `editor.begin_edit` answers, and what has been sent of it.
     */
    private class Held(val handle: ShownHandle, val start: WorldPoint) {
        var session: Int? = null
        var latest: String? = null
        var sent: String? = null
        var inFlight = false
        var finishing = false
        var cancelled = false
        var closed = false

        override fun toString(): String = "Held($handle, session=$session)"
    }

    internal companion object {

        /**
         * [point] held to [handle]'s constraint, through where the handle is: onto the line along one
         * of its axes, or onto the plane across two of them. A free drag in the view's plane is the
         * ground plane, which is the view's plane in 2D.
         */
        fun constrain(handle: ShownHandle, point: WorldPoint): WorldPoint {
            val at = handle.at
            return when (val constraint = handle.constraint) {
                is DragConstraint.Along -> {
                    val direction = handle.axes.direction(constraint.axis)
                    val along = (point.x - at.x) * direction.x + (point.y - at.y) * direction.y + (point.z - at.z) * direction.z
                    WorldPoint(at.x + direction.x * along, at.y + direction.y * along, at.z + direction.z * along)
                }
                is DragConstraint.Across -> {
                    val normal = handle.axes.direction(
                        when (constraint.plane) {
                            Plane.XY -> Axis.Z
                            Plane.XZ -> Axis.Y
                            Plane.YZ -> Axis.X
                        },
                    )
                    val lifted = WorldPoint(point.x, point.y, at.z)
                    val off = (lifted.x - at.x) * normal.x + (lifted.y - at.y) * normal.y + (lifted.z - at.z) * normal.z
                    WorldPoint(lifted.x - normal.x * off, lifted.y - normal.y * off, lifted.z - normal.z * off)
                }
                DragConstraint.ViewPlane -> WorldPoint(point.x, point.y, at.z)
            }
        }
    }
}
