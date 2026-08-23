package dev.wildware.moba.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ai.UnitBrain
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.EffectApplier
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasCueQueue
import dev.wildware.udea.gas.GasServices

/**
 * Integrates [Motion] into [Position], once per tick, with damping.
 *
 * The whole of this game's movement, and the reason a knockback and an arrow both move at all:
 * `PhysicsWorld` is a no-op in this engine, so nothing else does. Per **tick** and not per second,
 * so thirty single steps and one `step(30)` land a knocked-back unit in the same place.
 */
public class CombatMotionSystem : SimSystem() {

    private val moving: Family = world.family { all(Motion, Position) }

    override fun onTick() {
        val entities = moving.entities
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            val motion = entity[Motion]
            val position = entity[Position]
            position.x += motion.vx
            position.y += motion.vy
            motion.vx *= motion.damping
            motion.vy *= motion.damping
            // Snap to rest rather than let a float decay for ever: a unit still creeping by 1e-20
            // per tick is a unit whose position never repeats, which makes a snapshot diff noisy
            // for no gameplay reason.
            if (kotlin.math.abs(motion.vx) < Motion.REST_SPEED) motion.vx = 0f
            if (kotlin.math.abs(motion.vy) < Motion.REST_SPEED) motion.vy = 0f
            index++
        }
    }
}

/**
 * Arrows: hit the first enemy they touch, expire when their life runs out.
 *
 * Ported from `example/.../system/ProjectileSystem.kt`, which despawned a projectile **only** on
 * contact - so every arrow that missed stayed in the world for the rest of the match. Here a
 * projectile carries [Projectile.lifeTicks] and a miss is cleaned up.
 *
 * Runs in `SimPhase.PostPhysics`, after [CombatMotionSystem] has moved it: contact is read from
 * where the arrow is *now*, not where it was when the tick started.
 */
public class ProjectileSystem(
    private val combat: CombatWorldRef,
    private val rules: CombatRules,
    private val applier: EffectApplier,
    private val cues: GasCueQueue,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val projectiles: Family = world.family { all(Projectile, Position, Motion) }

    /** How many arrows have hit something. A zero here with arrows in flight is a broken seam. */
    public var hits: Long = 0L
        private set

    /** How many arrows expired without hitting anything. */
    public var expiries: Long = 0L
        private set

    override fun onTick() {
        val entities = projectiles.entities
        val now = tick
        // Backwards, because a hit removes the entity and Fleks compacts the bag; walking forwards
        // would skip the entry that moved into the slot just vacated.
        var index = entities.size - 1
        while (index >= 0) {
            val entity: Entity = entities[index]
            val projectile = entity[Projectile]
            val position = entity[Position]
            val world = combat.world
            val target = world.nearest(
                centreX = position.x,
                centreY = position.y,
                radius = projectile.hitRadius,
                relation = TeamRelation.Enemy,
                viewerTeam = projectile.teamId,
                exclude = projectile.owner,
            )
            if (!target.isNone) {
                strike(projectile, entity[Motion], target, now)
                despawn(entity)
                hits++
            } else {
                projectile.lifeTicks--
                if (projectile.lifeTicks <= 0) {
                    despawn(entity)
                    expiries++
                }
            }
            index--
        }
    }

    private fun strike(
        projectile: Projectile,
        motion: Motion,
        target: NetId,
        now: dev.wildware.udea.core.Tick,
    ) {
        val world = combat.world
        val attributes = world.attributesOf(target) ?: return
        val effects = world.effectsOf(target) ?: return
        applier.begin(rules.effects.damage)
            .magnitude(rules.tags.dataDamage, -projectile.damage)
            .applyTo(effects, attributes, now, targetId = target, source = projectile.owner)
        applier.begin(rules.effects.stun)
            .magnitude(rules.tags.dataDuration, projectile.stunTicks.toFloat())
            .applyTo(effects, attributes, now, targetId = target, source = projectile.owner)
        // Pushed along the arrow's own flight, which is the direction it was travelling when it
        // landed rather than the direction the archer was facing when it was loosed.
        val speed = kotlin.math.sqrt(motion.vx * motion.vx + motion.vy * motion.vy)
        if (speed > 0f) {
            world.impulse(
                target,
                motion.vx / speed * projectile.knockback,
                motion.vy / speed * projectile.knockback,
            )
        }
        cues.emit(
            cueId = MobaCues.ARROW_HIT,
            tick = now,
            source = projectile.owner,
            target = target,
            payload0 = projectile.damage,
        )
    }

    private fun despawn(entity: Entity) {
        netIds.free(netIds.netIdOf(entity))
        entity.remove()
    }
}

