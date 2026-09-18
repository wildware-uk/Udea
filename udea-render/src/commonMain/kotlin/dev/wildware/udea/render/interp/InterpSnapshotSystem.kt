package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.Teleport

/**
 * Copies every body's pose into its [Interp] before anything moves it.
 *
 * Registered at `SimPhase.PreSimulation` and constrained to run **before** `TeleportSystem`,
 * which is the only thing in the tick that moves a body discontinuously and which deletes the
 * `Teleport` component as it applies it. Running after it would mean the command is already
 * gone and this system cannot tell a teleport from a very fast walk — the entity would then be
 * drawn sweeping across the map over the following frames.
 *
 * ## Allocation
 *
 * Nothing is allocated per tick: the [Family] is resolved once at construction, the loop writes
 * into the existing [Interp] instance, and the pose is three floats rather than a vector object.
 * This runs for every moving entity every tick, which is exactly where the allocation budget in
 * standards section 4 is aimed.
 *
 * ## It is a simulation system, and it does not simulate
 *
 * The one presentation concern that has to live inside the tick, because "the pose at the start
 * of this tick" only exists at the start of the tick. It reads [PhysicsBody] and writes only
 * [Interp], which nothing simulated reads — so removing it changes no simulated value and no
 * `WorldHasher` result, which is asserted rather than asserted-in-a-comment.
 */
public class InterpSnapshotSystem : SimSystem(), PoseHistory {

    private val bodies: Family = world.family { all(PhysicsBody, Interp) }

    /**
     * Bodies that have not been given an [Interp] yet.
     *
     * Attaching it here rather than expecting every blueprint to remember is deliberate: a
     * body without one is not a crash, it is one entity that judders while the rest do not,
     * which is the sort of defect that survives a review. The family is empty on all but the
     * ticks that spawn something, so the scan costs a family lookup and nothing else.
     */
    private val uninitialised: Family = world.family { all(PhysicsBody).none(Interp) }

    /**
     * The tick this system last ran for.
     *
     * [Interpolator] compares it against the clock to spot a rewind: after a restore the clock
     * jumps, the recorded poses describe a world that no longer exists, and the first frame
     * drawn must snap rather than lerp from wherever the world used to be.
     *
     * It starts at `-1` rather than `Tick.ZERO` so that the frames drawn *before* the first
     * tick are ordinary frames rather than restores. `ZERO` would make `clock.tick == lastTick
     * + 1` false at start-up, and a game that opened paused would snap every frame until
     * something ticked -- which is the same code path as a rewind and would hide it.
     */
    override var lastTick: Tick = Tick(-1)
        private set

    /** Poses recorded since construction. A health signal an agent can poll. */
    public var capturedCount: Long = 0L
        private set

    override fun onTick() {
        uninitialised.forEach { entity ->
            val body = entity[PhysicsBody]
            entity.configure { it += Interp(body.x, body.y, body.angle, snap = true) }
        }

        bodies.forEach { entity ->
            val body = entity[PhysicsBody]
            val interp = entity[Interp]
            // A queued Teleport means this tick's movement is discontinuous, so the pose
            // recorded here is not somewhere the entity will have travelled from.
            interp.snap = Teleport in entity
            interp.capture(body.x, body.y, body.angle)
            capturedCount++
        }
        lastTick = tick
    }
}

/**
 * The tick the recorded previous poses belong to.
 *
 * A one-method interface, and the reason it is not just a property on [InterpSnapshotSystem]:
 * [Interpolator] needs to know when the recorded poses have been invalidated, and the only way
 * to check that it handles a rewind is to *present* it with one. Moving `SimClock` backwards is
 * `udea-core`'s privilege (`SimClock.moveTo` is internal), so a test that could not substitute
 * this would be a test that could only assert the rewind rule by not asserting it.
 */
public interface PoseHistory {

    /** The tick [InterpSnapshotSystem] last recorded for; `Tick(-1)` before its first run. */
    public val lastTick: Tick
}
