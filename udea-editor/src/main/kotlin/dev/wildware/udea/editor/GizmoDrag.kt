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
import dev.wildware.udea.render.view.ViewRay
import kotlin.math.abs

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
 * The pointer's view pixel is the line of world points drawn there ([EditorCamera.ray]), held to the
 * handle's [DragConstraint] through the point where the handle was when it was pressed: where that
 * line crosses the handle's plane, or the point on the handle's line nearest it (issue #237). A drag
 * is those two points: where the press was held, and where the pointer is held now. In 2D the line
 * runs straight down, so a point is the ground-plane point under the pointer; in 3D it runs from the
 * eye, so the same handle is dragged along the same line whichever way the camera looks. A move
 * whose line runs along the handle's line or plane, and so never meets it, is skipped: the handle
 * stays where the last move put it.
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

    private val pointer = ViewRay()

    /** The drag the button is holding, or `null`. */
    private var held: Held? = null

    /** Whether a drag is being held: the moves until the release are this one's. */
    val holding: Boolean get() = held != null

    /** A press at view pixel ([viewX], [viewY]) that the handle layer took. Starts the drag on its handle. */
    fun press(viewX: Float, viewY: Float) {
        val handle = layer.pressed ?: return
        camera.ray(viewX, viewY, pointer)
        // A free drag's plane faces the way the press looked, for the whole drag.
        val facing = WorldPoint(pointer.directionX, pointer.directionY, pointer.directionZ)
        val start = constrain(handle, pointer, facing) ?: handle.at
        val unitsPerPixel = camera.unitsPerPixelAt(handle.at.x, handle.at.y, handle.at.z)
        val still = handle.drag(Drag(start, start, unitsPerPixel))
        val paths = still.map { write -> gizmos.pathOf(write) ?: return refuse(write) }
        val drag = Held(handle, start, facing, unitsPerPixel)
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
        camera.ray(viewX, viewY, pointer)
        val at = constrain(drag.handle, pointer, drag.facing) ?: return
        val writes = drag.handle.drag(Drag(drag.start, at, drag.unitsPerPixel))
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

    override fun toString(): String = "GizmoDrag(holding=${held?.handle})"

    /**
     * One drag's edit session: the handle pressed, where the press was held to it, which way the press
     * looked and how big a pixel was there, the session id once `editor.begin_edit` answers, and what
     * has been sent of it.
     */
    private class Held(val handle: ShownHandle, val start: WorldPoint, val facing: WorldPoint, val unitsPerPixel: Float) {
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
         * The pointer's line [ray] held to [handle]'s constraint, through where the handle is: the
         * point on the line along one of its axes nearest the ray, or where the ray crosses the plane
         * across two of them, or - for a free drag - the plane square to [facing], the way the press
         * looked, which is the ground plane in 2D, where every ray runs straight down. `null` when the
         * ray runs along the handle's line or plane and never meets it.
         */
        fun constrain(handle: ShownHandle, ray: ViewRay, facing: WorldPoint): WorldPoint? {
            val at = handle.at
            val wx = at.x - ray.originX
            val wy = at.y - ray.originY
            val wz = at.z - ray.originZ
            return when (val constraint = handle.constraint) {
                is DragConstraint.Along -> {
                    // The closest points of two lines: the handle's, at + d s, and the ray's, o + r t.
                    val d = handle.axes.direction(constraint.axis)
                    val b = d.x * ray.directionX + d.y * ray.directionY + d.z * ray.directionZ
                    val parallel = 1f - b * b
                    if (parallel < PARALLEL) return null
                    val alongD = d.x * wx + d.y * wy + d.z * wz
                    val alongR = ray.directionX * wx + ray.directionY * wy + ray.directionZ * wz
                    val s = (b * alongR - alongD) / parallel
                    WorldPoint(at.x + d.x * s, at.y + d.y * s, at.z + d.z * s)
                }
                is DragConstraint.Across -> {
                    val normal = handle.axes.direction(
                        when (constraint.plane) {
                            Plane.XY -> Axis.Z
                            Plane.XZ -> Axis.Y
                            Plane.YZ -> Axis.X
                        },
                    )
                    crossing(ray, normal.x, normal.y, normal.z, wx, wy, wz)
                }
                DragConstraint.ViewPlane -> crossing(ray, facing.x, facing.y, facing.z, wx, wy, wz)
            }
        }

        /** Where [ray] crosses the plane square to (nx, ny, nz) a step of (wx, wy, wz) from its origin, or `null` when it runs along it. */
        private fun crossing(ray: ViewRay, nx: Float, ny: Float, nz: Float, wx: Float, wy: Float, wz: Float): WorldPoint? {
            val facing = nx * ray.directionX + ny * ray.directionY + nz * ray.directionZ
            if (abs(facing) < PARALLEL) return null
            val t = (nx * wx + ny * wy + nz * wz) / facing
            return WorldPoint(ray.originX + ray.directionX * t, ray.originY + ray.directionY * t, ray.originZ + ray.directionZ * t)
        }

        /** Less than this of the ray across a handle's line or plane, and it never meets it. */
        private const val PARALLEL: Float = 1e-4f
    }
}
