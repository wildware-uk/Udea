package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.OffscreenPass
import de.fabmax.kool.pipeline.RenderPass

/**
 * Lets a render system add a pass of its own that the capturable pass draws from.
 *
 * The 3D model renderer draws its world into a pass with a depth buffer and a perspective camera,
 * and the result is drawn into the capturable 2D pass as one image. Kool draws a scene's passes in
 * dependency order, so that pass has to be on the scene *and* be a dependency of the capturable
 * pass, or the capture would show the previous frame's 3D. Only [KoolSurface] can do both, so it
 * is the implementation, and a pipeline with no Kool surface behind it has none.
 *
 * Internal: Kool types never leave `udea-render`.
 */
internal interface ScenePasses {

    /** Puts [pass] on the scene, drawn before the capturable pass reads it. Render thread only. */
    fun addBeforeCapture(pass: OffscreenPass)

    /**
     * Puts [pass] on the scene and **not** before the capturable pass: an editor view's own 3D pass
     * (issue #234), which no capture may wait on or read. Render thread only.
     */
    fun addBeside(pass: OffscreenPass)

    /** Takes [pass] off the scene. It is the caller's to release. Render thread only. */
    fun remove(pass: OffscreenPass)

    /**
     * Adds a view to the capturable pass itself, after the one every `RenderSystem` draws into, and
     * runs [draw] each frame when Kool reaches it: with the pass's framebuffer bound and everything
     * the batch drew already in it. So what [draw] draws is on top of the frame, and in its capture.
     *
     * The view draws no nodes of its own. Kool's `createView` shares the default view's draw node,
     * which would draw the whole batch a second time; the implementation gives it an empty one.
     *
     * The door `CapturedUi` goes through: a ComposeGL frame draws into "whatever framebuffer the
     * host has bound", and here that is the pass. Render thread only.
     */
    fun addOnTop(name: String, draw: () -> Unit): RenderPass.View

    /** Takes a view [addOnTop] made off the pass, and releases its empty node. Render thread only. */
    fun removeOnTop(view: RenderPass.View)
}
