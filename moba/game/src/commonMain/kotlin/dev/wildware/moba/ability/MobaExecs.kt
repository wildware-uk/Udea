package dev.wildware.moba.ability

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.AbilityContext
import dev.wildware.udea.gas.AbilityExec
import dev.wildware.udea.gas.AbilityInstance

/**
 * The basic attack every unit has: a windup, one hit, a stun and a shove.
 *
 * Ported from `example/.../ability/UnitMeleeAttack.kt`. What changed, and why:
 *
 * - **The windup is ticks, not an animation callback.** The old exec called
 *   `setAnimation(...)?.apply { onNotify("attack_hit") { ... } }` and put the whole of the attack
 *   inside a closure held by an `EventListener` on a `@Transient` field. A snapshot taken between
 *   the swing and the hit restored into a world where the closure was gone: the unit stood there
 *   mid-swing for ever, its ability slot permanently `AlreadyActive`. Here the only state is
 *   `activatedTick`, which is a number on a serializable instance, so the hit lands after a
 *   rewind exactly as it would have.
 * - **The target is found at the hit, not at the swing.** That is the old behaviour
 *   (`spec.updateTarget(findTarget())` inside the notify) and it is the right one: a unit that
 *   walked out of reach during the windup is missed rather than hit at range.
 * - **`gameScreen.isServer` is gone.** Whether this simulation may act is
 *   [dev.wildware.udea.gas.AbilityAuthority]'s question, asked once at activation, per entity -
 *   not a global read per swing that made every client's melee attack do nothing.
 */
public class MeleeAttackExec(private val rules: CombatRules) : AbilityExec {

    override fun onActivate(context: AbilityContext) {
        context.cues.emit(MobaCues.MELEE_SWOOSH, context.tick, source = context.self)
    }

    override fun onTick(context: AbilityContext) {
        val elapsed = context.elapsedTicks
        if (elapsed == HIT_TICK) strike(context)
        if (elapsed >= DURATION_TICKS) context.endAbility()
    }

    private fun strike(context: AbilityContext) {
        val world = rules.combat.world
        val self = context.self
        val target = world.nearest(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = RANGE,
            relation = TeamRelation.Enemy,
            viewerTeam = world.teamOf(self),
            exclude = self,
        )
        if (target.isNone) return
        context.instance.targetSingle(target)

        val strength = context.attributes?.current(rules.attributes.strength) ?: return
        rules.damage(context, target, strength)
        rules.stun(context, target, STUN_TICKS)
        rules.knockback(context, target, world.x(self), world.y(self), KNOCKBACK)
        context.cues.emit(
            cueId = MobaCues.MELEE_HIT,
            tick = context.tick,
            source = self,
            target = target,
            payload0 = strength,
        )
    }

    public companion object {

        /** `soldier_attack` frame 4 of 6, at six ticks a frame. */
        public const val HIT_TICK: Long = 24L

        /** The whole swing: six frames. */
        public const val DURATION_TICKS: Long = 36L

        /** The old exec's own reach check - `if (diff.len() > 0.8F) missed`. */
        public const val RANGE: Float = 0.8f * MobaScale.WORLD

        /** `Data.Duration to 0.5F`, at 60Hz. */
        public const val STUN_TICKS: Int = 30

        /** `Data.Knockback to .1F`, plus the `.3F` impulse the exec applied directly. */
        public const val KNOCKBACK: Float = 0.04f * MobaScale.WORLD
    }
}

/**
 * The elite orc's spin: one lunge, then everything within reach takes 150% damage at once.
 *
 * Ported from `example/.../ability/OrcSpinAttack.kt`, which is the ability that most needed the
 * rewrite. The old one:
 *
 * - flipped its own Box2D body to `KinematicBody` and every fixture to a sensor on activation and
 *   back in `finish` - so a cancel that never reached `finish` left the orc permanently
 *   non-colliding, and a snapshot restored mid-spin restored a unit that walked through walls;
 * - read `Mouse.mouseWorldPos` **inside the simulation**, so a dedicated server aimed the spin at
 *   whatever the last mouse position on that process was, which is (0, 0);
 * - built a `GameplayEffectSpec` per target per hit - three objects and a map each.
 *
 * Here the lunge is an impulse toward the enemy the ability picked at activation, the targets are
 * written onto the instance's multi-target list so a snapshot knows who was caught, and the three
 * applications go through the shared applier with no allocation.
 */
public class OrcSpinExec(private val rules: CombatRules) : AbilityExec {

    private val caught = NetIdBuffer(AbilityInstance.MAX_MULTI_TARGETS)

