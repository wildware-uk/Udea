package dev.wildware.hollow

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.level.LevelHooks
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.module.before
import dev.wildware.udea.core.module.runtimeName
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.serviceKey
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityDef
import dev.wildware.udea.gas.AbilityExecRegistry
import dev.wildware.udea.gas.AbilitySystem
import dev.wildware.udea.gas.AbilityTable
import dev.wildware.udea.gas.AttributeDecl
import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.AttributeModule
import dev.wildware.udea.gas.AttributeTable
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffectDef
import dev.wildware.udea.gas.GameplayEffectDuration
import dev.wildware.udea.gas.GameplayEffectTable
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GameplayTag
import dev.wildware.udea.gas.GameplayTagTable
import dev.wildware.udea.gas.GasModule
import dev.wildware.udea.gas.ModifierType
import dev.wildware.udea.gas.value

/**
 * The numbers a fight in the clearing is made of (issue #252). Distances in metres on the ground
 * plane, speeds in metres a second - the solver's unit, as `HollowMovement` says - damage and health
 * in hit points, and every duration and cooldown a count of ticks.
 */
internal object CombatRules {

    /** A player's health when it is spawned, and the ceiling a heal fills it to. */
    const val PLAYER_HEALTH: Float = 100f

    /** A fox's health when it arrives. */
    const val FOX_HEALTH: Float = 100f

    /**
     * How far a player's swing reaches: every living fox this close, or closer, is hit.
     *
     * All the way round rather than in an arc in front. An arc needs the player's facing as a
     * vector against the fox's bearing, which `Player.faceX/faceY` could give - but a swing that
     * misses a fox a metre behind the player reads as a bug to a player who can see it, and a
     * circle is the rule a test can state from both sides of one number.
     */
    const val ATTACK_RANGE: Float = 1.8f

    /** The least a swing does. The rest is a roll: see [ATTACK_SPREAD]. */
    const val ATTACK_DAMAGE: Int = 25

    /**
     * How many hit points above [ATTACK_DAMAGE] a swing may add, drawn from the `Combat` stream: a
     * swing does `ATTACK_DAMAGE + nextInt(Combat, ATTACK_SPREAD)`, so 25 to 34. Four swings kill a
     * fox, or three and a lucky one - which is the whole of what the roll is for.
     */
    const val ATTACK_SPREAD: Int = 10

    /** How long a swing lasts: the character stands and punches for this long. */
    val ATTACK_TICKS: Ticks = Ticks(24L)

    /**
     * Ticks between one swing and the next. Longer than [ATTACK_TICKS], so holding the attack key
     * swings on the cooldown and not on the end of the last swing.
     */
    val ATTACK_COOLDOWN: Ticks = Ticks(36L)

    /** How fast a dash carries a player, in metres a second. Over twice a run. */
    const val DASH_SPEED: Float = 12f

    /** How long a dash lasts: `DASH_SPEED * DASH_TICKS / 60`, 2.4 metres on open ground. */
    val DASH_TICKS: Ticks = Ticks(12L)

    /** Ticks between one dash and the next: three seconds. */
    val DASH_COOLDOWN: Ticks = Ticks(180L)

    /** How much one heal restores, never past [PLAYER_HEALTH]. */
    const val HEAL_AMOUNT: Float = 35f

    /** Ticks between one heal and the next: ten seconds. */
    val HEAL_COOLDOWN: Ticks = Ticks(600L)

    /**
     * How far a fox's bite reaches: a little more than the distance a chasing fox stands off at
     * ([FoxBrain.BITE_RANGE]), so a fox standing at a player's heels can bite it.
     */
    const val BITE_REACH: Float = 1.4f

    /** The least a bite does. Plus `nextInt(Combat, BITE_SPREAD)`, so 5 to 8. */
    const val BITE_DAMAGE: Int = 5

    /** @see BITE_DAMAGE */
    const val BITE_SPREAD: Int = 4

    /**
     * Ticks between one bite and the next: a second and a half. Two foxes on a player take its
     * health in about twenty seconds, which is long enough to fight back and short enough to lose.
     */
    val BITE_COOLDOWN: Ticks = Ticks(90L)

    /** How long a dead fox lies in the clearing before it is gone: a second and a half. */
    val CORPSE: Ticks = Ticks(90L)

    /** A player's ability slots, in the order the HUD shows them. */
    const val ATTACK_SLOT: Int = 0

    /** @see ATTACK_SLOT */
    const val DASH_SLOT: Int = 1

    /** @see ATTACK_SLOT */
    const val HEAL_SLOT: Int = 2

    /** How many slots a player has. */
    const val PLAYER_SLOTS: Int = 3

