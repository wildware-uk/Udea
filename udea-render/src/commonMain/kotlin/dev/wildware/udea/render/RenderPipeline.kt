package dev.wildware.udea.render

import dev.wildware.udea.core.loop.Presentation
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewCursor
import dev.wildware.udea.render.view.WorldViewport

/**
 * One frame, in order: everything capturable, then the capture point, then the overlay.
 *
 * This is `udea-core`'s [Presentation] implemented, and it is the only thing the loop knows
 * about drawing. `GameLoop` calls `view?.render(alpha)` and has no idea whether that draws a
 * frame or does nothing, which is what makes a dedicated server, CI, the agent's `SimHarness`
 * and fast-forward run the *identical* simulation with `null` in that slot (spec 3.5).
 *
 * ## Why it is not a Fleks system list
 *
 * `GameScreen` in the old tree put every drawing system into `GameScreen.world`
 * (`common/UdeaGameManager.kt:85`), so `world.update(delta)` issued GL calls
 * (`UdeaGameManager.kt:222`) and headless was impossible. The systems here are ordinary
 * objects held in an ordinary list that this class walks. Nothing in the world's system list
 * draws, so `world.update(dt)` is pure simulation *by construction* rather than by
 * convention (spec 3.3).
 *
 * ## The order, and the capture point
 *
 * [RenderPhase.PreRender] through [RenderPhase.Debug] draw into
 * [RenderTargets.offscreen], through the batch that reaches only the capturable pass. That pass
 * is what a capture reads, and it is what gets presented on the window. **Only then** do the
 * [OverlaySystem]s draw, into [RenderTargets.screen], through a second batch that reaches only
 * the window. So the agent activity overlay lands after the point at which a frame can be
 * captured, on a target no capture reads (spec 3.7).
 *
 * Clearing the batches and presenting are [FrameSurface]'s, injected so that this class -- the
 * one that owns the ordering -- stays drivable with no render context behind it.
 *
 * ## Kool draws after this returns
 *
 * On Kool this class *records* a frame and Kool draws it afterwards. So a capture is claimed at
 * the capture point and read by [FrameCaptureSlot.collect] at the top of the next [render], before
 * that frame records anything: the pixels read are exactly the frame that claimed the request.
 *
 * ## Ownership
 *
 * The pipeline owns every render resource handed to it -- the capturable pass, both batches,
 * and whatever a system claimed through [RenderResources.own] -- and [dispose]
 * releases them in reverse construction order. In the old tree `GameScreen` constructed a batch and a shape renderer
 * (`UdeaGameManager.kt:143-144`) and then `BackgroundDrawSystem.kt:22` and
 * `DebugDrawSystem.kt:26` each built another: three batches, three lifetimes, disposal
 * wherever somebody remembered.
 *
 * Instances come from [RenderRegistry.build], which is also what guarantees the two lists
 * arrive already ordered.
 */
