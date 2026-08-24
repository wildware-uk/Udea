package dev.wildware.udea.net.prediction

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.math.sqrt

/**
 * The clock a client draws *other people* by: the server's tick, held deliberately in the past.
 *
 * ## Why the delay exists at all
 *
 * A remote unit's position is only known at the ticks whose snapshots arrived. Drawing it at the
 * newest one means drawing a step function - the unit sits still, then jumps - and every dropped
 * or late packet makes the jump bigger. Drawing it at `newest - delay` instead means there is
 * almost always a *later* sample to interpolate towards, so the unit slides. The delay is the
 * price: everybody else is rendered [delayTicks] behind where the server says they are, which is
 * why it is a parameter and not a constant, and why prediction (this file's opposite number)
 * exists for the one unit the delay would be intolerable on - your own.
 *
 * ## Why it is a rate-adjusted clock and not `newestServerTick - delay`
 *
 * Snapshots arrive when the network says so. Deriving the render tick straight from the newest
 * arrival makes the render clock inherit the link's jitter: it stands still for three ticks and
 * then advances four, and the interpolation between them stutters at exactly the rate the
 * network does. So the render tick advances by *one tick per client tick* on its own and is
 * only nudged towards the target - at most [maxRate] and at least [minRate] of real time. A
 * player cannot see a 10% speed difference in another character; they can see it stop and jump.
 *
 * There is no wall clock anywhere in this: [advance] is called once per client tick and the unit
 * of everything here is a [Tick].
 */
public class InterpolationClock(

    /**
     * How far behind the newest received server tick to render, in ticks.
     *
     * Six ticks is 100ms at 60Hz: two full 30Hz send intervals plus slack, so a single lost
     * snapshot still has a later sample to interpolate towards and does not starve the buffer.
     */
    public val delayTicks: Float = DEFAULT_DELAY_TICKS,

    /** Fraction of the remaining offset absorbed per tick when catching up or slowing down. */
    public val catchUp: Float = DEFAULT_CATCH_UP,

    /** Slowest the render clock may run, as a multiple of real time. */
    public val minRate: Float = DEFAULT_MIN_RATE,

    /** Fastest the render clock may run, as a multiple of real time. */
    public val maxRate: Float = DEFAULT_MAX_RATE,

    /**
     * Past this many ticks of disagreement the clock is re-anchored instead of eased.
     *
     * A connection that stalled for two seconds and came back is not "slightly behind": easing
     * at 25% would take eight seconds to catch up, during which every remote unit is rendered
     * two seconds in the past. That is a worse artefact than the one jump.
     */
    public val resyncTicks: Float = DEFAULT_RESYNC_TICKS,
) {

    init {
        require(delayTicks >= 0f) { "delayTicks must not be negative, was $delayTicks" }
        require(catchUp > 0f && catchUp <= 1f) { "catchUp is a fraction in (0, 1], was $catchUp" }
        require(minRate > 0f && minRate <= 1f) { "minRate is a fraction in (0, 1], was $minRate" }
        require(maxRate >= 1f) { "maxRate must be at least 1, was $maxRate" }
        require(resyncTicks > 0f) { "resyncTicks must be positive, was $resyncTicks" }
    }

    /** False until the first snapshot has arrived; there is no meaningful render tick before it. */
    public var started: Boolean = false
        private set

    /** Where the clock is being eased towards: the newest server tick minus [delayTicks]. */
    public var target: Double = 0.0
        private set

    /** The tick to sample every remote entity at. Fractional: it lands between two snapshots. */
    public var renderTick: Double = 0.0
        private set

    /** Times the clock was re-anchored rather than eased. Non-zero means the link stalled. */
    public var resyncs: Long = 0L
        private set

    /** The rate the last [advance] ran at. 1.0 is real time. */
    public var lastRate: Float = 1f
        private set

    /** Records the newest server tick this client holds state for. */
    public fun onSnapshot(serverTick: Tick) {
        val candidate = serverTick.value.toDouble() - delayTicks
        // Only ever forwards: a stale datagram overtaking a fresh one must not drag the render
        // clock backwards, which would make every remote unit walk back the way it came.
        if (started && candidate <= target) return
        target = candidate
        if (!started) {
            renderTick = candidate
            started = true
        }
    }

    /**
     * Advances by one client tick, easing towards [target].
     *
     * ## The [CONTROLLER_LAG] term is not a fudge
     *
     * [target] is not a fixed point being converged on: it moves forward by one tick every time
     * a snapshot arrives, which in the steady state is once per call to this. A proportional
     * controller chasing a target that ramps settles at a constant offset *ahead of* it - and
     * the algebra says the offset is exactly one tick of ramp, whatever [catchUp] is. Left
     * uncompensated, a clock asked for six ticks of interpolation delay would settle on five,
     * and the parameter would be a lie by exactly one tick.
     *
     * Aiming one tick further back cancels it. When the link is dropping snapshots the target
     * ramps *slower*, the cancellation is partial, and the clock settles a little further back
     * than asked - which is the right direction: a starving buffer wants more delay, not less.
     */
    public fun advance() {
        if (!started) return
        val error = target - CONTROLLER_LAG - renderTick
        if (error > resyncTicks || error < -resyncTicks) {
            renderTick = target
            lastRate = 1f
            resyncs++
            return
        }
        var rate = 1f + (error * catchUp).toFloat()
        if (rate > maxRate) rate = maxRate
        if (rate < minRate) rate = minRate
        lastRate = rate
        renderTick += rate.toDouble()
    }

    override fun toString(): String =
        "InterpolationClock(render=$renderTick, target=$target, delay=$delayTicks)"

    public companion object {

        /** 100ms at 60Hz. @see InterpolationClock.delayTicks */
        public const val DEFAULT_DELAY_TICKS: Float = 6f

        /** @see InterpolationClock.catchUp */
        public const val DEFAULT_CATCH_UP: Float = 0.1f

        /** @see InterpolationClock.minRate */
        public const val DEFAULT_MIN_RATE: Float = 0.9f

        /** @see InterpolationClock.maxRate */
        public const val DEFAULT_MAX_RATE: Float = 1.1f

        /** Two seconds at 60Hz. @see InterpolationClock.resyncTicks */
        public const val DEFAULT_RESYNC_TICKS: Float = 120f

        /** One tick of target ramp. @see InterpolationClock.advance */
        private const val CONTROLLER_LAG: Double = 1.0
    }
}

