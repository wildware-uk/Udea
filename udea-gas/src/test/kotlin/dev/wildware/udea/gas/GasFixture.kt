package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * A small but complete game's worth of GAS content, shared by every test in this module.
 *
 * Real content rather than mocks: five attributes with real bounds, effects of every duration
 * shape (instant, periodic, fixed-tick, set-by-caller, tag-only) and two abilities with real costs
 * and cooldowns. A test that asserted against a stub would be asserting the stub.
 *
 * Everything is constructed per fixture instance, so two fixtures in one JVM share nothing — which
 * is what `TwoWorldHandleTest` needs to be able to observe.
 */
internal class GasFixture(
    authority: AbilityAuthority = AbilityAuthority.All,
) {

    val tags: GameplayTagTable = GameplayTagTable.of(
        listOf("Cooldown", "Damage", "Debuff.Stunned", "Ability.Fireball", "Ability.Blink"),
    )

    val cooldownTag: GameplayTag = tags.tagOf("Cooldown")
    val damageTag: GameplayTag = tags.tagOf("Damage")
    val stunnedTag: GameplayTag = tags.tagOf("Debuff.Stunned")
    val fireballTag: GameplayTag = tags.tagOf("Ability.Fireball")

    val attributeTable: AttributeTable = AttributeTableBuilder().apply {
        add(AttributeDecl("game.Character.cooldownReduction", defaultBase = 0f), "game")
        add(AttributeDecl("game.Character.health", defaultBase = 100f), "game")
        add(AttributeDecl("game.Character.mana", defaultBase = 100f), "game")
        add(AttributeDecl("game.Character.maxHealth", defaultBase = 100f), "game")
        add(AttributeDecl("game.Character.moveSpeed", defaultBase = 10f), "game")
    }.build()

    val health: AttributeId = attributeTable.idOf("game.Character.health")
    val mana: AttributeId = attributeTable.idOf("game.Character.mana")
    val maxHealth: AttributeId = attributeTable.idOf("game.Character.maxHealth")
    val moveSpeed: AttributeId = attributeTable.idOf("game.Character.moveSpeed")
    val cooldownReduction: AttributeId = attributeTable.idOf("game.Character.cooldownReduction")

    val effectTable: GameplayEffectTable = GameplayEffectTable.of(
        listOf(
            GameplayEffectDef(
                name = "ability/cooldown",
                duration = GameplayEffectDuration.SetByCaller(cooldownTag),
                tags = tags.setOf(fireballTag),
            ),
            GameplayEffectDef(
                name = "ability/cost_mana",
                target = mana,
                modifierType = ModifierType.Additive,
                magnitude = value(-30f),
                duration = GameplayEffectDuration.Instant,
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = "ability/damage",
                target = health,
                modifierType = ModifierType.Additive,
                magnitude = value(damageTag),
                duration = GameplayEffectDuration.Instant,
                tags = tags.newSet(),
                cueIds = intArrayOf(DAMAGE_CUE),
            ),
            GameplayEffectDef(
                name = "ability/haste",
                target = moveSpeed,
                modifierType = ModifierType.Additive,
                magnitude = value(5f),
                duration = GameplayEffectDuration.Ticks(30),
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = "ability/petrify",
                target = moveSpeed,
                modifierType = ModifierType.Override,
                magnitude = value(99f),
                duration = GameplayEffectDuration.Ticks(30),
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = "ability/regen",
                target = health,
                modifierType = ModifierType.Additive,
                magnitude = value(5f),
                duration = GameplayEffectDuration.Infinite,
                periodTicks = 15,
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = "ability/root",
                target = moveSpeed,
                modifierType = ModifierType.Override,
                magnitude = value(0f),
                duration = GameplayEffectDuration.Ticks(30),
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = "ability/slow",
                target = moveSpeed,
                modifierType = ModifierType.Multiplicative,
                magnitude = value(0.5f),
                duration = GameplayEffectDuration.Ticks(30),
                tags = tags.newSet(),
            ),
            GameplayEffectDef(
                name = "debuff/stun",
                duration = GameplayEffectDuration.Ticks(60),
                tags = tags.setOf(stunnedTag),
            ),
        ),
    )

    val damageEffect: Int = effectTable.indexOf("ability/damage")
    val regenEffect: Int = effectTable.indexOf("ability/regen")
    val hasteEffect: Int = effectTable.indexOf("ability/haste")
    val slowEffect: Int = effectTable.indexOf("ability/slow")
    val rootEffect: Int = effectTable.indexOf("ability/root")
    val petrifyEffect: Int = effectTable.indexOf("ability/petrify")
    val stunEffect: Int = effectTable.indexOf("debuff/stun")
    val cooldownEffect: Int = effectTable.indexOf("ability/cooldown")
    val manaCostEffect: Int = effectTable.indexOf("ability/cost_mana")

    val exec: RecordingExec = RecordingExec()

    val channelled: ChannelledExec = ChannelledExec()

    val execs: AbilityExecRegistry = AbilityExecRegistry.of(listOf(exec, channelled))

    val abilityTable: AbilityTable = AbilityTable.of(
        listOf(
            AbilityDef(
                name = "ability/fireball",
                execId = execs.idOf(exec),
                cooldownTicks = 900,
                cooldownEffectIndex = cooldownEffect,
                cooldownTag = cooldownTag,
                cooldownReductionAttribute = cooldownReduction,
                costs = listOf(AbilityCost(mana, value(30f), manaCostEffect)),
                tags = tags.setOf(fireballTag),
                blockedBy = tags.setOf(stunnedTag),
            ),
            AbilityDef(
                name = "ability/blink",
                execId = execs.idOf(ChannelledExec::class.java.name),
                cooldownTicks = 0,
                tags = tags.setOf(tags.tagOf("Ability.Blink")),
                blockedBy = tags.setOf(stunnedTag),
            ),
        ),
    )

    val fireball: Int = abilityTable.indexOf("ability/fireball")
    val blink: Int = abilityTable.indexOf("ability/blink")

    val handles: HandleAllocator = HandleAllocator()

    val cues: GasCueQueue = GasCueQueue()

    val applier: EffectApplier = EffectApplier(effectTable, handles, cues)

    val activation: AbilityActivation =
        AbilityActivation(abilityTable, effectTable, execs, applier, cues, authority)

    val recompute: AttributeRecompute = AttributeRecompute(effectTable, attributeTable, handles)

    /** One entity's GAS state: the three components, plus the id the rest of the engine knows it by. */
    inner class Unit(val netId: NetId, abilitySlots: Int = 2) {
        val attributes: Attributes = Attributes(attributeTable)
        val effects: GameplayEffects = GameplayEffects()
        val abilities: Abilities = Abilities(abilitySlots)

        fun recompute(now: Tick) = this@GasFixture.recompute.recompute(attributes, effects, now)

        fun tickAbilities(now: Tick) = activation.tick(netId, abilities, attributes, effects, now)

        fun activate(slot: Int, now: Tick): ActivationResult =
            activation.activate(netId, abilities, attributes, effects, slot, now)

        fun canActivate(slot: Int, now: Tick): ActivationResult =
            activation.canActivate(netId, abilities, attributes, effects, slot, now)

        fun end(slot: Int, now: Tick, cancelled: Boolean = false) =
            activation.end(netId, abilities, attributes, effects, slot, now, cancelled)

        fun cooldownRemaining(slot: Int, now: Tick): Int =
            activation.cooldownRemaining(abilities, effects, slot, now)

        /** Applies [effectIndex] to this unit, staging [magnitudes] first. */
        fun apply(
            effectIndex: Int,
            now: Tick,
            vararg magnitudes: Pair<GameplayTag, Float>,
        ): EffectHandle {
            applier.begin(effectIndex)
            magnitudes.forEach { applier.magnitude(it.first, it.second) }
            return applier.applyTo(effects, attributes, now, targetId = netId, source = netId)
        }
    }

    fun unit(index: Int = 1, abilitySlots: Int = 2): Unit =
        Unit(NetId.of(index, 0), abilitySlots)

    companion object {
        /** The cue `ability/damage` emits. */
        const val DAMAGE_CUE: Int = 7
    }
}

