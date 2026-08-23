package dev.wildware.moba.ai

import dev.wildware.moba.ability.AbilityTargeting
import dev.wildware.moba.ability.CombatRules
import dev.wildware.moba.ability.MeleeAttackExec
import dev.wildware.moba.ability.MobaScale
import dev.wildware.moba.ability.Motion
import dev.wildware.moba.ability.NetIdBuffer
import dev.wildware.moba.ability.TargetPolicy
import dev.wildware.moba.ability.TeamRelation
import dev.wildware.udea.core.RngService
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.AbilityTable
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.EffectApplier
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GameplayTag

/**
 * One unit's decision for one tick: run, heal, shoot, or swing.
 *
 * ## What this is the port of
 *
 * `example/src/main/kotlin/dev/wildware/udea/example/system/UnitAISystem.kt`, whose four
 * decisions this reproduces in the order that file made them:
 *
 * | Old line | Old behaviour | Here |
 * |---|---|---|
 * | `:50` | heal when `health < maxHealth / 2` | [wantsHeal], widened to damaged allies |
 * | `:58` | nearest living enemy within `10F` | [SIGHT_RADIUS], `CombatWorld.nearest` |
 * | `:66` | flee at `health <= 10` unless `AITag.Fearless` | [FLEE_HEALTH] and [AiRoster] |
 * | `:71` | ranged past `.5F`, melee inside it | [MeleeAttackExec.RANGE] and the AI hint tags |
 *
 * What it replaces is `AbilityAutopilotSystem`'s original body, whose own KDoc admitted it: it
 * fired the highest-numbered ready ability whose target policy was satisfied and did nothing
 * else. A soldier therefore loosed an arrow point-blank, a priest healed on a cooldown rather
 * than on a wound, and a unit on two hit points stood still and swung until it died.
 *
 * ## Why the brain is not the system
 *
 * `AbilityAutopilotSystem` is a Fleks system, so it can only be exercised by building a world.
 * This is a plain object with no Fleks type on its surface: everything it reads comes through
 * `CombatWorld`, `Attributes` and `Abilities`, so one decision can be put under a test on its
 * own. The system is the loop; this is the thinking.
 *
 * ## Tick-denominated, seeded, allocation-free
 *
 * No decision reads a wall clock or a `Float` of seconds: [FLEE_HEALTH] is health,
 * [SIGHT_RADIUS] is world units, and every cooldown question goes to
 * [AbilityActivation.activate], which compares ticks. The one random draw is the retreat
 * heading, taken from [RngStream.AI] through the injected [RngService] - a named stream, so a
 * loot roll cannot shift a rout and a replay reproduces every unit's flight path. Exactly one
 * draw is taken per retreating unit per tick on **both** branches of [retreat], so the stream
 * advances by a count that is a pure function of who is running rather than of where they stand.
 *
 * Per tick this allocates nothing: the two area scans fill [allies], a [NetIdBuffer] this object
 * owns and reuses, and every other value it touches is a primitive.
 */
