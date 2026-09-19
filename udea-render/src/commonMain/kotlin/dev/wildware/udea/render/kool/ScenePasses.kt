package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.OffscreenPass

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

    /** Takes [pass] off the scene. It is the caller's to release. Render thread only. */
    fun remove(pass: OffscreenPass)
}
