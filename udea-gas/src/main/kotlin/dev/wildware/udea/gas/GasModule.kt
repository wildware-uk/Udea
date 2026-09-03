package dev.wildware.udea.gas

import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.serviceKey

/**
 * Everything a host needs to reach into a world's gameplay ability system.
 *
 * One value under one [ServiceKey] rather than eight keys, because these eight are one subsystem
 * with one lifetime: a caller that has the applier and not the handle allocator can apply an
 * effect it cannot release. Registered by [GasModule], read by the agent surface, the HUD and the
 * networking layer.
 */
public class GasServices internal constructor(
    /** Every attribute in the game, with its dense ids. */
    public val attributes: AttributeTable,
    /** Every effect definition. */
    public val effects: GameplayEffectTable,
    /** Every ability definition. */
    public val abilities: AbilityTable,
    /** The per-world handle source. Its whole state is one `Int` in the snapshot. */
    public val handles: HandleAllocator,
    /** Outbound cues. Presentation drains it; simulation never reads it back. */
    public val cues: GasCueQueue,
    /** Applies effects and emits their cues. */
    public val applier: EffectApplier,
    /** Gates and runs activations. */
    public val activation: AbilityActivation,
    /** The per-tick recompute. */
    public val recompute: AttributeRecompute,
) {
    override fun toString(): String =
        "GasServices(${attributes.count} attributes, ${effects.size} effects, ${abilities.size} abilities)"

    public companion object {
        /**
         * The key [GasServices] is registered under.
         *
         * A [ServiceKey] rather than a field on `GameContext`: the context holds a small fixed set
         * of engine services and its documented extension point is exactly this. GAS is a module,
         * not a kernel concern.
         */
        public val KEY: ServiceKey<GasServices> = serviceKey("GasServices")
    }
}

/**
 * The gameplay ability system as a registered module.
 *
 * ## Why the tables are constructor parameters
 *
 * A game's attributes, effects and abilities are *its* content. The engine cannot invent them, and
 * a module that discovered them at runtime would reproduce exactly what `@UdeaSystem(runIn = [...])`
 * and `kClass.createInstance()` did in the old engine: a registration that fails at start-up
 * instead of at compile time. So they arrive as values the caller built, and everything derived
 * from them — the allocator, the cue queue, the applier — is instance state on this module, not a
 * global. Two `GasModule`s in one JVM share nothing.
 *
 * ## Phases, not a system list
 *
 * [AbilitySystem] runs in [SimPhase.Ability] and [AttributeSystem] in [SimPhase.Attribute], so a
 * game module's own systems land either side of them by declaring a phase rather than by
 * appending to a hardcoded list. The two never need a `before`/`after` constraint between them:
 * the phase ordinals already say which runs first, absolutely.
 */
public class GasModule(
    /** The game's attribute table, merged from every [AttributeModule]. */
    public val attributes: AttributeTable,
    /** The game's effect definitions. */
    public val effects: GameplayEffectTable,
    /** The game's ability definitions. */
    public val abilities: AbilityTable,
    /** The game's ability executors. */
    public val execs: AbilityExecRegistry,
    /** Which entities this simulation may activate abilities on. */
    public val authority: AbilityAuthority = AbilityAuthority.All,
    /**
     * Which ability slots cool down together.
     *
     * A game with an item-active slot on its bar declares it here, and gets a cooldown that is
     * shared across those slots and independent of the champion's own. See [CooldownSharing].
     */
    public val sharing: CooldownSharing = CooldownSharing.None,
    /** Cue queue capacity, for a host that expects an unusually busy or unusually idle world. */
    cueCapacity: Int = GasCueQueue.DEFAULT_CAPACITY,
) : UdeaModule {

    override val name: String get() = "gas"

    /** The per-world effect handle source. */
    public val handles: HandleAllocator = HandleAllocator()

    /**
     * The per-world cue queue.
     *
     * Built without a downstream [dev.wildware.udea.core.CueSink], because a
     * `GameContextBuilder`'s sink is not readable before `build()` and this object is
     * constructed before then. [GasCueForwardSystem] closes that gap from the other end: it is
     * handed the built context and drains this queue into `GameContext.cues` every tick, in
     * [SimPhase.Cleanup].
     *
     * Until that system existed nothing drained this queue in an assembled game, so no GAS cue
     * reached `udea-render` or audio at all - the emit path was exercised only by tests.
     */
    public val cues: GasCueQueue = GasCueQueue(cueCapacity)

    /** Applies effects. */
    public val applier: EffectApplier = EffectApplier(effects, handles, cues)

    /** Gates and runs activations. */
    public val activation: AbilityActivation =
        AbilityActivation(abilities, effects, execs, applier, cues, authority, sharing)

    /** The per-tick recompute. */
    public val recompute: AttributeRecompute = AttributeRecompute(effects, attributes, handles)

    /** Everything above, as one registered service. */
    public val services: GasServices = GasServices(
        attributes = attributes,
        effects = effects,
        abilities = abilities,
        handles = handles,
        cues = cues,
        applier = applier,
        activation = activation,
        recompute = recompute,
    )

    override fun context(builder: GameContextBuilder) {
        builder.service(GasServices.KEY, services)
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Ability, { ctx -> AbilitySystem(activation, ctx[CoreModule.NET_IDS]) })
        registry.add(SimPhase.Attribute, { AttributeSystem(recompute) })
        registry.add(SimPhase.Cleanup, { GasCueForwardSystem(cues) })
    }
}
