package dev.wildware.udea.render.view

/**
 * Which editor view the render systems are drawing for right now: `null` while they draw the
 * capturable frame, the Scene view while the pipeline runs them again for it (issue #234).
 *
 * Read by the one system that cannot follow a Scene view through `CameraRig` alone: the 3D model
 * system, whose picture comes from a Kool pass with a camera of its own, so a Scene view needs a pass
 * of its own too. One per pipeline, handed to every system through `RenderResources`. Render thread.
 */
internal class ViewCursor {

    /** The Scene view being drawn, or `null` for the capturable frame. */
    var current: WorldViewport? = null

    override fun toString(): String = "ViewCursor($current)"
}