public class RenderPipeline internal constructor(
    private val targets: RenderTargets,
    /** Capturable systems, already in phase-then-topological order. */
    private val systems: List<RenderSystem>,
    /** Overlay systems, already ordered. Drawn after the capture point. */
    private val overlays: List<OverlaySystem>,
    private val timer: FrameTimer,
    /**
     * The capture request slot, or `null` when [RenderTargets] carries no way to read pixels.
     *
     * Nullable rather than an always-present slot that always fails, because the two answers
     * differ for the caller: `GameHost` turns `null` into `no_capture_backend`, which is a
     * wiring fault it can name, rather than a capture that times out and looks like a stall.
     */
    public val capture: FrameCaptureSlot?,
    /**
     * Render resources this pipeline owns, in construction order: the pass, the batches, and
     * whatever a system registered through [RenderResources.own]. Released in reverse by
     * [dispose].
     */
    private val owned: List<RenderResource>,
    /**
     * The systems an editor's Scene view draws again through its own camera: every capturable
     * system except the [RenderPhase.UI] phase's, in the same order. The HUD is the game's screen,
     * not its world, so the Scene tab does not show it (issue #234).
     */
    private val viewSystems: List<RenderSystem> = emptyList(),
    /** Which view the systems are drawing for, shared with every system's [RenderResources]. */
    private val cursor: ViewCursor = ViewCursor(),
) : Presentation {

    /** The editor views open on this pipeline, drawn after the capture point in opening order. */
    private val viewports = ArrayList<WorldViewport>()

    /**
     * The game's cameras, which a Scene view swaps its own projection into. Resolved once, like
     * [resizables]: a `filterIsInstance` per frame is a per-frame scan.
     */
    private val rigs: List<CameraRig> = systems.filterIsInstance<CameraRig>()

    private var disposed: Boolean = false

    /** How many frames [render] has drawn. A health signal for the agent's `/health`, not state. */
    public var frameCount: Long = 0L
        private set

    /**
     * Draws one frame.
     *
     * @param alpha how far this frame sits between the last simulated tick and the next.
     *   Passed to every [RenderSystem] untouched. Overlays never see it -- they get wall
     *   seconds instead, because an overlay must not read simulation time.
     * @throws IllegalStateException if the pipeline has been disposed. Drawing with a
     *   disposed batch is a GL crash several frames later, in another class.
     */
    override fun render(alpha: Float) {
        check(!disposed) { "RenderPipeline has been disposed and cannot draw" }
        require(alpha in 0f..1f) { "alpha must be in [0, 1], was $alpha" }

        // One clock reading per frame, taken before anything draws. A renderer that animates
        // on wall time reads it through FrameTime; an overlay is handed it below. Two readings
        // would let the world and the overlay disagree about how long the frame was.
        val dtSeconds = timer.advance()

        // Before anything records: the pass still holds the pixels of the frame that claimed a
        // capture at its capture point, and nothing below has been drawn yet.
        capture?.collect()
        for (index in viewports.indices) viewports[index].captures?.collect()

        targets.surface.begin()

        // `finally`, so a renderer that throws still leaves the window with a presented frame and
        // the screen batch cleared, rather than last frame's overlay drawn over a stale picture.
        //
        // The capture drain is deliberately *inside* the `try` and not the `finally`: a frame
        // that threw half-way through drawing is a half-drawn frame, and serving it would hand
        // an agent a picture of a partial world it would then reason about. Waiters are woken by
        // `FrameCaptureSlot.close`, which `KoolBackend` wires to the render loop's exit.
        try {
            // Indexed loops: this is the per-frame path and an iterator per phase per frame is
            // garbage the collector has to deal with in the middle of drawing.
            for (index in systems.indices) {
                systems[index].render(targets.offscreen, alpha)
            }

            // ---- capture point (spec 3.7) ----
            // Claims the requests this frame satisfies. The read is `collect`'s, next frame.
            capture?.drain(targets.offscreen)

            // ---- editor views (issue #234) ----
            // After the capture point, so nothing a view draws is in the frame a capture claimed;
            // and into passes of their own, which no capture reads. See `WorldViewport`.
            for (index in viewports.indices) renderView(viewports[index], alpha)
        } finally {
            targets.surface.endAndPresent(targets.screen)
        }

        for (index in overlays.indices) {
            overlays[index].render(targets.screen, dtSeconds)
        }

        frameCount++
    }

    /**
     * Releases the render resources this pipeline owns, in reverse construction order.
     *
     * Idempotent: releasing a GPU resource twice is undefined, and a second call is far likelier
     * to be a shutdown path running twice than a real defect, so it is a no-op rather than a
     * failure.
     */
    public fun dispose() {
        if (disposed) return
        disposed = true
        // Views first: each holds a pass on the scene the owned resources below release.
        for (index in viewports.indices.reversed()) viewports[index].close()
        // Waiters first: a caller awaiting a capture must be told the pipeline has gone before its
        // resources are released, or it waits out its whole deadline on a pipeline that can no
        // longer draw the frame it is waiting for.
        capture?.close()
        for (index in owned.indices.reversed()) {
            owned[index].release()
        }
    }

    /**
     * Starts drawing [view] every frame, after the capture point, until it is closed.
     *
     * Internal: a view is made on the render thread by the backend that owns its Kool pass
     * (`KoolBackend.openGameView`, `openSceneView`), which then hands it here.
     */
    internal fun open(view: WorldViewport) {
        check(!disposed) { "RenderPipeline has been disposed and cannot open $view" }
        viewports += view
        view.onClose { viewports.remove(view) }
    }

    /**
     * Fails every capture still waiting on this pipeline or on one of its views: the render loop
     * has gone, so no frame will ever read them. On the render thread, as its loop exits.
     */
    internal fun closeCaptures() {
        capture?.close()
        for (index in viewports.indices) viewports[index].captures?.close()
    }

    /**
     * Draws one editor view: the capturable frame again for the Game tab, the world again through
     * the editor camera for the Scene tab; then the view's gizmos; then its capture point.
     */
    private fun renderView(view: WorldViewport, alpha: Float) {
        view.begin()
        val rig = if (rigs.isEmpty()) null else rigs[0]
        val camera = view.camera
        if (camera == null) {
            view.drawFrame()
            view.drawGizmos(rig?.projection)
        } else {
            if (rig != null) camera.adopt(rig.camera, rig.viewport)
            camera.fit(view.width, view.height)
            drawWorld(view, camera, alpha)
            view.drawGizmos(null)
        }
        view.captures?.drain(view.target)
    }

    /**
     * Runs [viewSystems] into [view]'s record, with every rig's projection swapped for [camera]'s.
     *
     * The systems draw with the batch they were built with; [RenderTargets.batch] is pointed at the
     * view's record for the length of the run, and back at its own in a `finally`, so a system that
     * throws cannot leave the capturable frame's draws going into an editor view.
     */
    private fun drawWorld(view: WorldViewport, camera: EditorCamera, alpha: Float) {
        targets.batch.recordInto(view.record)
        cursor.current = view
        for (index in rigs.indices) rigs[index].enterView(camera.projection)
        try {
            for (index in viewSystems.indices) viewSystems[index].render(view.target, alpha)
        } finally {
            for (index in rigs.indices) rigs[index].leaveView()
            cursor.current = null
            targets.batch.recordInto(null)
        }
    }

    /** True once [dispose] has run. [render] fails after that. */
    public val isDisposed: Boolean get() = disposed

    /**
     * The surface the game is drawn on, and the only one a capture reads.
     *
     * Published because the size of a capture is a property of this target and of nothing else
     * -- not of the window, which [resize] deliberately does not propagate here. A caller that
     * has to validate a capture region (the agent's `render.screenshot_region`) needs the same
     * number the drain will check it against, and getting it from `Gdx.graphics` instead would
     * be reading the window and clamping to the wrong rectangle.
     */
    public val offscreen: OffscreenTarget get() = targets.offscreen

    /**
     * Tells every [Resizable] system, and [RenderTargets.screen], that the window has changed.
     *
     * @param width new window width in pixels; must be positive.
     * @param height new window height in pixels; must be positive.
     *
     * The [OffscreenTarget] is deliberately *not* resized. It is the framebuffer the game is
     * drawn into and every capture is read from, and letting a window drag change it would put
     * the window manager's opinion into every screenshot an agent diffs.
     *
     * A minimised window reports `0 x 0` on some platforms; that is not a size and is ignored,
     * because a viewport told it is zero pixels wide divides by it.
     */
    public fun resize(width: Int, height: Int) {
        check(!disposed) { "RenderPipeline has been disposed and cannot resize" }
        if (width <= 0 || height <= 0) return

        targets.screen.width = width
        targets.screen.height = height
        for (index in resizables.indices) {
            resizables[index].resize(width, height)
        }
    }

    /** Resolved once at construction: a `filterIsInstance` per resize is a per-event scan. */
    private val resizables: List<Resizable> =
        (systems.filterIsInstance<Resizable>() + overlays.filterIsInstance<Resizable>())

    public companion object {

        /**
         * Longest per-frame delta an [OverlaySystem] will be given, in seconds.
         *
         * The same figure and the same reasoning as `GameLoop.MAX_WALL_DELTA`: a breakpoint,
         * a GC pause or a laptop lid closing hands the frame a delta measured in tens of
         * seconds, and that is a stall rather than elapsed time. Without the clamp every
         * overlay animation would snap to its end state after any pause.
         */
        public const val MAX_FRAME_SECONDS: Float = 0.25f
    }
}
