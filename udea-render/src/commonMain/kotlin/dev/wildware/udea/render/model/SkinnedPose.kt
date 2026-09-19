package dev.wildware.udea.render.model

import de.fabmax.kool.scene.Model as KoolModel
import de.fabmax.kool.scene.animation.Animation
import dev.wildware.udea.core.spatial.ClipPlayback

/**
 * Poses this glTF node as [pose] says, through Kool's own clips and skin (issue #242): the one place
 * an entity's clip numbers cross into Kool, once per entity per frame.
 *
 * Every clip's weight is set - the clip playing now to [ClipPose.weight], the one it is fading from
 * to the rest, every other clip to zero - and each weighted clip is put at its time. Kool's
 * `applyAnimation` then starts every animated joint from its rest transform, blends the weighted
 * clips onto it (Kool's own blending), and updates the skin's joint matrices, which Kool's
 * armature shader skins the mesh with on the GPU. Because every joint is reset before the clips are
 * applied, the pose is a function of [pose] alone: nothing is carried from the frame before, or
 * from another entity that drew with this node before.
 *
 * - A node with no clips and no skin is left alone: a static model ignores an `Animator`.
 * - The bind pose - no clip, or a clip index the file does not have - is every weight at zero: the
 *   joints go back to rest and the skin is updated to match, so a node that an animated entity drew
 *   last frame does not keep its pose.
 * - A crossfade from a clip to itself shows the clip at its new time only: Kool has one set of
 *   joints per clip, so one clip cannot be in two places at once.
 *
 * Allocates nothing itself. (Kool's keyframe lookup boxes the time it searches for.)
 */
internal fun KoolModel.applyPose(pose: ClipPose) {
    val clips = animations
    if (clips.isEmpty() && skins.isEmpty()) return
    for (index in clips.indices) clips[index].weight = 0f
    if (pose.previous == ClipPlayback.NO_CLIP || pose.previous == pose.current) {
        weigh(clips, pose.current, pose.currentSeconds, 1f)
    } else {
        weigh(clips, pose.current, pose.currentSeconds, pose.weight)
        weigh(clips, pose.previous, pose.previousSeconds, 1f - pose.weight)
    }
    // No time passes inside Kool: the clips are where `weigh` put them.
    applyAnimation(0f)
}

private fun weigh(clips: List<Animation>, clip: Int, seconds: Float, weight: Float) {
    if (clip !in clips.indices || weight <= 0f) return
    val animation = clips[clip]
    animation.weight = weight
    // Kool wraps a clip's time at its last keyframe, so a clip at its very end would be drawn at its
    // first frame. A clip's length in ticks is its keyframes rounded up to a whole tick, so the end
    // of a clip held by `Loop.Once` - and the sliver past the last keyframe before a repeating clip
    // wraps - is drawn as the last keyframe instead.
    animation.progress = seconds.coerceIn(0f, maxOf(0f, animation.duration - END_MARGIN_SECONDS))
}

/**
 * How far short of a clip's last keyframe its end is drawn. Kool computes a clip's time as
 * `(time + duration) % duration` in `Float`; this is well clear of the rounding that would turn a
 * time a hair under `duration` into zero, and a ten-thousandth of a second is far below one frame.
 */
private const val END_MARGIN_SECONDS = 1e-4f
