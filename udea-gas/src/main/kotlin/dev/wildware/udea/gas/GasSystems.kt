package dev.wildware.udea.gas

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueSink
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * Runs in-flight ability activations, one tick each.
 *
 * A thin adapter: every rule lives in [AbilityActivation], which is world-free and therefore
 * testable without one. The family bag is walked by index rather than with `forEach`, because a
 * lambda capturing the system is one closure per tick on the allocation budget.
 */
public class AbilitySystem(
    private val activation: AbilityActivation,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val family: Family = world.family { all(Abilities, Attributes, GameplayEffects) }

    override fun onTick() {
        val entities = family.entities
        val now = tick
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            activation.tick(
                self = netIds.netIdOf(entity),
                abilities = entity[Abilities],
                attributes = entity[Attributes],
                effects = entity[GameplayEffects],
                now = now,
            )
            index++
        }
    }
}

/**
 * Recomputes every entity's attributes from base plus active modifiers, once per tick.
 *
 * Runs in [dev.wildware.udea.core.module.SimPhase.Attribute], after [AbilitySystem] has applied
 * whatever this tick's activations applied — which is what the phase order exists to guarantee,
 * rather than a `before`/`after` constraint between two modules that must not know about each
 * other.
 */
public class AttributeSystem(
    private val recompute: AttributeRecompute,
) : SimSystem() {

    private val family: Family = world.family { all(Attributes, GameplayEffects) }

    override fun onTick() {
        val entities = family.entities
        val now = tick
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            recompute.recompute(entity[Attributes], entity[GameplayEffects], now)
            index++
        }
    }
}

/**
 * Drains this world's [GasCueQueue] into the kernel's [CueSink], once per tick.
 *
 * ## The seam this closes
 *
 * `GasModule` builds its queue with a null sink, and its KDoc explained why: a
 * `GameContextBuilder`'s sink is not readable before `build()`, so the module cannot hand
 * `builder.cues` to a queue it constructs in its own initialiser. The consequence went
 * unstated - **no GAS cue ever reached `udea-core`** in an assembled game. Every damage number,
 * every hit flash, every ability sound was emitted into a queue that only a test ever drained.
 * `udea-render` and audio drain `GameContext.cues`; nothing drained this one.
 *
 * A system rather than a wiring change because a `SimSystem` is handed the built [ctx], which is
 * the first moment the real sink exists. [SimPhase.Cleanup] because a cue is presentation output
 * for the tick that has just finished: every phase that could emit one has run, and draining
 * earlier would forward this tick's cues and leave the rest for the next.
 *
 * ## Why it forwards rather than mirrors
 *
 * [GasCueQueue.drain] empties as it goes, so a cue is delivered exactly once and the queue does
 * not grow without bound when a presentation layer is absent - which was the other half of the
 * old arrangement's cost. A host that wants the GAS-shaped [CueEvent] (payloads, effect handle,
 * prediction key) rather than the kernel's three-field [Cue] reads `GasServices.cues` before
 * this system runs; that is what the phase order makes possible.
 *
 * The [Cue] loses [CueEvent.target], [CueEvent.payload0], [CueEvent.payload1] and
 * [CueEvent.predictionKey], because `Cue` has nowhere to put them. That is a real narrowing and
 * not an oversight: widening the kernel's cue is a `udea-core` change with a `udea-render`
 * consumer, and `CueEvent.payload0/1` are not populated by anything yet.
 */
public class GasCueForwardSystem(
    private val cues: GasCueQueue,
) : SimSystem() {

    /** Resolved once. `ctx` is available from construction; `World.inject` is not free per tick. */
    private val sink: CueSink = ctx.cues

    /**
     * Hoisted, not written inline at the call site.
     *
     * `drain { ... }` capturing `sink` allocates a closure per tick, which is the one thing the
     * whole module is written to avoid. This one captures `this` and is created once.
     */
    private val forward: (CueEvent) -> Unit = { event ->
        sink.emit(Cue(CueId(event.cueId), event.tick, event.source))
    }

    /** How many cues this system has forwarded. Zero after a busy tick means the seam is broken. */
    public var forwarded: Long = 0L
        private set

    override fun onTick() {
        forwarded += cues.drain(forward).toLong()
    }
}