    override fun onActivate(context: AbilityContext) {
        val world = rules.combat.world
        val self = context.self
        context.cues.emit(MobaCues.SPIN, context.tick, source = self)

        // The lunge. The old code aimed it with the mouse and then applied the impulse *away*
        // from the target (`.scl(-1F)` over `source - target`, which is the same as toward it
        // twice negated); toward the nearest enemy is what it visibly did and what an AI-driven
        // orc means by it.
        val lunge = world.nearest(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = LUNGE_SEARCH_RADIUS,
            relation = TeamRelation.Enemy,
            viewerTeam = world.teamOf(self),
            exclude = self,
        )
        if (lunge.isNone) return
        context.instance.targetLocation(world.x(lunge), world.y(lunge))
        val dx = world.x(lunge) - world.x(self)
        val dy = world.y(lunge) - world.y(self)
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length > 0f) world.impulse(self, dx / length * LUNGE, dy / length * LUNGE)
    }

    override fun onTick(context: AbilityContext) {
        val elapsed = context.elapsedTicks
        if (elapsed == HIT_TICK) hit(context)
        if (elapsed >= DURATION_TICKS) context.endAbility()
    }

    private fun hit(context: AbilityContext) {
        val world = rules.combat.world
        val self = context.self
        val strength = context.attributes?.current(rules.attributes.strength) ?: return
        world.query(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = RADIUS,
            relation = TeamRelation.Enemy,
            viewerTeam = world.teamOf(self),
            exclude = self,
            into = caught,
        )
        var index = 0
        while (index < caught.size) {
            val target: NetId = caught[index]
            context.instance.addMultiTarget(target)
            rules.damage(context, target, strength * DAMAGE_SCALE)
            rules.stun(context, target, STUN_TICKS)
            rules.knockback(context, target, world.x(self), world.y(self), KNOCKBACK)
            index++
        }
    }

    public companion object {

        /** `orc_elite_spin_attack` frame 8 of 11, at six ticks a frame. */
        public const val HIT_TICK: Long = 48L

        /** Eleven frames. */
        public const val DURATION_TICKS: Long = 66L

        /** `getUnitsWithin(source, 1.0F)`. */
        public const val RADIUS: Float = 1.0f * MobaScale.WORLD

        /** How far the orc will look for something to lunge at. */
        public const val LUNGE_SEARCH_RADIUS: Float = 4.0f * MobaScale.WORLD

        /** `-strength * 1.5F`. */
        public const val DAMAGE_SCALE: Float = 1.5f

        /** `Data.Duration to 1.0F`. */
        public const val STUN_TICKS: Int = 60

        /** `Data.Knockback to .3F`. */
        public const val KNOCKBACK: Float = 0.12f * MobaScale.WORLD

        /** How hard the orc throws itself at the target on activation. */
        public const val LUNGE: Float = 0.08f * MobaScale.WORLD
    }
}

/**
 * The priest's heal: a heal-over-time on every damaged ally in reach, including itself.
 *
 * Ported from `example/.../ability/PriestHeal.kt`. Two behaviours are deliberately kept and one
 * is deliberately dropped:
 *
 * - **Kept:** the ally search radius (3 world units) and the heal shape - `Data.Heal to 5F` every
 *   250ms for `Data.Duration to 5F` seconds, which is 5 health fifteen ticks apart over 300 ticks.
 * - **Kept:** healing nobody ends the ability early, so a priest does not stand there mid-cast.
 * - **Dropped:** the old exec called `commitAbility()` *only* when it found somebody, so a heal
 *   that found nobody cost neither mana nor cooldown. `udea-gas` pays costs at activation, before
 *   an exec runs, and there is no refund API. So a wasted heal costs mana here and did not before.
 *   That is a real behaviour change and the alternative - letting the exec decide whether the
 *   activation counts - is what made the old cooldown unrewindable in the first place.
 */
public class PriestHealExec(private val rules: CombatRules) : AbilityExec {

    private val allies = NetIdBuffer(AbilityInstance.MAX_MULTI_TARGETS)

    override fun onTick(context: AbilityContext) {
        val elapsed = context.elapsedTicks
        if (elapsed == HEAL_TICK) heal(context)
        if (elapsed >= DURATION_TICKS) context.endAbility()
    }

    override fun onActivate(context: AbilityContext) {
        // Nothing to do at the start of the cast: the heal lands on the animation's `heal` notify,
        // which is [HEAL_TICK] ticks in. Declared rather than left to the default so a reader does
        // not go looking for the half that fires here.
    }

