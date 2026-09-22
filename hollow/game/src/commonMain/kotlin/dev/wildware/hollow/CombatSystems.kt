package dev.wildware.hollow

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityContext
import dev.wildware.udea.gas.AbilityExec
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasServices

/**
 * How Hollow's ability executors reach the world they run in (issue #252).
 *
 * An `AbilityExec` is a singleton shared by every entity running the ability and is handed only the
 * caster's own components, so a swing that hurts a fox needs somewhere to find the fox. That is
 * this: the world, its `NetId` index and its random streams, bound once when the world builds
 * [PlayerAbilitySystem] - the first system of the phase the executors run in - and read by the
 * executors of that one [HollowCombat]. It holds nothing that changes between ticks, so there is
 * nothing in it for a rewind to restore.
 */
internal class Arena(private val combat: HollowCombat) {

    private var bound: World? = null
    private var context: GameContext? = null

    /** The world. Throws before [bind], which would be an executor running in no world at all. */
    val world: World get() = checkNotNull(bound) { "the combat arena is used before its world was built" }

    /** The world's context: its `NetId` index and its random streams. */
    val ctx: GameContext get() = checkNotNull(context) { "the combat arena is used before its world was built" }

    /** Every fox, for a swing to look through. Resolved once, at [bind]. */
    lateinit var foxes: Family
        private set

    fun bind(world: World, ctx: GameContext) {
        check(bound == null || bound === world) { "one HollowCombat is bound to two worlds; build one per definition" }
        bound = world
        context = ctx
        foxes = world.family { all(Fox, Transform3D, Attributes, GameplayEffects) }
    }

    /** The damage a hit does: [least] plus a roll of `0 until spread` from the `Combat` stream. */
    fun roll(least: Int, spread: Int): Float = (least + ctx.rng.nextInt(RngStream.Combat, spread)).toFloat()

    /**
     * Applies [amount] of damage to [victim] from [source], through `hollow/damage`, so it lands in
     * health when `AttributeSystem` runs this tick and is clamped at zero there.
     */
    fun hurt(context: AbilityContext, victim: Entity, source: NetId, amount: Float) {
        with(world) {
            context.applier.begin(combat.damage)
                .magnitude(combat.dataDamage, -amount)
                .applyTo(
                    target = victim[GameplayEffects],
                    attributes = victim[Attributes],
                    now = context.tick,
                    targetId = ctx[CoreModule.NET_IDS].netIdOf(victim),
                    source = source,
                )
        }
    }

    override fun toString(): String = "Arena(${if (bound == null) "unbound" else "bound"})"
}

/**
 * A player's swing: every living fox within [CombatRules.ATTACK_RANGE] takes
 * `ATTACK_DAMAGE + roll`, on the tick the swing starts, and the character punches for
 * [CombatRules.ATTACK_TICKS].
 *
 * The hit is on the first tick and not at the end of a wind-up. A wind-up would be the fox's chance
 * to walk out of reach, which is a good rule and a worse one for Hollow's first fight: a fox runs
 * at 4.4 metres a second, and a quarter-second wind-up would miss one walking out of reach as
 * often as it hit one walking in. What is hit is decided by where everybody stood when the key went
 * down, which is what a player saw.
 *
 * Foxes are hit in the order the family holds them - entity id order, the same on every run of the
 * same world - so the `Combat` stream is drawn in the same order on a server and on its replay.
 */
internal class AttackExec(private val arena: Arena) : AbilityExec {

    override fun onActivate(context: AbilityContext) {
        val world = arena.world
        val netIds = arena.ctx[CoreModule.NET_IDS]
        val me = netIds.resolveOrNull(context.self) ?: return
        with(world) {
            val at = me[Transform3D]
            val entities = arena.foxes.entities
            for (index in 0 until entities.size) {
                val fox = entities[index]
                if (fox[Fox].mode == FoxMode.Dead) continue
                val there = fox[Transform3D]
                val dx = there.x - at.x
                val dy = there.y - at.y
                if (dx * dx + dy * dy > REACH_SQUARED) continue
                arena.hurt(context, fox, context.self, arena.roll(CombatRules.ATTACK_DAMAGE, CombatRules.ATTACK_SPREAD))
            }
        }
    }

    override fun onTick(context: AbilityContext) {
        if (context.elapsedTicks >= CombatRules.ATTACK_TICKS.count) context.endAbility()
    }

    override fun toString(): String = "AttackExec"

    private companion object {
        const val REACH_SQUARED = CombatRules.ATTACK_RANGE * CombatRules.ATTACK_RANGE
    }
}

/**
 * A player's dash: [CombatRules.DASH_TICKS] at [CombatRules.DASH_SPEED] along the way the
 * character is facing.
 *
 * The facing is written into the activation's scratch when the dash starts, so a dash keeps its line
 * whatever the stick does during it, and a rewind to the middle of one carries on along the same
 * line: the scratch is on the `AbilityInstance`, which the snapshot carries. The velocity itself is
 * `PlayerMovementSystem`'s to write, because a character's velocity has one writer.
 */
