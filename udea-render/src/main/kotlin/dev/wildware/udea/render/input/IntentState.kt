package dev.wildware.udea.render.input

import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.serviceKey

/**
 * The one [Intent] the simulation reads, and the source it is sampled from.
 *
 * Published on the `GameContext` under [KEY] by [InputModule], so a game's control system reaches
 * input the same way it reaches any other service - a constructor parameter resolved from the
 * context - rather than by calling a device.
 *
 * ## Sampled once per tick, and that is the whole point
 *
 * [sample] is called by [IntentSampleSystem] at `SimPhase.Intent`, which runs exactly once per
 * tick by construction. A frame that contains three ticks samples three times, a frame that
 * contains none samples none, and a 250ms stall produces exactly the fifteen samples the fifteen
 * ticks it catches up need. The old `ControllerSystem` sampled per frame, so the same second of
 * game time contained 30 samples on a slow machine and 144 on a fast one - which meant "held for
 * ten samples" was a different duration on every machine, and no recorded input stream could be
 * replayed. `IntentSamplingTest` pins the new behaviour against three frame patterns.
 */
public class IntentState(
    /** What actions and axes exist, and their ids. */
    public val bindings: InputBindings,
    source: IntentSource = IntentSource.NONE,
) {

    /** This tick's input. Overwritten in place every tick; never hold a reference across ticks. */
    public val intent: Intent = Intent(bindings.catalog)

    /**
     * Where input comes from. Swappable at runtime, from any thread.
     *
     * `@Volatile` and not a lock: it is written rarely (a client wires a keyboard at start-up, an
     * agent host swaps in an injected source) and read once per tick. A tick either sees the old
     * source or the new one, never a torn reference, which is the whole of what is needed - a
     * source swapped mid-tick taking effect next tick is correct behaviour, not a race.
     */
    @Volatile
    public var source: IntentSource = source

    /** How many times [sample] has run. Equals the tick count once the game is running. */
    public var sampleCount: Long = 0L
        private set

    /** The tick the current [intent] was sampled for. */
    public var sampledAt: Tick = Tick(-1L)
        private set

    /** Clears [intent] and lets [source] fill it. Simulation thread, once per tick. */
    public fun sample(tick: Tick) {
        intent.clear()
        source.sample(intent)
        sampledAt = tick
        sampleCount++
    }

    override fun toString(): String = "IntentState($source, samples=$sampleCount)"

    public companion object {

        /** How a system finds this on the `GameContext`. */
        public val KEY: dev.wildware.udea.core.ServiceKey<IntentState> =
            serviceKey("udea-render/IntentState")
    }
}

/**
 * Turns the device (or the agent, or the replay) into this tick's [Intent]. One line of work.
 *
 * It is a `SimSystem` and it names no LibGDX type - `NoDeviceInSimTest` proves that by reading
 * the compiled bytecode of every `SimSystem` in this module, which is the check the acceptance
 * criteria ask for and the check a comment cannot make.
 *
 * Registered first in `SimPhase.Intent`, so every control system in that phase reads a value
 * sampled this tick rather than the previous tick's. A game orders its own control system with
 * `after(IntentSampleSystem::class)`.
 */
public class IntentSampleSystem(private val state: IntentState) : SimSystem() {

    override fun onTick() {
        state.sample(tick)
    }
}
