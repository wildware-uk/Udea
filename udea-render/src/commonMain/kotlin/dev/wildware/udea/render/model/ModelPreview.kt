package dev.wildware.udea.render.model

import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.spatial.AnimationClip

/**
 * What one editor Scene view shows in place of the simulated pose (issue #243): a **view setting**,
 * like the view's camera, set on `WorldViewport.modelPreview`.
 *
 * ## The seam
 *
 * This is the one place a pose other than the one `ClipPose` reads off an `Animator` reaches a
 * model, and it reaches **one view's pass only**. [ModelRenderSystem] draws every model into the
 * capturable frame from the simulation as always; then, drawing a Scene view that has a preview,
 * it shows a node of that view's own in the preview's pose and hides the simulated node from that
 * view alone (Kool's per-view draw filter). So:
 *
 * - the Game tab and `render.screenshot`, which read the capturable pass, keep the simulated pose;
 * - the world is never written: the preview is not a component, not a barrier action and not a
 *   tool call, and a `WorldHasher` hash of the world is the same with it as without it;
 * - clearing it (`modelPreview = null`) shows the simulated pose again on the next frame, because
 *   the simulated node was drawn all along and only this view's filter hid it.
 *
 * The shadow map is shared by every pass, and it keeps casting the simulated pose.
 */
public sealed interface ModelPreview {

    /**
     * [entity], where it stands, posed at [at] into [clip] rather than as its `Animator` says: a
     * scrub preview. An entity that is not drawn with an imported model shows as it is.
     */
    public data class Pose(
        val entity: NetId,
        val clip: AnimationClip,
        /** How far into [clip], clamped to it. */
        val at: Ticks,
    ) : ModelPreview

    /**
     * [model] on its own at the view's centre, turned [turnDegrees] about the up axis, scaled so
     * its largest side is three quarters of the view's height - or of its width, if that is
     * narrower - and posed at [at] into [clip], or in its bind pose when [clip] is `null`: a model
     * asset's preview. Every other model is left out of the view while it shows, bone overlay and
     * all; what other render systems draw into the view, a 2D game's sprites, is not.
     */
    public data class Asset(
        val model: ImportedModel,
        val clip: AnimationClip?,
        val at: Ticks,
        val turnDegrees: Float,
    ) : ModelPreview
}