/**
 * A body on the field: a unit that has died and has not been cleared away yet.
 *
 * ## Why there is a component here at all
 *
 * The *deadness* is [Position.hp] and needs no component - that is what makes a corpse survive a
 * `time.rewind`, since `hp` is one of the three floats `MobaGame.componentRegistry` snapshots and
 * a marker component would have to be minted in the reviewed `net-components.lock` to get the
 * same property. What needs somewhere to live is [diedTick], and only because a body is cleared
 * away eventually rather than never.
 *
 * ## What it deliberately does not survive
 *
 * A snapshot restores it, `diedTick` and all. It is `@Replicated` and it is in
 * `MobaGame.componentRegistry`, which it was not when it was first written: the KDoc here said a
 * restored corpse would start its linger again from the tick of the restore, and called that "the
 * smallest wrong answer available". The measured cost of an unregistered component turned out to
 * be larger than that. Capture asks the registry what to look for, so a component outside it is
 * not merely restored wrong - it is invisible, and an entity the restore has to *rebuild* comes
 * back without it entirely. A corpse that survives a rewind as a live unit is not a linger timer
 * five seconds out; it is a body that stands back up.
 *
 * `@Sim` and not `@Net`: a client is told a unit is dead by its `CharacterView.state`, and the
 * tick it died on is what the linger is measured against here and nowhere else.
 */
@Replicated
public class Corpse(
    /** The tick this unit's health reached zero. */
    @Sim @JvmField public var diedTick: Long = 0L,
) : Component<Corpse> {

    override fun type(): ComponentType<Corpse> = Corpse

    override fun toString(): String = "Corpse(died=$diedTick)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Corpse>()
}

/**
 * Publishes health onto [Position.hp], and retires units that have run out of it.
 *
 * ## The corpse, and why there is one
 *
 * This system used to call `entity.remove()` on the tick health reached zero, and
 * [dev.wildware.moba.CharacterStateSystem]'s KDoc said what that cost: **no unit in this game has
 * ever played its death animation.** All six characters have a packed, cut, addressable
 * `*_death_sheet`; a unit's last frame was mid-swing or mid-flinch and then it was gone between
 * one frame and the next. The old `example/.../system/GameUnitSystem.checkDead` did the opposite
 * and did it deliberately: it forced the death animation, turned every fixture into a sensor,
 * made the body static, switched the controller off - and **left the body on the field**.
 *
 * So a unit that runs out of health is *retired* rather than removed. It keeps its [Position]
 * (hp at or below zero), its `GameUnit` and its `CharacterView`, and it loses exactly one thing:
 * its [Combatant]. That single removal is what the old sensor-and-static-body pair did, in a
 * game with no physics:
 *
 * | it drops out of | consequence |
 * |---|---|
 * | [CombatIndex]'s family | nothing can target it, hit it, heal it or lunge at it |
 * | [AbilityAutopilotSystem]'s family | it stops swinging |
 * | this system's own family | it is retired exactly once |
 *
 * `UnitBattleSystem` already returned early on `position.hp <= 0f`, so a corpse does not walk,
 * and `HealthbarRenderSystem` already skips a unit at zero health, so a corpse carries no bar.
 * Both of those were written for a corpse that did not exist yet and are correct now that one
 * does.
 *
 * ## What "the animation plays" rests on
 *
 * Nothing here writes an animation. [dev.wildware.moba.CharacterStateSystem] asks
 * `Position.hp <= 0f` and answers [dev.wildware.moba.UnitState.Death], which is a *derived* state
 * exactly like the other four - and that is what makes the corpse survive a `time.rewind`.
 * `Position` is in `MobaGame.componentRegistry` and `hp` is one of its three snapshotted floats,
 * so a restored world restores the deadness, and the first tick after the restore recomputes the
 * pose from it. A `Corpse` marker component would not have survived: the registry names two
 * component types and a third would have to be minted in `net-components.lock`.
 *
 * The honest costs, both real:
 *
 * - **A rewind does not bring a unit back to life.** `Combatant`, `Attributes` and `Abilities`
 *   are not in the registry, so a restore does not put back the `Combatant` this system removed.
 *   A unit that was alive at the restored tick comes back as a corpse. That is the same hole that
 *   already makes a rewound world's combat inert, and it closes with the registry rather than
 *   here.
 * - **A corpse is counted as a unit while it lies there.** `world.query_entities with=GameUnit`
 *   returns bodies for [CORPSE_TICKS] after they fall, and `GameUnit`'s own KDoc still says a
 *   dead unit is a removed entity - that sentence is now wrong and it is in a file this change
 *   does not own.
 * - **The body is cleared after [CORPSE_TICKS] rather than left for ever**, which is where this
 *   departs from `GameUnitSystem`. That system left the corpse until the level ended, and a
 *   sixty-fourth-second `NetId` space plus a `world.query_entities` that never stops growing is
 *   the cost of copying it exactly. Five seconds is long enough that a human watching a fight
 *   sees who died and where; [Corpse.diedTick] is what counts it, and its KDoc says what a
 *   rewind does to that count.
 *
 * ## Why health is mirrored rather than moved
 *
 * `Position.hp` is the field the snapshot ring records, the field `world.get_component_field`
 * reads and the field a healthbar renderer will read. GAS health lives in an `Attributes`
 * component that none of those three know about - there is no `Attributes` replicator, no
 * snapshot schema entry and no agent component type for it. Mirroring one float per unit per tick
 * makes every attribute change visible through the surfaces that already exist, which is what
 * lets an agent watch a fight without a `gas.*` toolset having been written yet.
 *
 * It is a **mirror and not the truth**: writing `hp` through the agent surface changes the number
 * for one tick and is overwritten here on the next. That is stated rather than defended - the
 * honest fix is an `Attributes` replicator, which is a `udea-net`/`udea-codegen` change.
 *
 * Runs in `SimPhase.Gameplay`: after `AttributeSystem` has recomputed, so `hp` is this tick's
 * health rather than last tick's.
 */
