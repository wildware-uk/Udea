package dev.wildware.udea.core.spatial

import dev.wildware.udea.core.Ticks

/**
 * One animation in a model file, as gameplay code names it: `Fox.Clips.Run` (issue #241).
 *
 * A game does not write these. The asset build reads each `model(...)`'s glTF file and generates
 * one per animation in it, so a clip that is not in the file is a name that does not compile,
 * and nothing ever looks a clip up by its name at run time.
 *
 * @property index the animation's position in the file's `animations` array. This is the clip's
 *   identity: it is what an [Animator] stores, what goes on the wire, and what the renderer uses
 *   to find the animation to sample. Two clips of one model never share an index.
 * @property name the name the file gives the animation, for people and tools. Not on the wire.
 * @property length how long the clip runs at speed 1, in whole ticks. The asset build converts
 *   the file's seconds by rounding **up** at 60Hz, so a clip played once is never declared
 *   finished before its last keyframe has been reached; see the asset compiler's `GltfClips`.
 */
public data class AnimationClip(
    public val index: Int,
    public val name: String,
    public val length: Ticks,
) {
    init {
        require(index >= 0) { "clip '$name' has index $index; a clip index is a position in a list" }
        require(length.count >= 0) { "clip '$name' has length $length; a clip cannot run backwards" }
    }
}

/** What a clip does when it reaches its end. */
public enum class Loop {
    /** Stop on the last tick, and report [Animator.isFinished]. */
    Once,

    /** Start again from tick zero, for ever. */
    Repeat,
}