/**
 * A snapshot buffer per remote entity, sampled at a fractional tick so they *slide*.
 *
 * ## What it is for
 *
 * Without this a client draws every remote unit wherever the newest packet put it, so a unit
 * teleports once per arrival and stands still in between. With it, the unit is drawn between the
 * two samples bracketing [InterpolationClock.renderTick] and moves continuously, which is what
 * "other players move smoothly" means concretely.
 *
 * ## It never extrapolates
 *
 * Past the newest sample the position is **held**, not projected forward. Extrapolation is the
 * standard alternative and it is worse here: a MOBA unit's velocity changes on a dime - it stops
 * dead at its target's reach, it dies - so projecting it forward means overshooting and then
 * snapping back, which produces exactly the teleport this class exists to remove, and produces
 * it at the *worst* moment, when the link is already struggling. Holding is visibly a pause;
 * snapping back is visibly a bug. [starved] counts how often it happens so the choice is
 * measurable rather than assumed.
 *
 * ## It does not interpolate across a teleport
 *
 * Two consecutive samples further apart than [teleportDistance] are not a fast unit, they are a
 * respawn or a recycled [NetId]. Sliding between them walks the sprite across the map over the
 * whole interpolation delay. So that segment is stepped rather than interpolated, and [teleports]
 * counts it.
 */