public class DeathSystem(
    private val rules: CombatRules,
    private val cues: GasCueQueue,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val units: Family = world.family { all(Combatant, Position, Attributes) }

    /** The bodies. Disjoint from [units] by construction: retiring removes the [Combatant]. */
    private val corpses: Family = world.family { all(Corpse) }

    /** How many units have died. */
    public var deaths: Long = 0L
        private set

    /** How many bodies have been cleared away. */
    public var cleared: Long = 0L
        private set

    override fun onTick() {
        val entities = units.entities
        val now = tick
        var index = entities.size - 1
        while (index >= 0) {
            val entity: Entity = entities[index]
            val health = entity[Attributes].current(rules.attributes.health)
            entity[Position].hp = health
            if (health <= 0f) {
                val netId = netIds.netIdOf(entity)
                cues.emit(MobaCues.DEATH, now, source = netId, target = netId)
                retire(entity, now.value)
                deaths++
            }
            index--
        }
        clearOldBodies(now.value)
    }

    /**
     * Removes every body that has lain there for [CORPSE_TICKS].
     *
     * `<=` on the elapsed count and not `==`: a `time.step(100)` advances the clock a hundred
     * ticks between two runs of this system, and an equality would step straight over the one
     * tick a body was due to be cleared on and leave it on the field for ever.
     */
    private fun clearOldBodies(now: Long) {
        val entities = corpses.entities
        // Backwards, because a removal compacts the Fleks bag and walking forwards would skip the
        // entry that moved into the slot just vacated.
        var index = entities.size - 1
        while (index >= 0) {
            val entity: Entity = entities[index]
            if (now - entity[Corpse].diedTick >= CORPSE_TICKS) {
                netIds.free(netIds.netIdOf(entity))
                entity.remove()
                cleared++
            }
            index--
        }
    }

    /**
     * Takes [entity] out of the fight and leaves the body where it fell.
     *
     * The [Motion] is zeroed as well as the [Combatant] removed, because the last thing that
     * happened to most units is a knockback: a corpse that kept its velocity would slide away
     * from the blow that killed it for a fifth of a second, which reads as a body being dragged.
     *
     * The [dev.wildware.udea.core.identity.NetId] is deliberately **kept**. The entity is still
     * there, and freeing an id whose entity is alive would hand the same id to the next arrow
     * spawned - so `world.describe_entity` on a dead soldier would answer with an arrow.
     */
    private fun retire(entity: Entity, now: Long) {
        entity.getOrNull(Motion)?.let { motion ->
            motion.vx = 0f
            motion.vy = 0f
        }
        entity.configure {
            it -= Combatant
            it += Corpse(diedTick = now)
        }
    }

    public companion object {

        /**
         * How long a body lies on the field, in ticks.
         *
         * Five seconds at 60Hz. Long enough that a human watching the fight - or an agent
         * capturing it - sees which unit fell and where, which is the whole point of leaving one;
         * short enough that a ten-minute match is not a field of bodies and a `NetId` space that
         * only ever shrinks. It is longer than the longest death animation in the roster
         * (`orc_elite_death`, eleven frames, 66 ticks), so every corpse reaches its final frame
         * and holds it rather than being cleared mid-fall.
         */
        public const val CORPSE_TICKS: Long = 300L
    }
}

