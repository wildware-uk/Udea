package dev.wildware.udea.audio

import dev.wildware.udea.core.identity.NetId

/**
 * Where the ear is, and how quickly the world goes quiet away from it.
 *
 * ## Why this is mutable, and why that is not the smell it looks like
 *
 * Standards section 1 bans top-level `var` and mutable singletons; this is neither. It is a
 * per-`CueAudio` object the presentation layer writes once a frame - the camera moves, so the ear
 * moves - and reading it costs no allocation. The old code did the same job by reading
 * `gameScreen.camera.position` off a file-level `lateinit var` from inside a Fleks system, which
 * is exactly the arrangement that made two worlds in one JVM impossible.
 *
 * Seconds and world units, not ticks: this is presentation, which spec 5 says is where those are
 * allowed to live.
 */
public class AudioListener(
    /** Ear position, world x. */
    public var x: Float = 0F,
    /** Ear position, world y. */
    public var y: Float = 0F,
    /**
     * World distance at which a sound reaches silence.
     *
     * The old `SoundSystem.audioFalloff` was `10F` with no stated basis, in a game whose units
     * were forty times smaller than `moba`'s. A caller sets this from its own world scale rather
     * than inheriting a number from a different game.
     */
    public var falloff: Float = DEFAULT_FALLOFF,
    /**
     * World distance either side of the ear that maps to full stereo pan.
     *
     * `SoundSystem` divided by a literal `10F` here too, and it was a *different* ten from the
     * falloff ten - they only looked like one constant.
     */
    public var panWidth: Float = DEFAULT_PAN_WIDTH,
) {

    /** Moves the ear. One call rather than two assignments, so a caller cannot set half of it. */
    public fun moveTo(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    /**
     * Gain for something [distance] world units away, in `0f..1f`.
     *
     * Linear rather than inverse-square. That is a deliberate game-audio choice and not an
     * approximation of physics: inverse-square never reaches zero, so every sound in the level
     * stays faintly audible and the mixer spends voices on them.
     */
    public fun attenuationAt(distance: Float): Float {
        if (falloff <= 0F) return if (distance <= 0F) 1F else 0F
        return (1F - distance / falloff).coerceIn(0F, 1F)
    }

    /** Stereo pan for something [dx] world units to the ear's right, in `-1f..1f`. */
    public fun panAt(dx: Float): Float {
        if (panWidth <= 0F) return 0F
        return (dx / panWidth).coerceIn(-1F, 1F)
    }

    override fun toString(): String = "AudioListener(($x, $y), falloff=$falloff)"

    public companion object {
        /** A sound is inaudible about ten world units out, until a game says otherwise. */
        public const val DEFAULT_FALLOFF: Float = 10F

        /** Full pan about five world units off centre, until a game says otherwise. */
        public const val DEFAULT_PAN_WIDTH: Float = 5F
    }
}

/**
 * Turns a cue's `source` into a position, or says it has none.
 *
 * The simulation cannot put a position in a `Cue`: `Cue` carries an id, a `Tick` and a `NetId`,
 * and widening it so the audio layer could skip a lookup would put a presentation concern into a
 * kernel type. So the audio layer does the lookup, through this, against whatever the game keeps
 * positions in.
 *
 * ## Why `out: FloatArray` rather than a returned pair
 *
 * This is called once per drained cue. A returned `Vector2`, `Pair` or nullable `Float` would
 * allocate per hit in a fight where dozens land on one tick; the caller owns one two-element array
 * for the life of the mixer and this writes into it.
 */
public interface CueSourceLocator {

    /**
     * Writes [source]'s world position into [out] - x at index 0, y at index 1.
     *
     * @return `false` when [source] names nothing that has a position, in which case [out] is
     *   untouched and the caller places the sound at the listener. A cue whose source has already
     *   died between the tick that emitted it and the frame that plays it is the ordinary case,
     *   not a failure: `DEATH` is emitted by the system that removes the entity.
     */
    public fun locate(source: NetId, out: FloatArray): Boolean

    /**
     * Locates nothing, so every cue plays at the ear: full volume, centre pan.
     *
     * The right behaviour for a game with no positions and for a UI-only cue set, and what a
     * headless mixer uses - where the alternative would be resolving positions nobody hears.
     */
    public object Unlocated : CueSourceLocator {
        override fun locate(source: NetId, out: FloatArray): Boolean = false

        override fun toString(): String = "CueSourceLocator.Unlocated"
    }
}
