package dev.wildware.udea.render

/**
 * The two edges of a frame: before the first [RenderSystem] records, and after the capture point.
 *
 * ## Why the pipeline does not do this itself
 *
 * [RenderPipeline] is the one class that has to be provable without a render context: every
 * ordering claim this module makes -- phases, the capture point, overlays after it -- is asserted
 * in a plain JVM by tests that drive fakes. So the two moments a backend has work to do are named
 * here and injected; a test passes a recording stand-in and asserts the *order* of the calls.
 *
 * ## The order the two halves impose
 *
 * ```
 * begin()                  <- forget the last frame's capturable draws
 *   RenderSystems draw into OffscreenTarget
 *   captures claimed                           <- capture point
 * endAndPresent(screen)    <- forget the last frame's overlay; present the capturable frame
 *   OverlaySystems draw into ScreenTarget
 * ```
 *
 * On Kool (`KoolSurface`) the capturable draws and the overlay draws go to two different batches
 * reaching two different render targets, so the capture point is where a frame is *chosen* for a
 * capture, and the read is `FrameCaptureSlot.collect`'s, at the top of the next frame.
 */
public interface FrameSurface {

    /** Called before the first [RenderSystem] draws. */
    public fun begin()

    /**
     * Called after the capture point and before any [OverlaySystem] draws, with the window the
     * frame is presented on.
     */
    public fun endAndPresent(screen: ScreenTarget)

    /**
     * Makes the capturable frame [width] x [height] pixels from this frame on. Called by the pipeline
     * between frames, before [begin], and only when an editor's Game tab has asked for its own size
     * (issue #234).
     */
    public fun resize(width: Int, height: Int)

    public companion object {

        /**
         * A surface that does nothing.
         *
         * For the tests where a [RenderPipeline] exists but there is no render context behind it,
         * and *only* those.
         */
        public val None: FrameSurface = object : FrameSurface {
            override fun begin(): Unit = Unit
            override fun endAndPresent(screen: ScreenTarget): Unit = Unit
            override fun resize(width: Int, height: Int): Unit = Unit
            override fun toString(): String = "FrameSurface.None"
        }
    }
}
