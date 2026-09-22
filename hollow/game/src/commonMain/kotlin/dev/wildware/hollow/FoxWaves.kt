package dev.wildware.hollow

import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.module.CoreModule
import kotlin.math.sqrt

/**
 * When the foxes come, and how many (issue #251): wave `k` arrives on tick
 * `first + k * every` and brings `size + k * growth` foxes, never more than [cap] alive at once.
 *
 * ## The wave number is the tick's, and nothing stores it
 *
 * Which wave arrives on a tick is a pure function of the tick - [waveAt] - so there is no wave
 * counter and no "next wave" timer anywhere in the world. A rewind therefore has nothing to
 * restore and nothing to get wrong: put the clock back and the schedule is back with it. What
 * *does* vary between two matches - where each wave comes from and where each fox stands in it - is
 * drawn from `RngService`'s `Wave` and `Spawn` streams, whose state the snapshot carries by the
 * frozen randomness contract, so the same seed sends the same foxes to the same places.
 *
 * A `data class`: two schedules with the same numbers are the same schedule.
 */
public data class FoxWaves(
    /** The tick the first wave arrives on. */
    val first: Tick = Tick(240L),
    /** Ticks between one wave and the next. */
    val every: Ticks = Ticks(1_200L),
    /** Foxes in the first wave. */
    val size: Int = 3,
    /** Foxes each wave brings over the one before it. */
    val growth: Int = 2,
    /** The most foxes alive at once: a wave that would pass it brings only the difference. */
    val cap: Int = 24,
) {

    init {
        require(every.count > 0L) { "waves must be at least a tick apart, not $every" }
        require(size >= 0 && growth >= 0 && cap >= 0) { "a wave cannot bring fewer than no foxes: $this" }
    }

    /** The number of the wave that arrives on [tick], or `-1` when none does. */
    internal fun waveAt(tick: Tick): Long {
        val since = tick.ticksSince(first)
        if (since < 0L || since % every.count != 0L) return NO_WAVE
        return since / every.count
    }

    /**
     * How many waves have arrived by [tick], counting one on it: `0` before [first], `1` from it
     * until the second, and so on. What the HUD calls the wave (issue #252), and a pure function of
     * the tick like [waveAt], so a client asks it of the server's tick and gets the server's answer.
     */
    public fun arrivedBy(tick: Tick): Long {
        val since = tick.ticksSince(first)
        return if (since < 0L) 0L else since / every.count + 1
    }

    /** How many foxes wave [wave] brings, before [cap] is applied. */
    internal fun sizeOf(wave: Long): Int = (size + wave * growth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    public companion object {

        /** [waveAt]'s answer on a tick no wave arrives on. */
        internal const val NO_WAVE: Long = -1L

        /**
         * The schedule the game plays: a first wave four seconds in, then one every twenty.
         *
         * Public because a launcher names it twice - once for the session it starts and once for
         * the HUD, which shows the wave number and has to be told the same schedule the server is
         * playing (issue #252). Two launch lines with two schedules would put a number on the
         * screen that no wave matches.
         */
        public val DEFAULT: FoxWaves = FoxWaves()

        /**
         * How far from the middle of the clearing a wave arrives: just inside the inner ring of
         * trees, which stands from 14 metres out, so a wave walks in out of the forest's edge.
         */
        internal const val EDGE: Float = 13f

        /** How far, on each axis, a fox may stand from its wave's centre when it arrives. */
        internal const val SPREAD: Float = 1.8f

        /**
         * How far from the middle of the clearing a fox that has just arrived heads for, on each
         * axis: the clearing's heart, where the players are, with a little scatter so a wave does not
         * converge on one point.
         */
        internal const val HEART: Float = 2f

        /** How long a newly arrived fox keeps walking in before it wanders on its own account. */
        internal val APPROACH: Ticks = Ticks(900L)
    }
}

/**
 * Sends the waves [schedule] describes (issue #251): on a wave's tick, a group of foxes at the
 * clearing's edge, walking in towards its heart.
 *
 * ## Two streams, one question each
 *
 * - **`Wave`**: which way this wave comes from - one direction per wave, so a wave arrives as a
 *   pack out of one stretch of forest rather than as foxes spread evenly round the ring.
 * - **`Spawn`**: where in its pack each fox stands, and which point of the heart it heads for.
 *
 * Separate so that a change to how many foxes a wave brings - which changes how many `Spawn`
 * draws a wave makes - does not change which way any later wave comes from.
 *
 * ## A direction without an angle
 *
 * `determinism-audit.md` section 3.1 asks for no `sin` or `cos` in authoritative state unless it is
 * `StrictMath`, a table or fixed point, and a spawn position is as authoritative as state gets. So
 * the direction is not an angle at all: it is a point drawn uniformly in the square, kept when it
 * falls in the ring between half and the whole of the unit circle, and made unit length with
 * `sqrt`, which IEEE-754 specifies exactly. That is uniform in direction, it uses only the four
 * operations and a square root, and it needs no table this project would have to own.
 *
 * `SimPhase.PreSimulation`: a wave's foxes are in the world before any fox decides anything on the
 * tick they arrive, and their bodies are made by the solver's own step the same tick.
 */
internal class FoxWaveSystem(private val schedule: FoxWaves) : SimSystem() {

    private val foxes: Family = world.family { all(Fox) }

    private val direction = Direction()

    override fun onTick() {
        val now = tick
        val wave = schedule.waveAt(now)
        if (wave == FoxWaves.NO_WAVE) return
        // The direction first, even for a wave the cap turns away: a wave's `Wave` draws are then
        // the same whatever came before it, so how crowded the clearing was cannot change which way
        // any later wave comes from.
        bearing()
        val room = schedule.cap - foxes.numEntities
        val count = minOf(schedule.sizeOf(wave), room)
        if (count <= 0) return
        val centreX = direction.x * FoxWaves.EDGE
        val centreY = direction.y * FoxWaves.EDGE
        val netIds = ctx[CoreModule.NET_IDS]
        repeat(count) {
            Fox.spawn(
                world = world,
                netIds = netIds,
                x = centreX + spread(FoxWaves.SPREAD),
                y = centreY + spread(FoxWaves.SPREAD),
                goalX = spread(FoxWaves.HEART),
                goalY = spread(FoxWaves.HEART),
                decideAt = now + FoxWaves.APPROACH.count,
            )
        }
    }

    /**
     * Fills [direction] with a unit vector drawn from the `Wave` stream.
     *
     * A draw is kept when it lands in the ring, which is about 0.59 of the square, so [ATTEMPTS]
     * misses in a row is a chance under one in a million; should it happen, the wave comes from
     * the east rather than from nowhere, and it still happens identically on every machine.
     */
    private fun bearing() {
        repeat(ATTEMPTS) {
            val x = ctx.rng.nextFloat(RngStream.Wave) * 2f - 1f
            val y = ctx.rng.nextFloat(RngStream.Wave) * 2f - 1f
            val squared = x * x + y * y
            if (squared in INNER_SQUARED..1f) {
                val length = sqrt(squared)
                direction.x = x / length
                direction.y = y / length
                return
            }
        }
        direction.x = 1f
        direction.y = 0f
    }

    /** A value in `[-reach, reach)` from the `Spawn` stream. */
    private fun spread(reach: Float): Float = (ctx.rng.nextFloat(RngStream.Spawn) * 2f - 1f) * reach

    override fun toString(): String = "FoxWaveSystem($schedule)"

    /** A wave's direction. Reused, because this is a per-tick system. */
    private class Direction {
        var x: Float = 1f
        var y: Float = 0f
    }

    private companion object {
        /** Draws before a wave gives up on the ring and comes from the east. */
        const val ATTEMPTS: Int = 16

        /** The ring's inner edge, squared: half the unit circle's radius keeps short vectors out. */
        const val INNER_SQUARED: Float = 0.25f
    }
}
