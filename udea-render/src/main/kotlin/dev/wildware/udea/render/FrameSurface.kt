package dev.wildware.udea.render

/**
 * How a frame is bound before drawing, and how it reaches the window afterwards.
 *
 * ## Why the pipeline does not do this itself
 *
 * [RenderPipeline] is the one class that has to be provable without a GL context: every
 * ordering claim this module makes -- phases, the capture point, overlays after it -- is
 * asserted in a plain JVM by tests that drive fakes. If the pipeline called
 * `FrameBuffer.begin()` directly, none of those tests could run, and an ordering rule nobody
 * can run in CI is a comment.
 *
 * So the two GL moments in a frame are named here and injected. The real implementation lives
 * with the LWJGL3 backend, which is the only thing that knows a context exists; a test passes
 * a recording stand-in and asserts the *order* of the calls, which is the part that is a
 * correctness requirement (spec 3.7).
 *
 * ## The order the two halves impose
 *
 * ```
 * begin()              <- bind the offscreen framebuffer, clear it
 *   RenderSystems draw into OffscreenTarget
 *   FrameCapture reads the bound framebuffer      <- capture point
 * endAndPresent()      <- unbind, blit the offscreen colour to the window
 *   OverlaySystems draw into ScreenTarget
 * ```
 *
 * The capture sits *inside* the bound region on purpose: `glReadPixels` reads whichever
 * framebuffer is currently bound, so a capture drained after [endAndPresent] would read the
 * window -- which is the surface the overlay is about to draw on, and the one spec 3.7 says an
 * agent must never see.
 */
public interface FrameSurface {

    /**
     * Binds the offscreen surface and clears it, before the first [RenderSystem] draws.
     */
    public fun begin()

    /**
     * Unbinds the offscreen surface and blits its colour attachment to the window.
     *
     * Called after the capture point and before any [OverlaySystem] draws, so an overlay
     * lands on top of the presented frame without ever being part of it.
     */
    public fun endAndPresent()

    public companion object {

        /**
         * A surface that binds nothing.
         *
         * For the modes and tests where a [RenderPipeline] exists but there is no framebuffer
         * to bind -- and *only* those. It is not a fallback for a missing backend: a
         * production pipeline built with this would draw every frame straight onto the
         * window, which is precisely the arrangement that lets an overlay reach a capture.
         */
        public val None: FrameSurface = object : FrameSurface {
            override fun begin(): Unit = Unit
            override fun endAndPresent(): Unit = Unit
            override fun toString(): String = "FrameSurface.None"
        }
    }
}