public class UnitBrain(
    /** The damage, heal, stun and knockback helpers, and the way to the units around a unit. */
    private val rules: CombatRules,
    /** What each ability wants to be pointed at. */
    private val targeting: AbilityTargeting,
    /** Every ability definition, for the AI hint tags on each. */
    private val abilityTable: AbilityTable,
    /** The gate and the trigger. */
    private val activation: AbilityActivation,
) {

    private val allies = NetIdBuffer(SCAN_CAPACITY)

    /** How many activations this brain has started. */
    public var activations: Long = 0L
        private set

    /** How many unit-ticks have been spent running away. */
    public var retreats: Long = 0L
        private set

    /** How many heals it has cast because somebody was hurt. */
    public var heals: Long = 0L
        private set

    /** How many units it has put `ability/passive_health_regen` on. */
    public var regensApplied: Long = 0L
        private set

    /**
     * Decides what [self] does this tick, and does it.
     *
     * @param mayRetreat `false` for a unit a human is steering: a player who is winning does not
     *   want their soldier deciding to leave. Their abilities still fire, which is what the
     *   attack key already did through this same activation path.
     */
    public fun think(
        self: NetId,
        granted: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        now: Tick,
        rng: RngService,
        applier: EffectApplier,
        mayRetreat: Boolean,
    ) {
        applyPassiveRegen(self, attributes, effects, now, applier)

        val world = rules.combat.world
        val x = world.x(self)
        val y = world.y(self)
        val team = world.teamOf(self)
        val health = attributes.current(rules.attributes.health)
        val maxHealth = attributes.current(rules.attributes.maxHealth)
        val threat = world.nearest(x, y, SIGHT_RADIUS, TeamRelation.Enemy, team, self)

        // Running is decided before the busy check, and deliberately: the old AI drove movement
        // through `CharacterController` and abilities through `AbilitySystem`, so a unit mid-swing
        // that dropped below ten still ran. A stun still stops it - `ability/stun` is applied to
        // the unit rather than to this decision, and `AbilityActivation.tick` cancels the swing.
        if (mayRetreat && isRouted(health, team) && !threat.isNone) {
            retreat(self, x, y, threat, rng)
            return
        }

        // One ability at a time per unit, which is what `blockAnimations = true` meant on every
        // one of the old ability assets: a unit mid-spin must not also start swinging.
        var busy = 0
        while (busy < granted.slotCount) {
            if (granted.instanceAt(busy).isActive) return
            busy++
        }

        if (tryHeal(self, granted, attributes, effects, now, x, y, team, health, maxHealth)) return
        if (threat.isNone) return
        tryAttack(self, granted, attributes, effects, now, x, y, threat)
    }

    // --- running away ---------------------------------------------------------------------

    /**
     * Pushes [self] away from [threat], with a seeded spread.
     *
     * The old code wrote `controller.movement.set(heading.nor().scl(-1F))` - straight away from
     * the attacker, every unit, every tick, so a routed line stayed a line. The spread is up to
     * [RETREAT_SPREAD] radians either side of that heading, drawn once per unit per tick from
     * [RngStream.AI], which scatters a rout without making any one unit's path unrepeatable.
     *
     * It is an impulse into [Motion] rather than a write to `Position` because `Motion` is what
     * this game integrates (`CombatMotionSystem`) and what a knockback already uses, so a unit
     * being shoved by an orc and running from it resolve into one velocity instead of two writers
     * fighting over a coordinate. [RETREAT_IMPULSE] is sized against `Motion.UNIT_DAMPING` so the
     * speed it settles at is [RETREAT_SPEED] world units per tick - a shade above the fastest
     * unit's walk, so a runner outpaces what is chasing it.
     */
    private fun retreat(self: NetId, x: Float, y: Float, threat: NetId, rng: RngService) {
        val world = rules.combat.world
        val dx = x - world.x(threat)
        val dy = y - world.y(threat)
        val spread = rng.nextFloat(RngStream.AI) - 0.5f
        val heading = if (dx * dx + dy * dy < EPSILON * EPSILON) {
            // Standing exactly on top of its attacker. There is no "away", so any direction will
            // do - and this one is at least seeded, which the +x that `CombatRules.knockback`
            // picks for the same case is not.
            spread * TAU
        } else {
            kotlin.math.atan2(dy, dx) + spread * RETREAT_SPREAD
        }
        world.impulse(
            self,
            kotlin.math.cos(heading) * RETREAT_IMPULSE,
            kotlin.math.sin(heading) * RETREAT_IMPULSE,
        )
        retreats++
    }

    // --- healing --------------------------------------------------------------------------

    /**
     * Casts a heal when somebody in reach is worth healing. @return whether it cast one.
     *
     * The old rule was `health.currentValue < maxHealth.currentValue / 2F` on **itself only**, so
     * the priest - the one unit on the field whose ability heals everybody within three units -
     * cast it exclusively when the priest was the one dying. [wantsHeal] widens that to any ally
     * inside the ability's own declared radius, which is what a healer is for and what
     * `PriestHealExec` was already written to do the moment something told it to fire.
     */
    private fun tryHeal(
        self: NetId,
        granted: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        now: Tick,
        x: Float,
        y: Float,
        team: Int,
        health: Float,
        maxHealth: Float,
    ): Boolean {
        var slot = granted.slotCount - 1
        while (slot >= 0) {
            val instance = granted.instanceAt(slot)
            if (instance.isGranted && hasTag(instance.abilityIndex, rules.tags.hintHeal)) {
                val policy = targeting.policyOf(instance.abilityIndex)
                if (policy != null &&
                    wantsHeal(self, x, y, team, health, maxHealth, policy) &&
                    activation.activate(self, granted, attributes, effects, slot, now) ===
                    ActivationResult.Activated
                ) {
                    activations++
                    heals++
                    return true
                }
            }
            slot--
        }
        return false
    }

    /** Whether the caster or an ally inside [policy]'s radius is below [HURT_FRACTION] health. */
    private fun wantsHeal(
        self: NetId,
        x: Float,
        y: Float,
        team: Int,
        health: Float,
        maxHealth: Float,
        policy: TargetPolicy,
    ): Boolean {
        if (health < maxHealth * HURT_FRACTION) return true
        val world = rules.combat.world
        world.query(x, y, policy.range, TeamRelation.Friendly, team, NetId.NONE, allies)
        var index = 0
        while (index < allies.size) {
            val ally = allies[index]
            index++
            if (ally == self) continue
            val allyMax = rules.maxHealthOf(ally)
            if (allyMax > 0f && rules.healthOf(ally) < allyMax * HURT_FRACTION) return true
        }
        return false
    }

    // --- fighting -------------------------------------------------------------------------

    /**
     * Fires the right kind of attack for the range the fight is being fought at.
     *
     * `AIHint.Ranged` past [MeleeAttackExec.RANGE], `AIHint.Melee` inside it - the old
     * `if (distance > .5F)` split, expressed against the reach the swing itself uses so the two
     * cannot drift apart. The other kind is tried as a fallback, which the old code did not do:
     * there it fell through to *walking*, and walking is `UnitBattleSystem`'s job in this game.
     * Without the fallback an archer whose arrow is on cooldown would stand inside an orc's guard
     * doing nothing while holding a sword.
     */
    private fun tryAttack(
        self: NetId,
        granted: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        now: Tick,
        x: Float,
        y: Float,
        threat: NetId,
    ) {
        val world = rules.combat.world
        val dx = world.x(threat) - x
        val dy = world.y(threat) - y
        val closed = dx * dx + dy * dy <= MeleeAttackExec.RANGE * MeleeAttackExec.RANGE
        val preferred = if (closed) rules.tags.hintMelee else rules.tags.hintRanged
        val fallback = if (closed) rules.tags.hintRanged else rules.tags.hintMelee
        if (fire(self, granted, attributes, effects, now, threat, preferred)) return
        fire(self, granted, attributes, effects, now, threat, fallback)
    }

    /**
     * Activates the highest granted slot tagged `AIHint.Damage` and [kind]. @return whether it did.
     *
     * Highest first, so a unit that has a special uses it when it is up and falls back to its
     * basic attack when it is not - which is what every one of the old characters' two-ability
     * loadouts meant by putting the special in `Slot.B`.
     */
    private fun fire(
        self: NetId,
        granted: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        now: Tick,
        threat: NetId,
        kind: GameplayTag,
    ): Boolean {
        var slot = granted.slotCount - 1
        while (slot >= 0) {
            val instance = granted.instanceAt(slot)
            val index = instance.abilityIndex
            if (instance.isGranted &&
                hasTag(index, rules.tags.hintDamage) &&
                hasTag(index, kind) &&
                hasTarget(self, index) &&
                activation.activate(self, granted, attributes, effects, slot, now) ===
                ActivationResult.Activated
            ) {
                // Target acquisition, recorded. `activate` resets the instance, so this has to
                // come after it. Every exec still re-finds its own target at the frame it lands
                // on - that is the old behaviour and the right one - but an agent reading
                // `AbilityInstance.targetId` can now see who a unit went for at the moment it
                // decided, which nothing exposed before.
                granted.instanceAt(slot).targetSingle(threat)
                activations++
                return true
            }
            slot--
        }
        return false
    }

    // --- passive regeneration ---------------------------------------------------------------

    /**
     * Puts `ability/passive_health_regen` on [self], once, the first tick this brain sees it.
     *
     * `example/.../system/GameUnitSystem.onAddEntity` applied this to every unit at spawn and
     * nothing in this game applied it at all: `MobaEffects` declared the effect, `UnitKind`
     * carried a `healthRegen` per character, `CharacterAttributes` declared the attribute, and
     * `MobaAbilityModule`'s own KDoc said outright that nothing joined them up. So the priest's
     * `healthRegen = 2F` was a number in a table that never became a hit point.
     *
     * The reason it was left undone is real, and is solved here rather than dodged: a periodic
     * effect has to be applied *at a known tick*, and `Blueprint.configure` has no tick, so a
     * blueprint applying it would stamp `appliedTick = Tick.ZERO` and
     * `AttributeRecompute.applyPermanent`'s catch-up loop would fire one period for every second
     * the world had already been running, all at once. Applied here it is stamped with the tick
     * the unit was first seen, so the first regen lands one period later, exactly as it should.
     *
     * Only when `healthRegen` is above zero. Four of the six characters regenerate nothing, and
     * an infinite effect on each of them would be a permanent slot in `GameplayEffects` and a
     * per-tick recompute pass for a magnitude of `0f`. That is a deliberate divergence from the
     * old code, which applied it to everybody.
     *
     * It belongs in a spawn-time effect hook rather than in the AI - see the report.
     */
    private fun applyPassiveRegen(
        self: NetId,
        attributes: Attributes,
        effects: GameplayEffects,
        now: Tick,
        applier: EffectApplier,
    ) {
        if (attributes.current(rules.attributes.healthRegen) <= 0f) return
        val regen = rules.effects.passiveHealthRegen
        var slot = 0
        while (slot < effects.count) {
            if (effects.defIndexAt(slot) == regen) return
            slot++
        }
        applier.begin(regen).applyTo(effects, attributes, now, targetId = self, source = self)
        regensApplied++
    }

    // --- shared ---------------------------------------------------------------------------

    /** Whether the ability at [abilityIndex] carries [tag]. */
    private fun hasTag(abilityIndex: Int, tag: GameplayTag): Boolean =
        abilityIndex >= 0 && tag in abilityTable.defAt(abilityIndex).tags

    /** Whether the ability at [abilityIndex] has something to point at. */
    private fun hasTarget(self: NetId, abilityIndex: Int): Boolean {
        val policy = targeting.policyOf(abilityIndex) ?: return false
        val world = rules.combat.world
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
        val world = rules.combat.world
        world.query(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = policy.range,
            relation = policy.relation,
            viewerTeam = world.teamOf(self),
            exclude = if (policy.includesSelf) NetId.NONE else self,
            into = allies,
        )
        var index = 0
        while (index < allies.size) {
            if (rules.isDamaged(allies[index])) return true
            index++
        }
        return false
    }

    public companion object {

        /**
         * Whether a unit on [team] with this much [health] has decided to run.
         *
         * ## Why this is public, and why `UnitBattleSystem` asks it
         *
         * Because running was, until this call site existed, almost entirely cancelled out.
         * [retreat] pushes a routed unit away through `Motion`, and in the *same tick*
         * `UnitBattleSystem` walked it straight back towards the nearest enemy at
         * `UnitKind.moveSpeed`. The two writers netted about `0.15` world units per tick of
         * flight instead of [RETREAT_SPEED]'s `0.9`, so a soldier on nine hit points shuffled
         * backwards while an orc closed on it - which reads as a broken AI rather than as two
         * systems each doing exactly what it was written to do.
         *
         * The question is asked here rather than re-derived in the battle system on purpose. A
         * second copy of `health <= FLEE_HEALTH && !isFearless` is a copy that drifts the first
         * time either half is retuned, and the failure mode of that drift is silent: the unit
         * runs and closes at once again, and nothing throws.
         *
         * The caller passes `Position.hp`, which is `DeathSystem`'s once-a-tick window onto the
         * `health` attribute this reads directly. The two can disagree by one tick at the moment
         * a unit crosses [FLEE_HEALTH]; one tick of walking is a fifteenth of a world unit, and
         * closing that would mean giving the battle system an `Attributes` lookup per unit per
         * tick to buy nothing.
         */
        public fun isRouted(health: Float, team: Int): Boolean =
            health <= FLEE_HEALTH && !AiRoster.isFearless(team)

        /**
         * Health at or below which a unit that is not [AiRoster.isFearless] runs.
         *
         * Ten, the literal in `UnitAISystem.kt:66`, and deliberately **not** scaled by
         * [MobaScale.WORLD]: health is health in both games, and only distances were rescaled.
         */
        public const val FLEE_HEALTH: Float = 10f

        /** `health < maxHealth / 2F` at `UnitAISystem.kt:50`, as a fraction. */
        public const val HURT_FRACTION: Float = 0.5f

        /** `it.position.dst(entity.position) < 10F` at `UnitAISystem.kt:59`, in world units. */
        public const val SIGHT_RADIUS: Float = 10f * MobaScale.WORLD

        /**
         * How fast a routed unit ends up running, in world units per tick.
         *
         * A shade above `UnitKind.Skeleton`'s `moveSpeed` of `0.8f`, which is the fastest thing
         * that can chase it. Below that, fleeing is a slower death than fighting.
         */
        public const val RETREAT_SPEED: Float = 0.9f

        /**
         * The impulse that settles at [RETREAT_SPEED] against `Motion.UNIT_DAMPING`.
         *
         * A damped integrator fed `i` every tick converges on `i / (1 - damping)`, so the impulse
         * is the speed times the loss. Written this way rather than as the number it evaluates to,
         * so that retuning the damping does not silently retune the rout.
         */
        public const val RETREAT_IMPULSE: Float = RETREAT_SPEED * (1f - Motion.UNIT_DAMPING)

        /** How wide a routed pack scatters, in radians, either side of straight away. */
        public const val RETREAT_SPREAD: Float = 0.7f

        /** How many units the heal-worthiness and damaged-ally scans look at. */
        public const val SCAN_CAPACITY: Int = 16

        private const val TAU: Float = 6.2831855f

        /** Below this separation two units count as standing on the same point. */
        private const val EPSILON: Float = 1e-4f
    }
}
