package dev.wildware.moba.match

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.ability.Motion
import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.Attributes

/**
 * The player dies, lies there, and stands back up.
 *
 * ## What it replaces
 *
 * Nothing at all, and that was the single worst thing about playing this game. A player whose
 * soldier ran out of health kept the camera, kept the keyboard, and simply stopped affecting
 * anything: `PlayerControlSystem` went on writing move axes onto a unit `UnitBattleSystem`
 * refuses to walk, and `PlayerControlSystem`'s attack found abilities on an entity nothing could
 * target. No message, no restart short of relaunching the process. A play agent reported it as
 * "the controls just stop responding", which is exactly what it looks like from outside.
 *
 * ## Why a respawn and not a game-over screen
 *
 * Because a MOBA is the genre this is, and in one a death is a setback with a timer on it rather
 * than the end of the session. It is also the mechanism Phase 5's creep waves need, so building
 * it now costs a component and a system, and building a bespoke game-over instead would cost
 * that plus the work of deleting it later.
 *
 * ## The mechanism, and why it is three lines rather than a respawn pipeline
 *
 * `DeathSystem` does not delete a dead unit. It removes its `Combatant` and adds a `Corpse`,
 * leaving the entity, its net id, its `Abilities` and its `Attributes` exactly where they were -
 * the old game's `GameUnit.isDead` with the absence of a component in place of the flag. So
 * standing a unit back up is:
 *
 * 1. write the recorded maximum back into the `health` **base** attribute (damage is an instant
 *    effect and instant effects write `base`, so that is the number that was reduced);
 * 2. put the `Combatant` back and take the `Corpse` away, which returns the unit to every
 *    targeting family at once;
 * 3. move it to where the level placed it, and stop it dead - a corpse is usually mid-knockback,
 *    and a unit that resumed that velocity on standing up would slide away from its own grave.
 *
 * `Position.hp` is written too, even though `DeathSystem` recomputes it next tick from the same
 * number. It is the field `CharacterStateSystem` reads to decide whether to draw the death
 * animation, and leaving it at zero for one tick is a corpse that flickers on the frame it
 * stands up.
 *
 * ## Ordering
 *
 * `SimPhase.Cleanup`, **before** `CharacterStateSystem` - declared in [MatchModule] rather than
 * left to registration order - so the pose is derived from the health this system just restored
 * rather than from last tick's. After [MatchSystem] in the same phase, so a unit does not stand
 * up into a match that was decided on this very tick.
 *
 * ## What it deliberately does not do
 *
 * - **It revives only while the match is being fought.** A player who dies as the last of their
 *   side ends the match, and standing them back up during the result would put a living unit on
 *   a field whose scoreboard says everyone on that side is dead. The timer keeps running and the
 *   restart clears the whole world a few seconds later anyway.
 * - **It grants a [Respawn] only to a unit with a [Player] on it.** Everything below the family
 *   would work for any unit; the decision to respawn only the one a human drives is a game
 *   design decision and it is made here, in one line, rather than baked into the component.
 * - **It does not clear lingering effects.** See [Respawn].
 */
public class RespawnSystem(
    /** The `health` attribute this game's units are dressed with. See [MatchModule]. */
    private val health: AttributeId,
    /** Read to find out whether a match is still being fought. */
    private val service: MatchService,
) : SimSystem() {

    /**
     * Units eligible to come back. `Player` is the whole of the eligibility rule today.
     *
     * `Attributes` and `Position` are in the family rather than fetched with `getOrNull`,
     * because a unit that has neither cannot be revived at all and quietly skipping it is how a
     * player ends up permanently dead with nothing in a log.
     */
    private val candidates: Family = world.family { all(Player, GameUnit, Position, Attributes) }

    /** Units stood back up by this system. A signal for a test, not state. */
    public var revivals: Long = 0L
        private set

    override fun onTick() {
        val entities = candidates.entities
        val now = tick.value
        var index = 0
        with(world) {
            while (index < entities.size) {
                val entity: Entity = entities[index]
                val respawn = entity.getOrNull(Respawn) ?: grant(entity)
                if (Corpse in entity) dead(entity, respawn, now) else alive(respawn)
                index++
            }
        }
    }

    /**
     * Records this unit's maximum health and spawn point, on the tick the level placed it.
     *
     * The grant is separate from the death path so that the numbers come from a unit that is
     * intact. Reading a maximum off a unit that is already on fire would respawn it on fire.
     */
    private fun grant(entity: Entity): Respawn = with(world) {
        val position = entity[Position]
        val respawn = Respawn(
            maxHealth = entity[Attributes].base(health),
            spawnX = position.x,
            spawnY = position.y,
        )
        entity.configure { it += respawn }
        respawn
    }

    /** A living unit has no pending respawn. Idempotent, and the reset after a revival. */
    private fun alive(respawn: Respawn) {
        respawn.readyTick = Respawn.NOT_SCHEDULED
    }

    /** Schedules the return the first tick a body is seen, then waits for the tick to arrive. */
    private fun dead(entity: Entity, respawn: Respawn, now: Long) {
        if (!respawn.isScheduled) {
            respawn.readyTick = now + MatchRules.RESPAWN_TICKS
            respawn.deaths++
            return
        }
        if (now < respawn.readyTick) return
        // Not during a result. See the class KDoc: reviving into a decided match would put a
        // living unit on a field whose scoreboard says its side has none.
        if (service.phase != MatchPhase.Fighting) return
        revive(entity, respawn)
    }

    /** Puts back the health, the `Combatant` and the position, and clears the body. */
    private fun revive(entity: Entity, respawn: Respawn) {
        with(world) {
            val attributes = entity[Attributes]
            // `setBase` and not a write to `current`: `current` is rebuilt from `base` plus every
            // active modifier by `AttributeRecompute` on the next `SimPhase.Attribute`, so a write
            // to it would survive exactly until then and the unit would fall over again.
            attributes.setBase(health, respawn.maxHealth)
            val position = entity[Position]
            position.x = respawn.spawnX
            position.y = respawn.spawnY
            // The mirror, so the death animation ends on this frame rather than on the next tick.
            position.hp = respawn.maxHealth
            entity.getOrNull(Motion)?.let { motion ->
                motion.vx = 0f
                motion.vy = 0f
            }
            val team = entity[GameUnit].team
            entity.configure {
                it -= Corpse
                it += Combatant(teamId = team)
            }
            respawn.readyTick = Respawn.NOT_SCHEDULED
        }
        revivals++
    }

    override fun toString(): String = "RespawnSystem(revivals=$revivals)"
}