    /** A fox's one slot. */
    const val BITE_SLOT: Int = 0

    /** How many slots a fox has. */
    const val FOX_SLOTS: Int = 1
}

/**
 * Hollow's combat content: the attributes a fighter has, the effects that change them, the four
 * abilities and what runs each, and the `udea-gas` module over all of it (issue #252).
 *
 * ## Health is an attribute, and the only health there is
 *
 * A player's and a fox's hit points are both `hollow.health` in an [Attributes], written by the
 * `hollow/damage` and `hollow/heal` effects and replicated by `udea-gas`'s own codec. `Fox` had a
 * `health` field of its own in issue #251, which nothing but a test wrote; it is gone, so the fox's
 * mind, the HUD and the wire read one number rather than two that could disagree.
 *
 * ## Built per world
 *
 * One of these per `HollowGame.definition`, because the executors reach the world through an
 * [arena] that is bound when the world is built, and two worlds in one process - a server and its
 * clients in a test - must not share one. The tables themselves are immutable and would be safe to
 * share; they are built here with the executors they index so that an ability can never name an
 * executor in another registry.
 */
public class HollowCombat internal constructor() {

    /** The tags: the set-by-caller keys and the one tag that is a state. */
    internal val tags: GameplayTagTable = GameplayTagTable.of(
        listOf(DATA_DAMAGE, DATA_HEAL, DATA_COOLDOWN, DEAD),
    )

    /** The magnitude of a `hollow/damage` application. Negative. */
    internal val dataDamage: GameplayTag = tags.tagOf(DATA_DAMAGE)

    /** The magnitude of a `hollow/heal` application. */
    internal val dataHeal: GameplayTag = tags.tagOf(DATA_HEAL)

    /** The duration of a `hollow/cooldown` application, in ticks. */
    internal val dataCooldown: GameplayTag = tags.tagOf(DATA_COOLDOWN)

    /** Carried by a fighter whose health has reached zero. Every ability is blocked by it. */
    internal val dead: GameplayTag = tags.tagOf(DEAD)

    /** The attribute table: health, and the ceiling it is clamped to. */
    internal val attributes: AttributeTable = attributeTable()

    /**
     * Hit points. Zero is dead.
     *
     * Public, unlike the table it indexes, because a launcher reads a fighter's health off its
     * `Attributes` - `HollowFightShot` prints every fighter's at every frame - and an attribute
     * means nothing without its id.
     */
    public val health: AttributeId = attributes.idOf(HEALTH)

    /** What [health] is clamped to. */
    public val maxHealth: AttributeId = attributes.idOf(MAX_HEALTH)

    /** Every effect. */
    internal val effects: GameplayEffectTable = GameplayEffectTable.of(
        listOf(
            GameplayEffectDef(
                name = DAMAGE,
                target = health,
                modifierType = ModifierType.Additive,
                magnitude = value(dataDamage),
                duration = GameplayEffectDuration.Instant,
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = HEAL,
                target = health,
                modifierType = ModifierType.Additive,
                magnitude = value(dataHeal),
                duration = GameplayEffectDuration.Instant,
                tags = tags.newSet(),
            ),
            // The cooldown is an effect whose duration is the cooldown, as `moba`'s is: that is what
            // puts it in the effect list a snapshot carries and a client is sent, so a HUD on either
            // reads the same ticks left.
            GameplayEffectDef(
                name = COOLDOWN,
                duration = GameplayEffectDuration.SetByCaller(dataCooldown),
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = DEATH,
                duration = GameplayEffectDuration.Infinite,
                tags = tags.setOf(dead),
            ),
        ),
    )

    /** The `hollow/damage` effect's index. */
    internal val damage: Int = effects.indexOf(DAMAGE)

    /** The `hollow/heal` effect's index. */
    internal val heal: Int = effects.indexOf(HEAL)

    /** The `hollow/death` effect's index: what a fighter at zero health carries from then on. */
    internal val death: Int = effects.indexOf(DEATH)

    /** How an executor reaches the world it runs in. Bound by [HollowCombatModule]'s first system. */
    internal val arena: Arena = Arena(this)

    /** The executors, by sorted class name. */
    internal val execs: AbilityExecRegistry = AbilityExecRegistry.of(
        listOf(AttackExec(arena), DashExec(), HealExec(this), BiteExec(arena)),
    )

    /** The four abilities. */
    internal val abilities: AbilityTable = abilityTable()

    /** The player's swing's index in [abilities]. */
    internal val attack: Int = abilities.indexOf(ATTACK)

    /** The player's dash. */
    internal val dash: Int = abilities.indexOf(DASH)

    /** The player's heal. */
    internal val healSelf: Int = abilities.indexOf(HEAL_ABILITY)

