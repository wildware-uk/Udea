package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * Where an entity's body stood at the start of the tick, so a renderer can draw between there
 * and where it stands now.
 *
 * ## Why interpolation is needed at all
 *
 * The simulation advances in whole 60Hz ticks and the renderer draws whenever the display asks
 * it to (spec 3.3). Drawn at the last simulated pose, a body moving at a constant speed
 * advances by zero, zero, one tick's worth, zero, one tick's worth — the repeating `0/0/x`
 * judder signature — on any refresh rate that is not a multiple of 60. Every frame of that is
 * *correct* and the motion still looks broken.
 *
 * ## Why the previous pose is a component and not a map
 *
 * It is per-entity state that has to survive being written by one system and read by another,
 * and Fleks already indexes exactly that. A `Map<Entity, Pose>` on the side would be a second
 * entity index to keep in step with entity destruction, which is the mistake the old tree made
 * with `Networkable.remoteEntity`.
 *
 * ## What it is not, yet
 *
 * Issue #120 specifies this component in `udea-core` carrying `@Sim`, so it is snapshotted and
 * a rewind restores the previous pose along with the current one. It is here instead, and is
 * **presentation-local**: this agent owns `udea-render` only and `udea-core` is another
 * agent's module this cycle. The consequence is real and is handled rather than hidden — a
 * restore leaves [prevX]/[prevY] describing a world that no longer exists, so
 * [InterpolationState] treats the first frame after any non-consecutive tick as a snap. That
 * gets the same visible behaviour (no one-frame smear across the map after a rewind) at the
 * cost of one frame of interpolation, and it is what makes moving the component to `udea-core`
 * later a widening rather than a rewrite.
 */
public class Interp(
    /** The body's `x` at the start of the current tick. */
    public var prevX: Float = 0f,
    /** The body's `y` at the start of the current tick. */
    public var prevY: Float = 0f,
    /** The body's `angle` at the start of the current tick, in radians. */
    public var prevAngle: Float = 0f,
    /**
     * Draw at the current pose this tick, ignoring [prevX]/[prevY]/[prevAngle].
     *
     * Set when the previous pose is not somewhere the entity actually was: a `Teleport` moved
     * it discontinuously, or it has only just been created. Interpolating across either
     * produces a body sweeping smoothly over ground it never crossed, which reads as a bug in
     * movement rather than in rendering.
     *
     * Cleared by [InterpSnapshotSystem] on the following tick, so it snaps once rather than
     * permanently.
     */
    public var snap: Boolean = true,
) : Component<Interp> {

    /** Records [x], [y] and [angle] as the pose this tick started from. */
    public fun capture(x: Float, y: Float, angle: Float) {
        prevX = x
        prevY = y
        prevAngle = angle
    }

    override fun type(): ComponentType<Interp> = Interp

    override fun toString(): String = "Interp(($prevX, $prevY) angle=$prevAngle snap=$snap)"

    public companion object : ComponentType<Interp>()
}
