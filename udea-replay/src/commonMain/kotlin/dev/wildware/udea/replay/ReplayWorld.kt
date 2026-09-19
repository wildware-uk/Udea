package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.snapshot.WorldSnapshot

/**
 * The simulation a recording is played back into, as this module is allowed to see it.
 *
 * ## Why an interface and not a `GameHost`
 *
 * Playing a recording back needs four things: put this tick's input where the simulation will
 * read it, run exactly one tick, hash the result, and - when a divergence has to be explained -
 * hand over the captured world. Three of those are `GameHost` calls and the fourth is not: the
 * *input* seam is a game's own, because what "apply this input" means is `IntentState.source` in
 * `moba` and a jitter buffer in a dedicated server, and neither type may be named from a module
 * that must stay headless (`InputSchema` carries the module-graph reasoning).
 *
 * So the game implements this in six lines and this module never learns what a device is. It is
 * the same shape `IntentSource` uses for the same reason - the simulation cannot tell a replayed
 * input from a human's, which is the property the entire phase rests on.
 *
 * ## The contract
 *
 * [applyInput] then [step], once per tick, in that order. [hash] is read **after** [step], so it
 * describes the world the tick produced; a hash read before it describes the previous tick's
 * world, and the whole stream would be off by one.
 */
public interface ReplayWorld {

    /** The tick about to be simulated. Advances by exactly one per [step]. */
    public val tick: Tick

    /**
     * Puts [samples] where this tick's simulation will read them.
     *
     * Called once per tick, immediately before [step]. The array is the replay's own and is
     * overwritten on the next tick, so an implementation must copy anything it keeps.
     */
    public fun applyInput(samples: Array<InputSample>)

    /**
     * Makes this tick's recorded edits again, in order, before its input and before [step].
     *
     * Each is a call an agent or the editor window made between ticks (see [ReplayEdit]); a game
     * whose replay world can serve the editor's tools submits each one through them. The default
     * applies none and refuses a tick that has any: a world that quietly skipped them would
     * diverge on the first edited tick with nothing saying why.
     */
    public fun applyEdits(edits: List<ReplayEdit>) {
        check(edits.isEmpty()) {
            "this recording has ${edits.size} edit(s) made between ticks on ${edits.first().tick}, " +
                "starting with ${edits.first().tool}, and $this cannot apply edits; replay it in " +
                "a world that serves the editor's tools"
        }
    }

    /** Runs exactly one simulation tick. Not a frame, not "about one". */
    public fun step()

    /**
     * `WorldHasher.hash` over a capture of the world as it stands.
     *
     * Must be the [dev.wildware.udea.core.snapshot.WorldHasher.hash] overload that takes a
     * [WorldSnapshot] and not the one that takes a field store: the field-store overload leaves
     * out the RNG state and the id allocator, so two runs that reached the same world by drawing
     * a different number of random values would hash the same and the divergence would surface
     * seconds later, on the first tick the difference became visible in a field.
     */
    public fun hash(): Long

    /**
     * The world as a snapshot, for a field-level divergence report.
     *
     * The returned snapshot may be a reused buffer; a caller that needs two at once must say so
     * by asking two worlds. `null` when this implementation has no snapshot registry, which is
     * legal and costs only the field names in a divergence report.
     */
    public fun snapshot(): WorldSnapshot?

    /** Frees anything the world holds. A session that rebuilds calls this on the old one. */
    public fun close() {}
}

/**
 * Puts [tick]'s recorded edits and input into this world, ready for [ReplayWorld.step].
 *
 * The edits first: they were applied at the top of the tick, where the barrier drains. The one
 * place the replay drivers do this, so none of them can feed a tick's input and forget its edits.
 */
internal fun ReplayWorld.feed(recording: ReplayRecording, tick: Tick, slots: Array<InputSample>) {
    applyEdits(recording.editsAt(tick))
    recording.samplesInto(tick, slots)
    applyInput(slots)
}

/**
 * Builds a fresh world at the recording's first tick.
 *
 * A factory rather than a single instance, because `replay.seek` backwards has no way to run a
 * simulation in reverse: it rebuilds from the start and fast-forwards. That is the same answer
 * `TimeControl.rewind` gives, and for the same reason.
 */
public fun interface ReplayWorldFactory {

    /**
     * A world seeded and stepped to exactly [firstTick], with nothing else done to it.
     *
     * @throws IllegalStateException if the world cannot be brought to that tick. Loud, because a
     *   replay that started one tick early would diverge on tick one with no cause in the world.
     */
    public fun create(firstTick: Tick): ReplayWorld
}

/**
 * Where a baseline world's snapshot at an arbitrary tick comes from, when one exists.
 *
 * ## What this closes, and what it honestly does not
 *
 * A `.udearep` stores one `Long` per tick, not a world per tick, so a file on its own can say
 * *which tick* two runs stopped agreeing on and cannot say *which field*: naming a field needs
 * the record-time values, and hashes are one-way. Storing a snapshot per tick would make a
 * two-thousand-tick match tens of megabytes and would still be the wrong trade, because the
 * question is asked on the small fraction of runs that diverge.
 *
 * The producer usually still has the answer. A host with a snapshot ring can `TimeControl.rewind`
 * to the tick and capture it, which is exact - `SnapshotRestoreProofTest` proves the landing is
 * exact on the real level - and that is what `moba` supplies here. When nothing supplies one,
 * [ReplayVerification.fields] is empty and [ReplayVerification.describe] says so in a sentence
 * rather than leaving a reader to wonder whether the fields agreed.
 */
public fun interface BaselineSnapshots {

    /** The baseline world at [tick], or `null` when it cannot be reconstructed. */
    public fun snapshotAt(tick: Tick): WorldSnapshot?

    public companion object {

        /** No baseline. What a replay of a file from another machine has. */
        public val NONE: BaselineSnapshots = BaselineSnapshots { null }
    }
}