internal class DashExec : AbilityExec {

    /**
     * Nothing to start: [PlayerAbilitySystem] writes the line into the scratch as this returns,
     * because it is the system holding the `Player` whose facing it is.
     */
    override fun onActivate(context: AbilityContext) = Unit

    override fun onTick(context: AbilityContext) {
        if (context.elapsedTicks >= CombatRules.DASH_TICKS.count) context.endAbility()
    }

    override fun toString(): String = "DashExec"
}

/** A player's heal: [CombatRules.HEAL_AMOUNT] back, at once, never past the maximum. */
internal class HealExec(private val combat: HollowCombat) : AbilityExec {

    override fun onActivate(context: AbilityContext) {
        val attributes = context.attributes ?: return
        val effects = context.appliedEffects ?: return
        context.applier.begin(combat.heal)
            .magnitude(combat.dataHeal, CombatRules.HEAL_AMOUNT)
            .applyTo(effects, attributes, context.tick, targetId = context.self, source = context.self)
        context.endAbility()
    }

    override fun toString(): String = "HealExec"
}

/**
 * A fox's bite: [CombatRules.BITE_DAMAGE] plus a roll on the player it is chasing. [FoxBiteSystem]
 * fires it only when that player is within [CombatRules.BITE_REACH], on the same tick, so there is
 * nothing to look for here but the target.
 */
internal class BiteExec(private val arena: Arena) : AbilityExec {

    override fun onActivate(context: AbilityContext) {
        val world = arena.world
        val netIds = arena.ctx[CoreModule.NET_IDS]
        val me = netIds.resolveOrNull(context.self)
        if (me != null) {
            val victim = with(world) { netIds.resolveOrNull(me[Fox].target) }
            if (victim != null) {
                arena.hurt(context, victim, context.self, arena.roll(CombatRules.BITE_DAMAGE, CombatRules.BITE_SPREAD))
            }
        }
        context.endAbility()
    }

    override fun toString(): String = "BiteExec"
}

/**
 * Gives every player and every fox that has none its health, its ability bar and its effect list
 * (issue #252), through [HollowCombat.arm].
 *
 * A system rather than a line in each spawn, because a fighter reaches the world by four routes -
 * `Player.spawn` for a joining client, `Fox.spawn` for a wave, a level file, a test - and only one of
 * them could be handed the combat tables. First in `SimPhase.Ability`, so whatever arrived since the
 * last tick can act on this one.
 *
 * Never on a client: a replicated fighter arrives with its `Attributes` already on it.
 */
internal class ArmSystem(private val combat: HollowCombat) : SimSystem() {

    private val unarmed: Family = world.family {
        any(Player, Fox)
        none(Attributes)
    }

    /** The entities to arm, copied out first: arming one changes the family being read. */
    private val pending = ArrayList<Entity>()

    override fun onTick() {
        if (unarmed.isEmpty) return
        unarmed.forEach { pending += it }
        for (entity in pending) combat.arm(world, entity)
        pending.clear()
    }

    override fun toString(): String = "ArmSystem"
}

/**
 * Fires a player's abilities from the buttons [PlayerControlSystem] read this tick: attack, dash and
 * heal, one slot each (issue #252).
 *
 * The button is **held**, not pressed: a swing fires on every tick the key is down that the ability
 * is off cooldown, so holding the attack key swings once per [CombatRules.ATTACK_COOLDOWN]. That is
 * also what crosses the wire - `MoveInput.buttons` is a state at 30Hz and an edge between two sends
 * would be lost - and it makes a cooldown the one thing standing between a held key and a swing
 * every tick, which is what a test of the cooldown can see.
 *
 * Before `AbilitySystem`, so a swing started this tick has its first `onTick` this tick.
 */
