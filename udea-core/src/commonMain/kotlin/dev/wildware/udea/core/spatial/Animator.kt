package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
import kotlinx.serialization.Serializable
import kotlin.math.floor

/**
 * One clip playing on a clock: which clip, from which tick, how fast, and what happens at its end.
 *
 * Five plain fields and no time at all. Where the clip *is* is never stored: [clipTime] derives it
 * from the tick asked about, so there is nothing to accumulate, nothing to drift, and nothing a
 * rewind or a late-joining client has to catch up on. The fields are public `var`s because
 * `Replicator.apply` restores them in place (the composite lowering of `docs/contracts/replicator.md`);
 * game code changes them through [Animator.play] and [Animator.crossfade], which check them.
 */
@Serializable
public class ClipPlayback(
    /** The clip's [AnimationClip.index], or [NO_CLIP]. */
    public var clip: Int = NO_CLIP,
    /** The clip's [AnimationClip.length] in ticks, carried so a client needs no asset to loop it. */
    public var length: Long = 0L,
    /** The tick the clip's first frame was on. */
    public var start: Tick = Tick.ZERO,
    /** Clip ticks per simulation tick. `1` is the clip as authored. */
    public var speed: Float = 1f,
    public var loop: Loop = Loop.Repeat,
) {

    /** True when no clip has been put here. */
    public fun isEmpty(): Boolean = clip == NO_CLIP

    /** True when this is playing [clip], compared by the clip's identity, its index. */
    public fun holds(clip: AnimationClip): Boolean = this.clip == clip.index

    /** How far into the clip [now] is. See [Animator.clipTime]. */
    public fun clipTime(now: Tick): Ticks {
        if (isEmpty() || length == 0L) return Ticks(0L)
        val scaled = scaledElapsed(now)
        return Ticks(
            when (loop) {
                Loop.Repeat -> scaled % length
                Loop.Once -> minOf(scaled, length)
            },
        )
    }

    /** Whether a [Loop.Once] clip has reached its end by [now]. See [Animator.isFinished]. */
    public fun isFinished(now: Tick): Boolean =
        !isEmpty() && loop == Loop.Once && (length == 0L || scaledElapsed(now) >= length)

    /**
     * `(now - start) * speed`, rounded down, and never below zero.
     *
     * ## Why it is the same number on every machine
     *
     * The product is taken in `Double`: the elapsed count converted exactly, times [speed]
     * widened exactly, which is one IEEE-754 multiplication and one floor - both defined to the
     * last bit on every platform Udea targets. It is **exact**, not merely reproducible, while the
     * elapsed count stays under 2^29 ticks (about 103 days at 60Hz): a `Float` speed has a 24-bit
     * significand, the elapsed count has at most 29 bits, and their product fits the 53 bits a
     * `Double` holds, so there is no rounding step for two platforms to take differently.
     * `ClipTimeExactnessTest` checks the result against integer arithmetic.
     */
    private fun scaledElapsed(now: Tick): Long {
        val elapsed = now.ticksSince(start)
        if (elapsed <= 0L) return 0L
        return floor(elapsed.toDouble() * speed.toDouble()).toLong()
    }

    internal fun set(clip: AnimationClip, now: Tick, loop: Loop, speed: Float) {
        require(speed >= 0f && speed.isFinite()) {
            "clip '${clip.name}' asked for speed $speed; a speed is a finite multiplier, zero or more"
        }
        this.clip = clip.index
        this.length = clip.length.count
        this.start = now
        this.speed = speed
        this.loop = loop
    }

    internal fun copyFrom(other: ClipPlayback) {
        clip = other.clip
        length = other.length
        start = other.start
        speed = other.speed
        loop = other.loop
    }

    internal fun clear() {
        copyFrom(EMPTY)
    }

    override fun toString(): String =
        if (isEmpty()) "ClipPlayback(none)" else "ClipPlayback(#$clip len=$length from $start x$speed $loop)"

    public companion object {
        /** [clip] when nothing is playing. Never a real index, which starts at zero. */
        public const val NO_CLIP: Int = -1

        private val EMPTY = ClipPlayback()
    }
}

/**
 * Which animation an entity is playing, measured in ticks (issue #241).
 *
 * This is the simulation's half of an animated model. It says *which* clip and *where in it*, and
 * it says so identically on a server, on every client and in a replay, because everything it
 * holds is a [Tick] or a number and every answer it gives is a pure function of those and the
 * tick asked about. The pose - bones, matrices, seconds, the interpolation alpha - is the
 * renderer's, and none of it is here.
 *
 * ```
 * animator.play(Fox.Clips.Walk, now)
 * animator.crossfade(Fox.Clips.Run, now, over = 6.ticks)
 * animator.play(Fox.Clips.Survey, now, loop = Loop.Once, speed = 1.5f)
 * if (animator.isFinished(now)) ...
 * ```
 *
 * `now` is an argument, not something the component reads: a component has no clock, and a
 * system that changes an animation already has the tick it is running.
 *
 * ## A crossfade
 *
 * [crossfade] moves the clip that was playing into [previous], untouched, so it keeps its own
 * start and speed and carries on moving while it fades out; [blendWeight] says how much of
 * [current] to show. Nothing is cleared when the fade ends: a weight of `1` means [previous] is
 * not drawn, and leaving it in place costs nothing and changes nothing.
 *
 * ## Replicated
 *
 * Every field is `@Net`, so a client plays the same clip from the same tick as the server, and a
 * snapshot and a rewind carry it. The two [ClipPlayback]s lower to `current.clip`,
 * `current.length`, ... and `previous.*`, one field each.
 */