    /** The fox's bite. */
    internal val bite: Int = abilities.indexOf(BITE)

    /** The engine half, over these tables. */
    internal val gas: GasModule = GasModule(
        attributes = attributes,
        effects = effects,
        abilities = abilities,
        execs = execs,
    )

    /** Whether [attributes] belong to a fighter that has been killed. */
    internal fun isDead(attributes: Attributes): Boolean = attributes.base(health) <= 0f

    /**
     * Gives [entity] what a fighter has - health, an ability bar and an effect list - when it has
     * none, and does nothing when it has. A player gets the swing, the dash and the heal; a fox gets
     * the bite.
     *
     * The one place those three are made, called by [ArmSystem] for anything spawned without them
     * and by a test that wants to set a fox's health before its first tick.
     */
    internal fun arm(world: World, entity: Entity) {
        with(world) {
            if (entity has Attributes) return
            val isFox = entity has Fox
            entity.configure { arm(it, isFox) }
        }
    }

    private fun EntityCreateContext.arm(entity: Entity, isFox: Boolean) {
        val attributes = Attributes(this@HollowCombat.attributes)
        val full = if (isFox) CombatRules.FOX_HEALTH else CombatRules.PLAYER_HEALTH
        attributes.setBase(maxHealth, full)
        attributes.setBase(health, full)
        attributes.base.copyInto(attributes.current)
        val bar = Abilities(if (isFox) CombatRules.FOX_SLOTS else CombatRules.PLAYER_SLOTS)
        if (isFox) {
            bar.grant(CombatRules.BITE_SLOT, bite)
        } else {
            bar.grant(CombatRules.ATTACK_SLOT, attack)
            bar.grant(CombatRules.DASH_SLOT, dash)
            bar.grant(CombatRules.HEAL_SLOT, healSelf)
        }
        entity += attributes
        entity += bar
        entity += GameplayEffects()
    }

    /**
     * The tick [effects]' owner died on, or null while it is alive: when its `hollow/death` was
     * applied. Read off the effect rather than stored beside it, so a rewind that restores the
     * effect list restores the time of death with it.
     */
    internal fun diedAt(effects: GameplayEffects): Tick? {
        for (slot in 0 until effects.count) {
            if (effects.defIndexAt(slot) == death) return effects.appliedTickAt(slot)
        }
        return null
    }

    private fun abilityTable(): AbilityTable {
        // Dead is the one state that stops a fighter acting: a body on the ground swings at
        // nothing, and `AbilityActivation.tick` cancels a swing already in flight when it lands.
        val blocked = tags.setOf(dead)
        fun ability(name: String, exec: String, cooldown: Ticks) = AbilityDef(
            name = name,
            execId = execs.idOf(exec),
            cooldownTicks = cooldown.count.toInt(),
            cooldownEffectIndex = effects.indexOf(COOLDOWN),
            cooldownTag = dataCooldown,
            tags = tags.newSet(),
            blockedBy = blocked,
        )
        return AbilityTable.of(
            listOf(
                ability(ATTACK, AttackExec::class.runtimeName, CombatRules.ATTACK_COOLDOWN),
                ability(DASH, DashExec::class.runtimeName, CombatRules.DASH_COOLDOWN),
                ability(HEAL_ABILITY, HealExec::class.runtimeName, CombatRules.HEAL_COOLDOWN),
                ability(BITE, BiteExec::class.runtimeName, CombatRules.BITE_COOLDOWN),
            ),
        )
    }

    override fun toString(): String = "HollowCombat(${abilities.size} abilities, ${effects.size} effects)"