/**
 * Drives every AI unit's decision for the tick: run, heal, shoot, or swing.
 *
 * ## What changed
 *
 * This class used to be, by its own admission, not the port of
 * `example/.../system/UnitAISystem.kt`: it fired the highest-numbered ability whose target policy
 * was satisfied and did nothing else. It now owns none of the thinking - that is
 * [dev.wildware.moba.ai.UnitBrain], which is the port - and what is left here is the loop, the
 * three services the brain needs from the context, and the one question a brain cannot answer
 * because it has no Fleks types on its surface: whether a human is steering this entity.
 *
 * The name and the constructor are unchanged on purpose. `MobaAbilityModule` registers this in
 * `SimPhase.Ability` before `AbilitySystem`, `Player` names it in its KDoc, and turning
 * `MobaAbilityModule(autopilot = false)` off still removes the whole AI - so the port arrives
 * without a second registration, a second family or a second activation path competing with this
 * one for the same ability slots.
 *
 * ## What it reads off the context rather than the constructor
 *
 * [dev.wildware.udea.gas.GasServices.KEY] for the effect applier and the ability table, and
 * `GameContext.rng` for the `AI` stream. Both are services the world already registers, and
 * reaching them through `ctx` rather than through four more constructor parameters keeps the
 * registration in `MobaAbilityModule` - a file this change does not touch - exactly as it was.
 *
 * ## What it still does not do
 *
 * It does not walk a unit anywhere. Closing on a target is `UnitBattleSystem`'s job, in
 * `SimPhase.Gameplay`, and a retreat here is an impulse into `Motion` that that system's closing
 * partly cancels for as long as the runner still has a target - see the report.
 */
public class AbilityAutopilotSystem(
    private val activation: AbilityActivation,
    private val targeting: AbilityTargeting,
    private val combat: CombatWorldRef,
    private val rules: CombatRules,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val units: Family = world.family { all(Abilities, Attributes, GameplayEffects, Combatant, Position) }

    private val gas: GasServices = ctx[GasServices.KEY]

    init {
        // One box, not two. The brain reaches the units around a unit through `rules.combat`, so
        // a caller that handed this system a different `CombatWorldRef` from the one the rules
        // hold would get a system that queried an empty world while the abilities queried a full
        // one - a fight in which nobody can see anybody, and nothing to read off a stack trace.
        check(combat === rules.combat) {
            "the autopilot and the combat rules must share one CombatWorldRef; got $combat and ${rules.combat}"
        }
    }

    /** The port. One per system, so its scratch buffer and its counters are per world. */
    private val brain: UnitBrain = UnitBrain(rules, targeting, gas.abilities, activation)

    /** How many activations this system has started. */
    public val activations: Long get() = brain.activations

    /** How many unit-ticks have been spent running away. */
    public val retreats: Long get() = brain.retreats

    /** How many heals have been cast because somebody was hurt. */
    public val heals: Long get() = brain.heals

    /** How many units have been given `ability/passive_health_regen`. */
    public val regensApplied: Long get() = brain.regensApplied

    override fun onTick() {
        val entities = units.entities
        val now = tick
        val rng = ctx.rng
        val applier = gas.applier
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            // A human's unit is not routed by the AI **and does not fire its own abilities**.
            // `Player` is a marker on the one entity `PlayerControlSystem` steers, and that
            // system is what activates its slots - one key per slot, read on the same tick:
            // `attack` fires slot 0 and `attack_2` fires slot 1. It used to be "highest granted
            // slot that will fire wins", which meant one key silently spent the special and the
            // second key had nothing left to do.
            //
            // This used to be `mayRetreat = Player !in entity`: the player was exempt from
            // retreating and from nothing else, so the autopilot went on activating the abilities
            // of the unit a human was holding the controls of. That was invisible for exactly as
            // long as the player was a soldier, whose melee and arrow move nobody, and it stopped
            // being invisible the moment `blueprint/player` went back to inheriting `orc_elite` -
            // as it did in the old game. `OrcSpinExec.onActivate` lunges the caster at the nearest
            // enemy, so an auto-fired spin threw the player across the clearing on the first tick
            // of every session and then decayed for a second afterwards, which is
            // `MobaInputTest`'s "the AI walked the player" and reads to a human as the controls
            // being ignored.
            //
            // Skipping the entity rather than adding a second flag: every branch of `think` -
            // retreat, activate, apply passive regen - is a decision about what this unit does
            // next, and a unit somebody is driving has a driver for that.
            if (Player !in entity) {
                brain.think(
                    self = netIds.netIdOf(entity),
                    granted = entity[Abilities],
                    attributes = entity[Attributes],
                    effects = entity[GameplayEffects],
                    now = now,
                    rng = rng,
                    applier = applier,
                    mayRetreat = true,
                )
            }
            index++
        }
    }
}