@Serializable
@Replicated
public class Animator(
    /** The clip playing now. */
    @Net public val current: ClipPlayback = ClipPlayback(),
    /** The clip [current] is fading in over, or empty. */
    @Net public val previous: ClipPlayback = ClipPlayback(),
    /** The tick the last crossfade began on. */
    @Net public var fadeStart: Tick = Tick.ZERO,
    /** How many ticks the last crossfade lasts; `0` when there is no fade. */
    @Net public var fadeLength: Long = 0L,
) : Component<Animator> {

    /**
     * Plays [clip] from [now], with no blend: whatever was playing stops on this tick.
     *
     * @param loop [Loop.Repeat] unless asked: a glTF file says nothing about looping, so the
     *   default is the one most clips are authored for.
     * @param speed clip ticks per simulation tick; `0` freezes the clip on its first frame.
     * @throws IllegalArgumentException if [speed] is negative, infinite or not a number.
     */
    public fun play(clip: AnimationClip, now: Tick, loop: Loop = Loop.Repeat, speed: Float = 1f) {
        current.set(clip, now, loop, speed)
        previous.clear()
        fadeStart = now
        fadeLength = 0L
    }

    /**
     * Plays [clip] from [now], blending it in over [over] from whatever was playing.
     *
     * With nothing playing this is [play]. A crossfade begun during another one blends from the
     * clip that was fading in; the one fading out is dropped on this tick.
     *
     * @throws IllegalArgumentException if [over] is negative, or [speed] is as [play] refuses.
     */
    public fun crossfade(
        clip: AnimationClip,
        now: Tick,
        over: Ticks,
        loop: Loop = Loop.Repeat,
        speed: Float = 1f,
    ) {
        require(over.count >= 0L) { "a crossfade to '${clip.name}' over $over; a fade cannot be negative" }
        previous.copyFrom(current)
        current.set(clip, now, loop, speed)
        fadeStart = now
        fadeLength = over.count
    }

    /** True when [clip] is the clip playing now. */
    public fun isPlaying(clip: AnimationClip): Boolean = current.holds(clip)

    /**
     * How far into the current clip [now] is: `(now - start) * speed`, rounded down, then wrapped
     * ([Loop.Repeat]) or held at the clip's length ([Loop.Once]). Zero before the clip's start,
     * with nothing playing, and for a clip of no length.
     */
    public fun clipTime(now: Tick): Ticks = current.clipTime(now)

    /**
     * True from the first tick a [Loop.Once] clip's time reaches its length. Never true for a
     * [Loop.Repeat] clip, nor with nothing playing.
     */
    public fun isFinished(now: Tick): Boolean = current.isFinished(now)

    /**
     * How much of [current] to show at [now], from `0` on the tick a crossfade begins to `1` on
     * the tick it ends; `1` with no fade, and with nothing to fade from.
     */
    public fun blendWeight(now: Tick): Float {
        if (fadeLength <= 0L || previous.isEmpty()) return 1f
        val elapsed = now.ticksSince(fadeStart)
        return when {
            elapsed <= 0L -> 0f
            elapsed >= fadeLength -> 1f
            // One IEEE division of two exactly-represented integers (a fade is far shorter than
            // 2^24 ticks), so every platform rounds it to the same float.
            else -> elapsed.toFloat() / fadeLength.toFloat()
        }
    }

    override fun type(): ComponentType<Animator> = Animator

    override fun toString(): String = "Animator($current, fading from $previous over $fadeLength from $fadeStart)"

    public companion object : ComponentType<Animator>() {

        /**
         * The snapshot registration for a game's `ComponentRegistry`, which is what makes a
         * snapshot, a rewind, a replay hash and the network's state fold see an [Animator] at all:
         * capture walks the registry, and a component left out of it is invisible rather than
         * partly captured. Built fresh per call, like `PhysicsSnapshotTypes.all()`.
         *
         * The kinds are in the generated replicator's order, the lowered names sorted:
         * `current.clip`, `current.length`, `current.loop`, `current.speed`, `current.start`,
         * `fadeLength`, `fadeStart`, then the five `previous.*` in the order of the first five.
         * `ComponentSchema.of` refuses a list of the wrong length; a kind typed wrong at the right
         * length is caught by `AnimatorSnapshotTypeTest`'s round trip.
         */
        public fun snapshotType(): ReplicatedComponentType<Animator> = fleksComponentType(
            AnimatorReplicator,
            ComponentSchema.of(AnimatorReplicator, "Animator", PLAYBACK_KINDS + FADE_KINDS + PLAYBACK_KINDS),
            Animator,
        ) { Animator() }

        /** One lowered [ClipPlayback]: `clip`, `length`, `loop`, `speed`, `start`. */
        private val PLAYBACK_KINDS: List<FieldKind> =
            listOf(FieldKind.Int, FieldKind.Long, FieldKind.Int, FieldKind.Float, FieldKind.Tick)

        /** `fadeLength`, `fadeStart`. */
        private val FADE_KINDS: List<FieldKind> = listOf(FieldKind.Long, FieldKind.Tick)
    }
}