    public companion object {

        /**
         * Where a world's [HollowCombat] is published, for the systems in `HollowModule` that read a
         * fighter's health - a fox deciding whether to flee, a player whether it may move.
         */
        public val KEY: ServiceKey<HollowCombat> = serviceKey("hollow.combat")

        internal const val HEALTH: String = "hollow.health"
        internal const val MAX_HEALTH: String = "hollow.maxHealth"

        internal const val DATA_DAMAGE: String = "hollow.data.damage"
        internal const val DATA_HEAL: String = "hollow.data.heal"
        internal const val DATA_COOLDOWN: String = "hollow.data.cooldown"
        internal const val DEAD: String = "hollow.state.dead"

        internal const val DAMAGE: String = "hollow/damage"
        internal const val HEAL: String = "hollow/heal"
        internal const val COOLDOWN: String = "hollow/cooldown"
        internal const val DEATH: String = "hollow/death"

        internal const val ATTACK: String = "hollow/attack"
        internal const val DASH: String = "hollow/dash"
        internal const val HEAL_ABILITY: String = "hollow/heal_self"
        internal const val BITE: String = "hollow/bite"

        /**
         * The table, with health capped by its maximum.
         *
         * Built twice, as `moba`'s `CharacterAttributes` is and for its reason: the cap names
         * `maxHealth` by [AttributeId], and ids are handed out from sorted names, so they do not
         * exist until a table does. The first build learns them; the second is the real one, and
         * the check is what would catch a third attribute renumbering the two.
         */
        internal fun attributeTable(): AttributeTable {
            val names = listOf(HEALTH, MAX_HEALTH)
            val probe = AttributeTable.of(listOf(Declared(names.map { AttributeDecl(it) })))
            val table = AttributeTable.of(
                listOf(
                    Declared(
                        listOf(
                            AttributeDecl(
                                HEALTH,
                                defaultBase = CombatRules.PLAYER_HEALTH,
                                min = value(0f),
                                max = value(probe.idOf(MAX_HEALTH)),
                            ),
                            AttributeDecl(MAX_HEALTH, defaultBase = CombatRules.PLAYER_HEALTH, min = value(0f)),
                        ),
                    ),
                ),
            )
            for (name in names) check(table.idOf(name) == probe.idOf(name)) { "attribute ids moved between the probe and the table: $name" }
            return table
        }
    }

    /** Hollow's contribution to the attribute table. */
    private class Declared(private val decls: List<AttributeDecl>) : AttributeModule {
        override val moduleName: String get() = HollowGame.NAME
        override fun attributes(): List<AttributeDecl> = decls
    }
}

/**
 * Combat as a module: the `udea-gas` half, forwarded, and Hollow's four systems around it
 * (issue #252).
 *
 * | Phase | System | Answers |
 * |---|---|---|
 * | `Ability` | [ArmSystem] | has everything that fights got health and an ability bar |
 * | `Ability` | [PlayerAbilitySystem] | which of a player's buttons fire an ability this tick |
 * | `Ability` | [FoxBiteSystem] | which foxes are at a player's heels and bite |
 * | `Ability` | `AbilitySystem` | the swings and dashes in flight (`udea-gas`) |
 * | `Attribute` | `AttributeSystem` | damage and heals land in health (`udea-gas`) |
 * | `Gameplay` | [DeathSystem] | who has just died, and which dead foxes are gone |
 *
 * The movement a dash makes is `PlayerMovementSystem`'s, and the punch and the fall are the pose
 * systems': a dash is a velocity and a death is a pose, and both already have one writer.
 *
 * ## What a client runs
 *
 * Nothing but the context and the level hooks. Its world is a replicated view, as `HollowModule`'s
 * KDoc says: a client that ran `AttributeSystem` would fire a replicated heal-over-time a second time
 * into its own copy of the health, and one that ran `AbilitySystem` would swing a replicated swing.
 */
internal class HollowCombatModule(
    /** The content this module runs. */
    val combat: HollowCombat,
    /** Whether this world decides anything. See `HollowModule.authoritative`. */
    private val authoritative: Boolean,
) : UdeaModule {

    override val name: String get() = "hollow-combat"

    override fun context(builder: GameContextBuilder) {
        combat.gas.context(builder)
        builder.service(HollowCombat.KEY, combat)
    }

    // Forwarded, as `moba`'s combat module does: `gas` is not in the game's module list, so a hook
    // it does not receive from here it does not receive at all.
    override fun level(hooks: LevelHooks) {
        combat.gas.level(hooks)
    }

    override fun simulation(registry: SimRegistry) {
        if (!authoritative) return
        combat.gas.simulation(registry)
        registry.add(SimPhase.Ability, { ArmSystem(combat) }) {
            // Before everything that reads a fighter's health or ability bar, so whatever arrived
            // since the last tick - a wave's foxes, a joining player - can fight on this one.
            before<PlayerAbilitySystem>()
            before<FoxBiteSystem>()
            before<AbilitySystem>()
        }
        registry.add(SimPhase.Ability, { ctx ->
            PlayerAbilitySystem(combat, ctx[CoreModule.NET_IDS]).also { combat.arena.bind(it.world, ctx) }
        }) {
            // Before the system that advances activations, so a swing started this tick lands this
            // tick and a dash started this tick moves the character this tick.
            before<AbilitySystem>()
        }
        registry.add(SimPhase.Ability, { ctx -> FoxBiteSystem(combat, ctx[CoreModule.NET_IDS]) }) {
            before<AbilitySystem>()
        }
        registry.add(SimPhase.Gameplay, { ctx -> DeathSystem(combat, ctx[CoreModule.NET_IDS]) })
    }

    override fun toString(): String = "HollowCombatModule(${if (authoritative) "authoritative" else "replicated view"})"
}