    private fun heal(context: AbilityContext) {
        val world = rules.combat.world
        val self = context.self
        world.query(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = RADIUS,
            relation = TeamRelation.Friendly,
            viewerTeam = world.teamOf(self),
            exclude = NetId.NONE,
            into = allies,
        )
        var healed = 0
        var index = 0
        while (index < allies.size) {
            val ally = allies[index]
            // Only the damaged: a heal-over-time on a unit at full health is a wasted effect
            // slot, and the old code applied one to every ally in range including itself.
            if (rules.isDamaged(ally)) {
                context.instance.addMultiTarget(ally)
                rules.healOverTime(context, ally, HEAL_PER_PERIOD, HEAL_DURATION_TICKS)
                healed++
            }
            index++
        }
        if (healed == 0) context.endAbility()
    }

    public companion object {

        /** `priest_heal` frame 4 of 6, at six ticks a frame. */
        public const val HEAL_TICK: Long = 24L

        /** Six frames. */
        public const val DURATION_TICKS: Long = 36L

        /** `getNearbyFriendlyUnits(spec.entity, 3F)`. */
        public const val RADIUS: Float = 3.0f * MobaScale.WORLD

        /** `Data.Heal to 5F`, per 250ms period. */
        public const val HEAL_PER_PERIOD: Float = 5.0f

        /** `Data.Duration to 5F` seconds, at 60Hz. */
        public const val HEAL_DURATION_TICKS: Int = 300
    }
}

/**
 * The soldier's ranged attack: an arrow that leaves the bow, travels, and hits what it reaches.
 *
 * Ported from `example/.../ability/SoldierFireArrow.kt`, which aimed with `Mouse.mouseWorldPos`
 * from inside the simulation and spawned the arrow with `Blueprint.newInstance(world)` mid-tick -
 * creating an entity outside the barrier, so a snapshot taken on that tick had an arrow in the
 * world that the tick's own recording did not. Here the arrow is a `SimBarrier` spawn: it exists
 * from the start of the next tick, for everybody, including the snapshot.
 *
 * The aim is the nearest enemy rather than a cursor, because this ability is fired by units as
 * well as by a player. Aiming it at a cursor is one line ([dev.wildware.udea.gas.AbilityContext]
 * carries the instance's target) once there is an input path that sets one.
 */
public class FireArrowExec(private val rules: CombatRules) : AbilityExec {

    override fun onActivate(context: AbilityContext) {
        // The bow is drawn here; the arrow leaves at [FIRE_TICK].
    }

    override fun onTick(context: AbilityContext) {
        val elapsed = context.elapsedTicks
        if (elapsed == FIRE_TICK) fire(context)
        if (elapsed >= DURATION_TICKS) context.endAbility()
    }

    private fun fire(context: AbilityContext) {
        val world = rules.combat.world
        val self = context.self
        val team = world.teamOf(self)
        val target = world.nearest(
            centreX = world.x(self),
            centreY = world.y(self),
            radius = RANGE,
            relation = TeamRelation.Enemy,
            viewerTeam = team,
            exclude = self,
        )
        if (target.isNone) {
            context.endAbility()
            return
        }
        context.instance.targetSingle(target)
        val dx = world.x(target) - world.x(self)
        val dy = world.y(target) - world.y(self)
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length < 1e-4f) {
            context.endAbility()
            return
        }
        val strength = context.attributes?.current(rules.attributes.strength) ?: 0f
        world.fireArrow(
            owner = self,
            team = team,
            x = world.x(self),
            y = world.y(self),
            vx = dx / length * SPEED,
            vy = dy / length * SPEED,
            damage = strength * DAMAGE_SCALE,
        )
        context.cues.emit(
            cueId = MobaCues.ARROW_FIRED,
            tick = context.tick,
            source = self,
            target = target,
            payload0 = dx / length,
            payload1 = dy / length,
        )
    }

    public companion object {

        /** `soldier_fire_arrow` frame 8 of 9, at six ticks a frame. */
        public const val FIRE_TICK: Long = 48L

        /** Nine frames. */
        public const val DURATION_TICKS: Long = 54L

        /** `range = 2.0F` in the asset, doubled: an archer that outranges nothing never fires. */
        public const val RANGE: Float = 4.0f * MobaScale.WORLD

        /**
         * World units per tick.
         *
         * The old code set a Box2D linear velocity of `5.0F` units per **second**; at 60Hz that is
         * this, and the arrow now covers the same ground per second with no solver involved.
         */
        public const val SPEED: Float = 5.0f / 60f * MobaScale.WORLD

        /** The arrow's `Data.Damage to -10F` against the soldier's default strength of 10. */
        public const val DAMAGE_SCALE: Float = 1.0f
    }
}
