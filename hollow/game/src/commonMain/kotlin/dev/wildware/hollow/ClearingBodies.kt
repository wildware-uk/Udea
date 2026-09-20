package dev.wildware.hollow

import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.Transform3D

/**
 * Gives every prop that a character cannot walk through a static collision circle (issue #250).
 *
 * ## Why a system and not level content
 *
 * The clearing's models are a CC0 art pack and carry no colliders, so something has to decide how
 * wide a tree is. Two places could: the `.udealevel` file, which would mean a `PhysicsBody` and a
 * `Circle` saved on every one of its two hundred entities, or this. It is here because the answer
 * is a *rule* - `Prop.blockRadius` times the entity's scale - and a rule saved two hundred times
 * into a binary file is a rule nobody can change without rewriting the file. The editor (issue
 * #255) moves and swaps props; each one gets the right circle the tick after, with nothing to keep
 * in step.
 *
 * ## What it costs after the first tick
 *
 * The family is "scenery with no body", so after the level has loaded the only members left are the
 * props a character walks through - the grass, the flowers, the ground - and each costs one enum
 * read and a multiply. Every tree and stone leaves the family the tick it is given its circle, and
 * a prop the editor adds joins it for exactly one tick.
 *
 * `Transform3DSeedSystem` (`udea-physics2d`) is what then puts each new body where its
 * `Transform3D` says, so nothing here touches a position.
 *
 * Registered at `SimPhase.PreSimulation`, the phase for spawns and despawns, so a body added this
 * tick is built by the solver's reconciliation in the same tick's `SimPhase.Physics`.
 */
public class ClearingBodySystem : SimSystem() {

    /** Scenery with no body yet. Empty on every tick after the level loaded. */
    private val unbuilt: Family = world.family { all(Scenery, Transform3D).none(PhysicsBody) }

    /** Props given a collision circle since this system was built. A signal for a test. */
    public var built: Long = 0L
        private set

    override fun onTick() {
        unbuilt.forEach { entity ->
            val prop = entity[Scenery].prop
            // `scaleX` alone: the clearing is laid out at one uniform scale per prop, and a circle
            // has one radius - an ellipse is not a shape `udea-physics2d` has.
            val radius = prop.blockRadius * entity[Transform3D].scaleX
            if (radius <= 0f) return@forEach
            entity.configure {
                it += PhysicsBody(kind = BodyKind.Static)
                it += Circle(radius)
            }
            built++
        }
    }
}
