package dev.wildware.udea.net.relevancy

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * Why one team can currently see one entity — the answer `net.relevancy` prints.
 *
 * Spec 7 asks for "the granting vision source" specifically, and this enum is the other half of
 * that answer: a `NetId` alone cannot distinguish "a scout is looking at it" from "it is one of
 * ours" from "nothing sees it, we are inside the grace window", and those three are the three
 * different bugs a flicker report turns out to be.
 */
public enum class VisionReason {

    /** Nothing on this team can see it. */
    Hidden,

    /** It belongs to this team, so the team sees it unconditionally. */
    OwnTeam,

    /** A team member's sight radius covers it. [TeamVision.sourceOf] names which. */
    Sighted,

    /** Sight was lost, and the linger window has not expired yet. This is the anti-flicker grace. */
    Lingering,
}

/**
 * One team's fog state: who it can see, why, and what changed this tick.
 *
 * ## Per team, not per client
 *
 * Spec 7 makes this the headline mitigation: five clients on a team share one answer, so a 5v5
 * costs two solves rather than ten. Everything here is therefore indexed by `NetId.index` and
 * owned by a team; [FogOfWar] maps a client onto a team and does no per-client work at all
 * beyond that lookup.
 *
 * ## The expand-only double buffer, stated precisely
 *
 * Spec 7 warns that getting this wrong "causes relevancy flicker that presents as a network
 * bug". The failure it is warning about is contraction happening *during* the solve: source A
 * is stamped, then source B's stamp clears what A set, and an entity two sources can both see
 * blinks according to source order. So there are two buffers and exactly one rule:
 *
 * - [beginSolve] clears [back] once. Every [grant] during the solve only ever **sets** bits.
 *   Nothing in the solve can clear one, so source order cannot change the result.
 * - [publish] is the single point where a bit may be cleared, and it happens after every source
 *   has been considered.
 *
 * That is the whole discipline, and it is why [grant] has no counterpart called `revoke`.
 *
 * ## Linger, and why hysteresis alone is not enough
 *
 * A radius band ([FogOfWar.hysteresis]) stops a body oscillating as it walks the boundary. It
 * does nothing about the *source* moving, blinking or dying for a tick. [lingerTicks] covers
 * that: sight lost is not visibility lost until the grace window expires, so a one-tick gap in
 * coverage costs nothing on the wire. Both are needed, and the boundary-walk test fails with
 * either removed.
 *
 * ## Leaving is not dying
 *
 * [leftAt] is the list an entity lands on when it walks into a bush, and it is deliberately
 * separate from the world's destroy path: an entity that has stopped existing is dropped from
 * [visibleAt] with **no** leave event at all, because `EntityOp.Destroy` already covers it.
 * Confusing the two is how a client plays a death animation for a unit that walked behind a
 * wall, which is the exact defect spec 7 names.
 */