public class EntityInterpolator(

    /**
     * Samples held per entity.
     *
     * Must comfortably exceed [InterpolationClock.delayTicks] divided by the send interval, or
     * the sample the render tick wants has already been overwritten. Sixteen at one snapshot per
     * server tick is 16 ticks of history against a 6-tick delay.
     */
    public val historyPerEntity: Int = DEFAULT_HISTORY_PER_ENTITY,

    /** Two samples further apart than this are a teleport, not motion. @see EntityInterpolator */
    public val teleportDistance: Float = DEFAULT_TELEPORT_DISTANCE,
) {

    init {
        require(historyPerEntity >= 2) {
            "interpolation needs two samples to interpolate between, was $historyPerEntity"
        }
        require(teleportDistance > 0f) { "teleportDistance must be positive, was $teleportDistance" }
    }

    /**
     * Per-entity tracks, keyed by raw [NetId].
     *
     * `LinkedHashMap` and not `HashMap`: [forgetAllExcept] walks it, and iteration order that is
     * not a contract is the ordering smell the charter names even where today's use happens not
     * to care.
     */
    private val tracks = LinkedHashMap<Int, Track>()

    /** Samples accepted. */
    public var recorded: Long = 0L
        private set

    /** Samples rejected as not newer than the newest already held: duplicates and reorders. */
    public var rejected: Long = 0L
        private set

    /** Samples taken. */
    public var sampled: Long = 0L
        private set

    /** Samples that fell past the newest and were held rather than extrapolated. */
    public var starved: Long = 0L
        private set

    /** Samples that fell before the oldest held. The buffer is too short or the clock jumped. */
    public var behind: Long = 0L
        private set

    /** Segments stepped rather than interpolated because they exceeded [teleportDistance]. */
    public var teleports: Long = 0L
        private set

    /** How many entities are being tracked. */
    public val tracked: Int get() = tracks.size

    /** Records [netId]'s authoritative position at [tick]. Older or duplicate ticks are dropped. */
    public fun record(netId: NetId, tick: Tick, x: Float, y: Float) {
        val track = tracks.getOrPut(netId.raw) { Track(historyPerEntity) }
        if (!track.push(tick.value, x, y, teleportDistance)) {
            rejected++
            return
        }
        recorded++
    }

    /**
     * Writes [netId]'s interpolated position at [renderTick] into [into].
     *
     * @return false when nothing is known about [netId] yet, in which case [into] is untouched -
     *   a caller must not draw an entity at whatever the pose happened to hold.
     */
    public fun sample(netId: NetId, renderTick: Double, into: PredictedPose): Boolean {
        val track = tracks[netId.raw] ?: return false
        when (val outcome = track.sample(renderTick, into)) {
            Track.NONE -> return false
            Track.STARVED -> starved++
            Track.BEHIND -> behind++
            Track.TELEPORT -> teleports++
            Track.EXACT -> Unit
            else -> error("Track.sample returned an outcome this class does not know: $outcome")
        }
        sampled++
        return true
    }

    /** Drops [netId]'s history. Call when an entity is destroyed, or its ring outlives it. */
    public fun forget(netId: NetId): Boolean = tracks.remove(netId.raw) != null

    /**
     * Drops every track whose raw id [live] does not admit.
     *
     * The cheapest correct answer to a recycled [NetId]: a track that outlived its entity would
     * hand the *next* entity in that slot a history it never had, and the interpolation between
     * the two would be a slide across the map. `moba` recycles ids constantly - every arrow.
     */
    public fun forgetAllExcept(live: (Int) -> Boolean) {
        val iterator = tracks.keys.iterator()
        while (iterator.hasNext()) if (!live(iterator.next())) iterator.remove()
    }

    override fun toString(): String =
        "EntityInterpolator(tracked=${tracks.size}, starved=$starved, teleports=$teleports)"

    /** One entity's ring of samples, ascending by tick. */
    private class Track(capacity: Int) {

        private val ticks = LongArray(capacity)
        private val xs = FloatArray(capacity)
        private val ys = FloatArray(capacity)

        /** `jumped[i]` means the step *into* sample `i` exceeded the teleport distance. */
        private val jumped = BooleanArray(capacity)

        private var head = 0
        private var count = 0

        fun push(tick: Long, x: Float, y: Float, teleportDistance: Float): Boolean {
            if (count > 0 && tick <= ticks[index(count - 1)]) return false
            var jump = false
            if (count > 0) {
                val last = index(count - 1)
                val dx = x - xs[last]
                val dy = y - ys[last]
                jump = sqrt(dx * dx + dy * dy) > teleportDistance
            }
            if (count == ticks.size) head = (head + 1) % ticks.size else count++
            val slot = index(count - 1)
            ticks[slot] = tick
            xs[slot] = x
            ys[slot] = y
            jumped[slot] = jump
            return true
        }

        fun sample(renderTick: Double, into: PredictedPose): Int {
            if (count == 0) return NONE
            val newest = index(count - 1)
            if (renderTick >= ticks[newest].toDouble()) {
                into.set(xs[newest], ys[newest])
                return STARVED
            }
            val oldest = index(0)
            if (renderTick <= ticks[oldest].toDouble()) {
                into.set(xs[oldest], ys[oldest])
                return BEHIND
            }
            var upper = count - 1
            while (upper > 0 && ticks[index(upper - 1)].toDouble() > renderTick) upper--
            val after = index(upper)
            val before = index(upper - 1)
            if (jumped[after]) {
                // Step, do not slide: hold the earlier sample until the render clock reaches the
                // later one, so the sprite jumps once instead of walking across the level.
                into.set(xs[before], ys[before])
                return TELEPORT
            }
            val span = (ticks[after] - ticks[before]).toDouble()
            val alpha = ((renderTick - ticks[before].toDouble()) / span).toFloat()
            into.set(
                xs[before] + (xs[after] - xs[before]) * alpha,
                ys[before] + (ys[after] - ys[before]) * alpha,
            )
            return EXACT
        }

        private fun index(offset: Int): Int = (head + offset) % ticks.size

        companion object {
            const val NONE = 0
            const val EXACT = 1
            const val STARVED = 2
            const val BEHIND = 3
            const val TELEPORT = 4
        }
    }

    public companion object {

        /** @see EntityInterpolator.historyPerEntity */
        public const val DEFAULT_HISTORY_PER_ENTITY: Int = 16

        /**
         * Thirty-two world units in one server tick.
         *
         * `moba`'s fastest unit walks 0.8 a tick, so nothing that is *moving* comes near it; a
         * respawn and a recycled id both clear it easily.
         */
        public const val DEFAULT_TELEPORT_DISTANCE: Float = 32f
    }
}
