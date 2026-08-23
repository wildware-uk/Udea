package dev.wildware.moba.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.moba.Position
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.EffectApplier
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasCueQueue

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
 * Publishes health onto [Position.hp], and removes units that have run out of it.
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

    /** How many units have died. */
    public var deaths: Long = 0L
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
                netIds.free(netId)
                entity.remove()
                deaths++
            }
            index--
        }
    }
}

/**
 * Activates a unit's abilities when something worth using them on is in reach.
 *
 * ## What this is, honestly
 *
 * It is the smallest thing that makes the abilities observable in a running game: without an
 * activation path, `AbilityActivation.activate` is called by nobody and a fight never starts. It
 * is **not** the port of `example/.../system/UnitAISystem.kt`, which also chose targets, fled at
 * low health, respected `AITag.Fearless` and drove movement. This picks the highest-numbered
 * ability whose target policy is satisfied and fires it.
 *
 * It is deliberately separable: `MobaAbilityModule(autopilot = false)` leaves it out entirely, so
 * the real AI port replaces it by turning one flag off rather than by deleting code somebody else
 * is editing.
 *
 * Highest slot first, so a unit that has a special uses it when it is up and falls back to its
 * basic attack when it is not - which is what every one of the old characters' two-ability
 * loadouts meant by putting the special in `Slot.B`.
 */
public class AbilityAutopilotSystem(
    private val activation: AbilityActivation,
    private val targeting: AbilityTargeting,
    private val combat: CombatWorldRef,
    private val rules: CombatRules,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val units: Family = world.family { all(Abilities, Attributes, GameplayEffects, Combatant, Position) }

    /** How many activations this system has started. */
    public var activations: Long = 0L
        private set

    override fun onTick() {
        val entities = units.entities
        val now = tick
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            considerOne(netIds.netIdOf(entity), entity[Abilities], entity[Attributes], entity[GameplayEffects], now)
            index++
        }
    }

    private fun considerOne(
        self: NetId,
        granted: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        now: dev.wildware.udea.core.Tick,
    ) {
        // One ability at a time per unit, which is what `blockAnimations = true` meant on every
        // one of the old ability assets: a unit mid-spin must not also start swinging.
        var busy = 0
        while (busy < granted.slotCount) {
            if (granted.instanceAt(busy).isActive) return
            busy++
        }

        var slot = granted.slotCount - 1
        while (slot >= 0) {
            val instance = granted.instanceAt(slot)
            if (instance.isGranted && hasTarget(self, instance.abilityIndex)) {
                if (activation.canActivate(self, granted, attributes, effects, slot, now) ===
                    ActivationResult.Activated
                ) {
                    activation.activate(self, granted, attributes, effects, slot, now)
                    activations++
                    return
                }
            }
            slot--
        }
    }

    /** Whether the ability at [abilityIndex] has something to point at. */
    private fun hasTarget(self: NetId, abilityIndex: Int): Boolean {
        val policy = targeting.policyOf(abilityIndex) ?: return false
        val world = combat.world
        val candidate = world.nearest(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = policy.range,
            relation = policy.relation,
            viewerTeam = world.teamOf(self),
            exclude = if (policy.includesSelf) NetId.NONE else self,
        )
        if (candidate.isNone) return false
        if (policy.requiresDamaged && !rules.isDamaged(candidate)) {
            // A priest with one full-health ally beside it and one damaged ally further away must
            // still cast: `nearest` answered with the closest, so ask the area instead.
            return anyDamagedWithin(self, policy)
        }
        return true
    }

    private fun anyDamagedWithin(self: NetId, policy: TargetPolicy): Boolean {
        val world = combat.world
        world.query(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = policy.range,
            relation = policy.relation,
            viewerTeam = world.teamOf(self),
            exclude = if (policy.includesSelf) NetId.NONE else self,
            into = scratch,
        )
        var index = 0
        while (index < scratch.size) {
            if (rules.isDamaged(scratch[index])) return true
            index++
        }
        return false
    }

    private val scratch = NetIdBuffer(SCRATCH_TARGETS)

    private companion object {
        /** How many candidates the damaged-ally scan looks at. */
        const val SCRATCH_TARGETS: Int = 16
    }
}
