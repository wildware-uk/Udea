package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.ability.MobaEffects
import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.GameplayEffects

/**
 * Decides what every unit is *doing*, so the renderer has something to draw other than `Idle`.
 *
 * ## The hole this fills
 *
 * [CharacterView] was built as a seam - a simulation system writes [CharacterView.state], the
 * renderer reads it - and shipped with **nothing on the writing side**. Six characters, thirty-
 * three animations and five states were packed, loaded, cut out of the atlas and addressable, and
 * every unit in the running game was frozen on frame 0 of `idle` for the life of the process,
 * because no system ever called `enter`. `MobaShot` cycled the states by hand for its screenshot,
 * which is why the roster capture looked alive and the game did not.
 *
 * ## Why the state is derived rather than pushed
 *
 * Nothing calls `enter` from the outside here either. This system asks four questions of state
 * that already exists, in priority order, once a tick:
 *
 * | State | Asked | Why that question |
 * |---|---|---|
 * | [UnitState.Attack] | any ability instance is active | An activation is the swing. The windup, the hit tick and the recovery are the same numbers the animation is cut into ([dev.wildware.moba.ability.MeleeAttackExec.HIT_TICK] is `attack_hit`'s frame), so the picture and the damage cannot drift apart. |
 * | [UnitState.Hit] | an `ability/stun` effect is on it | Every landed blow in this game stuns ([dev.wildware.moba.ability.CombatRules.stun]), so "stunned" *is* "just been hit", and a unit that cannot act is exactly the one that should be flinching. |
 * | [UnitState.Walk] | [GameUnit.movingTick] is this tick | Written by the one system that moves a unit, so this cannot disagree with whether it actually moved. |
 * | [UnitState.Idle] | otherwise | |
 *
 * The alternative - a combat system calling `enter(Attack, tick)` at each of its call sites - is
 * how an animation state machine gets out of step with the thing it is animating: one path that
 * forgets to leave the state leaves a unit swinging forever, and the bug is in the file that
 * forgot rather than in the file that draws. Deriving it means the picture is a pure function of
 * the simulation, which is also what makes it survive a `time.rewind` that [CharacterView] itself
 * does not: the state is recomputed from restored components on the first tick after a restore.
 *
 * [CharacterView.enter] ignores a re-entry into the state already showing, so writing the same
 * answer every tick holds the playhead where it is rather than pinning it to frame 0.
 *
 * ## Ordering, and why it is `Cleanup`
 *
 * After the fighting and after the walking, in the same tick, so a unit that started swinging on
 * this tick is drawn swinging on this tick rather than one behind. Before
 * [CharacterAnimationSystem], which is what turns the state and its start tick into the exact
 * tick each `animNotify` frame lands on - a notify computed from a state written *after* it would
 * be a frame late on the tick a unit changed what it was doing.
 *
 * ## What it deliberately does not do
 *
 * **It never enters [UnitState.Death].** `dev.wildware.moba.ability.DeathSystem` removes an
 * entity on the tick its health reaches zero, so there is no entity left to play a death
 * animation on and no corpse to draw. The art is packed and addressable and nothing shows it.
 * Fixing that is a two-stage death - a dying tag, the animation, then the removal - which changes
 * when a net id is freed and when a unit stops being a legal target, and that is a combat
 * decision rather than an animation one.
 */
public class CharacterStateSystem(
    /** Which effect index means `ability/stun`. */
    private val effects: MobaEffects,
) : SimSystem() {

    private val units: Family = world.family { all(CharacterView, GameUnit) }

    /** State changes since the process started. Zero over a running fight is a broken seam. */
    public var transitions: Long = 0L
        private set

    override fun onTick() {
        val entities = units.entities
        val now = tick.value
        var index = 0
        while (index < entities.size) {
            val entity = entities[index]
            val view = entity[CharacterView]
            val next = stateOf(entity, now)
            if (view.state != next) {
                view.enter(next, now)
                transitions++
            }
            index++
        }
    }

    private fun stateOf(entity: Entity, now: Long): UnitState {
        if (isCasting(entity)) return UnitState.Attack
        if (isStunned(entity)) return UnitState.Hit
        if (entity[GameUnit].movingTick == now) return UnitState.Walk
        return UnitState.Idle
    }

    /** Whether any granted slot has an activation in flight. */
    private fun isCasting(entity: Entity): Boolean {
        val abilities = entity.getOrNull(Abilities) ?: return false
        var slot = 0
        while (slot < abilities.slotCount) {
            if (abilities.instanceAt(slot).isActive) return true
            slot++
        }
        return false
    }

    /** Whether an `ability/stun` is currently on this unit. */
    private fun isStunned(entity: Entity): Boolean {
        val applied = entity.getOrNull(GameplayEffects) ?: return false
        var slot = 0
        while (slot < applied.count) {
            if (applied.defIndexAt(slot) == effects.stun) return true
            slot++
        }
        return false
    }
}