public class TeamVision(

    /** Which team this is. */
    public val team: Int,

    /** How many `NetId` indices this team tracks. */
    public val capacity: Int,
) {

    init {
        require(team >= 0) { "team must be non-negative, was $team" }
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(capacity <= NetId.MAX_INDICES) {
            "capacity $capacity is beyond the ${NetId.MAX_INDICES} NetId indices that exist"
        }
    }

    private val words = (capacity + WORD_BITS - 1) / WORD_BITS
    private val front = LongArray(words)
    private val back = LongArray(words)
    private val scratch = LongArray(words)

    private val generation = IntArray(capacity) { NO_GENERATION }
    private val source = IntArray(capacity) { NetId.NONE.raw }
    private val distance = FloatArray(capacity) { Float.POSITIVE_INFINITY }
    private val reason = ByteArray(capacity)
    private val lastSeen = LongArray(capacity) { Long.MIN_VALUE }
    private val since = LongArray(capacity)
    private val enterCounts = IntArray(capacity)
    private val leaveCounts = IntArray(capacity)
    private val observedStamp = LongArray(capacity) { Long.MIN_VALUE }

    private var visible = IntArray(INITIAL_LIST)
    private var visibleSize = 0
    private var left = IntArray(INITIAL_LIST)
    private var leftSize = 0
    private var entered = IntArray(INITIAL_LIST)
    private var enteredSize = 0
    private var stamp = Long.MIN_VALUE

    /** Entities this team can see right now. */
    public val visibleCount: Int get() = visibleSize

    /** The [index]th visible entity, in ascending `NetId.index` order. */
    public fun visibleAt(index: Int): NetId = NetId.ofRaw(visible[index])

    /** Entities that stopped being visible in the last [publish] **without** ceasing to exist. */
    public val leftCount: Int get() = leftSize

    /** The [index]th entity that left. Feeds `EntityOp.Leave`, never `EntityOp.Destroy`. */
    public fun leftAt(index: Int): NetId = NetId.ofRaw(left[index])

    /** Entities that became visible in the last [publish]. */
    public val enteredCount: Int get() = enteredSize

    /** The [index]th entity that entered. */
    public fun enteredAt(index: Int): NetId = NetId.ofRaw(entered[index])

    /** Whether this team may be told about [netId] at all. */
    public fun canSee(netId: NetId): Boolean {
        val index = netId.index
        if (index >= capacity) return false
        if (generation[index] != netId.generation) return false
        return test(front, index)
    }

    /** Why [netId] is or is not visible. [VisionReason.Hidden] for an id this team never saw. */
    public fun reasonFor(netId: NetId): VisionReason {
        val index = netId.index
        if (index >= capacity || generation[index] != netId.generation) return VisionReason.Hidden
        return REASONS[reason[index].toInt()]
    }

    /**
     * The vision source that grants [netId], or [NetId.NONE].
     *
     * For [VisionReason.OwnTeam] this is the entity itself, which is the honest answer: nothing
     * else has to be looking at it. For [VisionReason.Lingering] it is the source that saw it
     * last, which is what makes a flicker report readable — the source that keeps dropping it is
     * named rather than inferred.
     */
    public fun sourceOf(netId: NetId): NetId {
        val index = netId.index
        if (index >= capacity || generation[index] != netId.generation) return NetId.NONE
        return NetId.ofRaw(source[index])
    }

    /** Distance to [sourceOf], or infinity when nothing grants it. */
    public fun distanceOf(netId: NetId): Float {
        val index = netId.index
        if (index >= capacity || generation[index] != netId.generation) return Float.POSITIVE_INFINITY
        return distance[index]
    }

    /** The tick [netId]'s current visible-or-hidden state began, or [Tick.ZERO] if never tracked. */
    public fun sinceTick(netId: NetId): Tick =
        if (netId.index >= capacity) Tick.ZERO else Tick(since[netId.index])

    /** How many times [netId] has entered this team's vision. The flicker counter. */
    public fun enterCount(netId: NetId): Int =
        if (netId.index >= capacity) 0 else enterCounts[netId.index]

    /** How many times [netId] has left it. A boundary walk must not move this past one. */
    public fun leaveCount(netId: NetId): Int =
        if (netId.index >= capacity) 0 else leaveCounts[netId.index]

    /**
     * Opens a solve for [solveStamp].
     *
     * The stamp is the tick, and it is what tells "this entity is hidden" apart from "this
     * entity is not in the world any more" at [publish] time without a second pass over the
     * roster: anything still carrying an older stamp was never offered this tick.
     */
    public fun beginSolve(solveStamp: Long) {
        stamp = solveStamp
        back.fill(0L)
        leftSize = 0
        enteredSize = 0
    }

    /** Records that [netId] exists this solve, whether or not anything can see it. */
    public fun observe(netId: NetId) {
        val index = netId.index
        if (index >= capacity) return
        if (generation[index] != netId.generation) reset(index, netId.generation)
        observedStamp[index] = stamp
    }

    /**
     * Grants vision of [netId] from [visionSource] at [range].
     *
     * Only ever sets. Calling it twice keeps the **nearer** source, so the reported granting
     * source is a stable function of the world rather than of the order sources were iterated in
     * — which matters because that report is what an operator uses to explain a flicker.
     */
    public fun grant(netId: NetId, visionSource: NetId, range: Float, why: VisionReason) {
        val index = netId.index
        if (index >= capacity) return
        if (generation[index] != netId.generation) reset(index, netId.generation)
        observedStamp[index] = stamp
        val already = test(back, index)
        if (!already || range < distance[index] || (range == distance[index] && visionSource < sourceAt(index))) {
            source[index] = visionSource.raw
            distance[index] = range
            reason[index] = why.ordinal.toByte()
        }
        set(back, index)
    }

    /**
     * Contracts the front buffer to what the solve found, applies [lingerTicks], and records the
     * enter and leave lists. The one place a visibility bit is ever cleared.
     */
    public fun publish(tick: Tick, lingerTicks: Int) {
        for (word in 0 until words) scratch[word] = front[word] or back[word]
        visibleSize = 0
        for (word in 0 until words) {
            var bits = scratch[word]
            while (bits != 0L) {
                val bit = bits.countTrailingZeroBits()
                bits = bits and (bits - 1)
                settle(word * WORD_BITS + bit, tick, lingerTicks)
            }
        }
    }

    override fun toString(): String = "TeamVision(team=$team, $visibleSize visible)"

    /** Decides one entity's fate and appends it to the right list. */
    private fun settle(index: Int, tick: Tick, lingerTicks: Int) {
        val wasVisible = test(front, index)
        val seen = test(back, index)
        if (observedStamp[index] != stamp) {
            // Not offered this solve: the entity has left the world entirely. Destroy owns it, so
            // it is dropped silently and deliberately does NOT land on the leave list.
            clear(front, index)
            reason[index] = VisionReason.Hidden.ordinal.toByte()
            return
        }
        if (seen) lastSeen[index] = tick.value
        val lingering = !seen && wasVisible && tick.value - lastSeen[index] <= lingerTicks
        if (lingering) reason[index] = VisionReason.Lingering.ordinal.toByte()
        val nowVisible = seen || lingering
        when {
            nowVisible && !wasVisible -> {
                set(front, index)
                enterCounts[index]++
                since[index] = tick.value
                if (enteredSize == entered.size) entered = entered.copyOf(enteredSize * 2)
                entered[enteredSize++] = rawOf(index)
            }

            !nowVisible && wasVisible -> {
                clear(front, index)
                leaveCounts[index]++
                since[index] = tick.value
                source[index] = NetId.NONE.raw
                distance[index] = Float.POSITIVE_INFINITY
                reason[index] = VisionReason.Hidden.ordinal.toByte()
                if (leftSize == left.size) left = left.copyOf(leftSize * 2)
                left[leftSize++] = rawOf(index)
            }

            !nowVisible -> reason[index] = VisionReason.Hidden.ordinal.toByte()

            else -> Unit
        }
        if (nowVisible) {
            if (visibleSize == visible.size) visible = visible.copyOf(visibleSize * 2)
            visible[visibleSize++] = rawOf(index)
        }
    }

    private fun rawOf(index: Int): Int = NetId.of(index, generation[index]).raw

    private fun sourceAt(index: Int): NetId = NetId.ofRaw(source[index])

    private fun reset(index: Int, newGeneration: Int) {
        generation[index] = newGeneration
        clear(front, index)
        clear(back, index)
        source[index] = NetId.NONE.raw
        distance[index] = Float.POSITIVE_INFINITY
        reason[index] = VisionReason.Hidden.ordinal.toByte()
        lastSeen[index] = Long.MIN_VALUE
        since[index] = 0L
        enterCounts[index] = 0
        leaveCounts[index] = 0
    }

    private fun test(bits: LongArray, index: Int): Boolean =
        (bits[index ushr WORD_SHIFT] and (1L shl (index and WORD_MASK))) != 0L

    private fun set(bits: LongArray, index: Int) {
        bits[index ushr WORD_SHIFT] = bits[index ushr WORD_SHIFT] or (1L shl (index and WORD_MASK))
    }

    private fun clear(bits: LongArray, index: Int) {
        bits[index ushr WORD_SHIFT] = bits[index ushr WORD_SHIFT] and (1L shl (index and WORD_MASK)).inv()
    }

    private companion object {

        const val WORD_BITS: Int = 64
        const val WORD_SHIFT: Int = 6
        const val WORD_MASK: Int = 63

        /** No entity has ever occupied this index. Outside `NetId`'s 0..255 generations. */
        const val NO_GENERATION: Int = -1

        /** Room for a full lane's worth of visible bodies before the first grow. */
        const val INITIAL_LIST: Int = 64

        val REASONS: Array<VisionReason> = VisionReason.entries.toTypedArray()
    }
}