internal class PlayerAbilitySystem(
    private val combat: HollowCombat,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val players: Family = world.family { all(Player, Abilities, Attributes, GameplayEffects) }

    private val gas: GasServices = ctx[GasServices.KEY]

    init {
        combat.arena.bind(world, ctx)
    }

    override fun onTick() {
        players.forEach { entity ->
            val player = entity[Player]
            val abilities = entity[Abilities]
            val attributes = entity[Attributes]
            val effects = entity[GameplayEffects]
            val self = netIds.netIdOf(entity)
            if (player.attack && fire(self, abilities, attributes, effects, CombatRules.ATTACK_SLOT)) {
                player.attackReady = readyAt(abilities, effects, CombatRules.ATTACK_SLOT)
            }
            if (player.dash && fire(self, abilities, attributes, effects, CombatRules.DASH_SLOT)) {
                player.dashReady = readyAt(abilities, effects, CombatRules.DASH_SLOT)
                // The line the dash keeps, written onto the activation so a rewind keeps it too.
                val scratch = abilities.instanceAt(CombatRules.DASH_SLOT).scratchFloats
                scratch[DASH_X] = player.faceX
                scratch[DASH_Y] = player.faceY
            }
            if (player.heal && fire(self, abilities, attributes, effects, CombatRules.HEAL_SLOT)) {
                player.healReady = readyAt(abilities, effects, CombatRules.HEAL_SLOT)
            }
        }
    }

    /** Activates [slot] if it can be, now. True when it was. */
    private fun fire(self: NetId, abilities: Abilities, attributes: Attributes, effects: GameplayEffects, slot: Int): Boolean =
        gas.activation.activate(self, abilities, attributes, effects, slot, tick).isActivated

    /**
     * The tick [slot] comes off the cooldown it has just started, read off the cooldown effect
     * itself: the `Player` copy of it is exactly the effect, never a second reckoning of it.
     */
    private fun readyAt(abilities: Abilities, effects: GameplayEffects, slot: Int): Tick =
        tick + gas.activation.cooldownRemaining(abilities, effects, slot, tick).toLong()

    override fun toString(): String = "PlayerAbilitySystem"

    internal companion object {
        /** Where a dash's line is kept in its activation's scratch. */
        const val DASH_X: Int = 0

        /** @see DASH_X */
        const val DASH_Y: Int = 1
    }
}

/**
 * Bites for every fox that is chasing a living player within [CombatRules.BITE_REACH] (issue #252),
 * as often as the bite's cooldown lets it.
 *
 * The reach is tested here rather than in [BiteExec] so that a fox out of reach does not spend its
 * cooldown on a bite that could not land. `SimPhase.Ability`, before `AbilitySystem`: the positions
 * are the ones the last tick's solve left, the same ones the fox's own chase was decided on.
 */
internal class FoxBiteSystem(
    private val combat: HollowCombat,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val foxes: Family = world.family { all(Fox, Transform3D, Abilities, Attributes, GameplayEffects) }

    private val gas: GasServices = ctx[GasServices.KEY]

    override fun onTick() {
        val now = tick
        foxes.forEach { entity ->
            val fox = entity[Fox]
            if (fox.mode != FoxMode.Chase) return@forEach
            val victim = netIds.resolveOrNull(fox.target) ?: return@forEach
            val attributes = victim.getOrNull(Attributes) ?: return@forEach
            if (combat.isDead(attributes)) return@forEach
            val at = entity[Transform3D]
            val there = victim[Transform3D]
            val dx = there.x - at.x
            val dy = there.y - at.y
            if (dx * dx + dy * dy > REACH_SQUARED) return@forEach
            gas.activation.activate(
                netIds.netIdOf(entity),
                entity[Abilities],
                entity[Attributes],
                entity[GameplayEffects],
                CombatRules.BITE_SLOT,
                now,
            )
        }
    }

    override fun toString(): String = "FoxBiteSystem"

    private companion object {
        const val REACH_SQUARED = CombatRules.BITE_REACH * CombatRules.BITE_REACH
    }
}

/**
 * Marks every fighter whose health has reached zero as dead, and takes away a fox that has lain dead
 * for [CombatRules.CORPSE] (issue #252).
 *
 * Dead is the `hollow/death` effect, applied once, whose tag blocks every ability and cancels one in
 * flight; its applied tick is the time of death, so there is no clock of this system's own for a
 * rewind to miss. A dead player stays where it fell - what comes after, a respawn or the end of the
 * match, is issue #253's.
 *
 * `SimPhase.Gameplay`: after `AttributeSystem` has landed this tick's damage, so a fighter killed on
 * this tick is marked on this tick.
 */
internal class DeathSystem(
    private val combat: HollowCombat,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val fighters: Family = world.family { all(Attributes, GameplayEffects) }

    private val gas: GasServices = ctx[GasServices.KEY]

    /** Dead foxes whose time is up, copied out first: removing one changes the family being read. */
    private val gone = ArrayList<Entity>()

    override fun onTick() {
        val now = tick
        fighters.forEach { entity ->
            val attributes = entity[Attributes]
            if (!combat.isDead(attributes)) return@forEach
            val effects = entity[GameplayEffects]
            val died = combat.diedAt(effects)
            if (died == null) {
                gas.applier.begin(combat.death)
                    .applyTo(effects, attributes, now, targetId = netIds.netIdOf(entity), source = netIds.netIdOf(entity))
            } else if (entity has Fox && now.ticksSince(died) >= CombatRules.CORPSE.count) {
                gone += entity
            }
        }
        for (entity in gone) {
            netIds.free(netIds.netIdOf(entity))
            world -= entity
        }
        gone.clear()
    }

    override fun toString(): String = "DeathSystem"
}

