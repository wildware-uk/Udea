package dev.wildware.udea.render.draw

/**
 * A sprite animation: frames shown for a fixed number of seconds each, once or on a loop.
 *
 * LibGDX's `Animation<TextureRegion>` with the two play modes a 2D game here used, `NORMAL` and
 * `LOOP`, and the same frame arithmetic, so a clip timed on the old engine plays the same.
 * Seconds are legitimate: this is presentation, and a clip's playhead never enters a snapshot.
 */
public class SpriteClip(
    /** The frames, in play order. */
    public val frames: List<SpriteRegion>,
    /** Seconds each frame is shown. */
    public val frameSeconds: Float,
    /** Starts over after the last frame instead of holding it. */
    public val looping: Boolean = false,
) {

    init {
        require(frames.isNotEmpty()) { "a clip needs at least one frame" }
        require(frameSeconds > 0f && frameSeconds.isFinite()) {
            "frameSeconds must be a positive number of seconds, was $frameSeconds"
        }
    }

    /** Seconds from the first frame to the end of the last. */
    public val durationSeconds: Float get() = frames.size * frameSeconds

    /** Index of the frame showing [stateTime] seconds in. */
    public fun frameIndexAt(stateTime: Float): Int {
        if (frames.size == 1 || stateTime <= 0f) return 0
        val index = (stateTime / frameSeconds).toInt()
        return if (looping) index % frames.size else minOf(index, frames.size - 1)
    }

    /** The frame showing [stateTime] seconds in. */
    public fun frameAt(stateTime: Float): SpriteRegion = frames[frameIndexAt(stateTime)]

    /** True once a non-looping clip has shown its last frame for its full time. Never for a loop. */
    public fun isFinished(stateTime: Float): Boolean =
        !looping && (stateTime / frameSeconds).toInt() >= frames.size

    override fun toString(): String =
        "SpriteClip(${frames.size} frame(s) at ${frameSeconds}s${if (looping) ", looping" else ""})"
}
