package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * Where a `Transform3D` stood at the end of the last two ticks, so a renderer can draw between
 * them (issue #246).
 *
 * ## Why the end of a tick, and not the start as [Interp] records
 *
 * [Interp] records a body's pose at the start of a tick, in `PreSimulation`, which runs *after* the
 * `SimBarrier` is drained. That is right for a body the simulation moves, and wrong for a
 * `Transform3D` on a client: there the position arrives in a snapshot applied through the barrier,
 * so a start-of-tick record would already hold the new position and the model would hop from one
 * snapshot to the next with nothing drawn between. Recorded at the end of each tick instead, the
 * previous pose is where the last tick *left* the entity, before any barrier work, and the draw
 * slides across whatever moved it - a system on a server, a snapshot on a client.
 *
 * ## Why it keeps both ends
 *
 * [prevX] and friends are the pose at the end of the tick before last: where a frame at alpha 0
 * draws. [lastX] and friends are the pose at the end of the last tick, which equals the live
 * `Transform3D` - unless something moved the entity *between* ticks, outside the barrier: an
 * editor gizmo dragged while the game is paused. [Interpolator3D] draws that entity where it now
 * is, rather than lagging the handle under the pointer by a tick.
 *
 * Only position and heading are kept. Pitch, roll and scale are drawn as they stand: nothing in
 * the engine animates them per tick, and interpolating them would be three more lerps per model
 * per frame for no visible change.
 *
 * Presentation-local, like [Interp]: nothing simulated reads it, it is not in any
 * `ComponentRegistry`, and it is not captured, hashed or sent.
 */
internal class Interp3D(
    var prevX: Float,
    var prevY: Float,
    var prevZ: Float,
    /** Radians about Z. */
    var prevHeading: Float,
    var lastX: Float = prevX,
    var lastY: Float = prevY,
    var lastZ: Float = prevZ,
    /** Radians about Z. */
    var lastHeading: Float = prevHeading,
) : Component<Interp3D> {

    /** Shifts the last recorded pose to the previous one and records [x], [y], [z] and [heading]. */
    fun record(x: Float, y: Float, z: Float, heading: Float) {
        prevX = lastX
        prevY = lastY
        prevZ = lastZ
        prevHeading = lastHeading
        lastX = x
        lastY = y
        lastZ = z
        lastHeading = heading
    }

    override fun type(): ComponentType<Interp3D> = Interp3D

    override fun toString(): String =
        "Interp3D(prev=($prevX, $prevY, $prevZ) heading=$prevHeading, last=($lastX, $lastY, $lastZ) heading=$lastHeading)"

    companion object : ComponentType<Interp3D>()
}