/**
 * An exec that records what it was asked to do, and on whose behalf.
 *
 * It records into the *instance's* scratch as well as into its own counters, so
 * `AbilityExecStatelessTest` can prove that two entities running it simultaneously keep
 * independent state even though they share this one object.
 */
internal class RecordingExec : AbilityExec {

    var activations = 0
        private set

    var ticks = 0
        private set

    var ends = 0
        private set

    var cancellations = 0
        private set

    override fun onActivate(context: AbilityContext) {
        activations++
        context.instance.scratchInts[0] = context.instance.instanceId
        context.instance.scratchFloats[0] = context.attributes!!.current(AttributeId(0))
    }

    override fun onTick(context: AbilityContext) {
        ticks++
        context.instance.scratchInts[1]++
    }

    override fun onEnd(context: AbilityContext, cancelled: Boolean) {
        ends++
        if (cancelled) cancellations++
    }
}

/** An exec that stays active for a fixed number of ticks, then ends itself. */
internal class ChannelledExec(private val durationTicks: Int = 5) : AbilityExec {

    var ends = 0
        private set

    var cancellations = 0
        private set

    override fun onEnd(context: AbilityContext, cancelled: Boolean) {
        ends++
        if (cancelled) cancellations++
    }

    override fun onActivate(context: AbilityContext) {
        context.instance.phase = AbilityPhase.AwaitingTarget
        context.instance.scratchInts[0] = durationTicks
    }

    override fun onTick(context: AbilityContext) {
        val remaining = context.instance.scratchInts[0] - 1
        context.instance.scratchInts[0] = remaining
        if (context.instance.phase == AbilityPhase.AwaitingTarget && remaining <= durationTicks - 2) {
            context.instance.phase = AbilityPhase.Active
        }
        if (remaining <= 0) context.instance.phase = AbilityPhase.Inactive
    }
}
