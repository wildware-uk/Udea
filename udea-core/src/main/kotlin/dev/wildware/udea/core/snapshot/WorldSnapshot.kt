package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.HandleState

/**
 * One whole simulation at one tick: every field, every id, the random streams, the scene.
 *
 * The unit the ring stores and [SnapshotService] fills and reads back. It is deliberately a
 * mutable, **pooled** object rather than a value: spec 7 gives the ring a 64MB budget and a
 * per-tick capture that must not allocate, and a fresh snapshot per tick would allocate a
 * megabyte of columns sixty times a second. A ring slot is acquired, refilled and released;
 * only [SnapshotRing] ever constructs one.
 *
 * ## What is in it, and what is deliberately not
 *
 * | Carried | Why |
 * |---|---|
 * | [tick] | the snapshot's identity; the ring is keyed by it |
 * | [scene] | a cross-scene restore must load the scene before applying fields (spec 5) |
 * | [fields] | every `@Net` and `@Sim` field of every entity, columnar |
 * | [rng] | `DefaultRngService.saveState()`, or a rewind re-rolls different numbers |
 * | [handles] | the `NetIdIndex` allocator, or a re-run hands out different ids |
 *
 * Not carried, and not by omission: Box2D bodies (spec 3.4 — rebuilt from components after a
 * restore, never snapshot state), and everything in [SnapshotExclusion]. The `NetIdIndex`
 * itself is not carried either — the roster of live ids is already `fields`' row keys, in
 * ascending order, and duplicating it would be a second thing that can disagree.
 */
public class WorldSnapshot internal constructor(
    /** Which component types this snapshot was captured against. */
    public val registry: ComponentRegistry,
) {

    /** The tick this snapshot was taken at: the value `SimClock.tick` read during capture. */
    public var tick: Tick = Tick.ZERO
        internal set

    /**
     * The scene that was simulating, or `null` before any scene became active.
     *
     * Restoring into a different scene is a [RewindFailure.SceneMismatch] unless the caller
     * loads the scene first, because entity ids from one scene mean nothing in another.
     */
    public var scene: SceneId? = null
        internal set

    /** Every captured field, by entity row and component type. */
    public val fields: WorldFieldStore = WorldFieldStore(registry)

    /** Every random stream's state, in `DefaultRngService.saveState()` layout. */
    public var rng: LongArray = EMPTY_RNG
        internal set

    /** The `NetIdIndex` allocator state, so a re-run mints the same ids the first run did. */
    public val handles: HandleState = HandleState()

    /** True once this snapshot holds a capture. A pooled slot between uses does not. */
    public var isFilled: Boolean = false
        internal set

    /** Bytes of backing storage this snapshot holds. What the ring's budget counts. */
    public fun sizeBytes(): Long =
        fields.sizeBytes() + handles.sizeBytes() + rng.size.toLong() * Long.SIZE_BYTES

    /** Empties the snapshot and keeps every buffer, ready to be refilled by a capture. */
    internal fun reset() {
        fields.reset()
        handles.reset()
        tick = Tick.ZERO
        scene = null
        isFilled = false
    }

    /**
     * The `LongArray` a capture writes RNG state into, grown only when the stream count grows.
     *
     * Reusing it is what keeps `RngService.saveInto` off the allocating path; `saveState()`
     * would hand back a fresh array on every tick.
     */
    internal fun rngBuffer(words: Int): LongArray {
        if (rng.size != words) rng = LongArray(words)
        return rng
    }

    override fun toString(): String =
        "WorldSnapshot(tick=$tick, scene=$scene, entities=${fields.rowCount}, bytes=${sizeBytes()})"

    private companion object {
        val EMPTY_RNG: LongArray = LongArray(0)
    }
}
