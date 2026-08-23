package dev.wildware.udea.render

import com.badlogic.gdx.utils.Disposable
import dev.wildware.udea.core.loop.Presentation
import dev.wildware.udea.render.capture.FrameCaptureSlot

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
 * [RenderTargets.offscreen]. That target is what `FrameCapture` (agent epic) reads, and the
 * captured image is what gets blitted to the window. **Only then** do the
 * [OverlaySystem]s draw, into [RenderTargets.screen]. So the agent activity overlay lands
 * after the point at which a frame can be captured, on a target no capture reads (spec 3.7).
 *
 * The bind and the blit are [FrameSurface]'s, injected so that this class -- the one that
 * owns the ordering -- stays drivable with no GL context behind it.
 *
 * ## Ownership
 *
 * The pipeline owns every GL resource handed to it -- the offscreen framebuffer, the one
 * `Batch`, and whatever a system claimed through [RenderResources.own] -- and [dispose]
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
     * GL resources this pipeline owns, in construction order: the framebuffer, the batch, and
     * whatever a system registered through [RenderResources.own]. Released in reverse by
     * [dispose].
     */
    private val owned: List<Disposable>,
) : Presentation {

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

        targets.surface.begin()

        // Indexed loops: this is the per-frame path and an iterator per phase per frame is
        // garbage the collector has to deal with in the middle of drawing.
        for (index in systems.indices) {
            systems[index].render(targets.offscreen, alpha)
        }

        // ---- capture point (spec 3.7) ----
        // Inside the bound region, because glReadPixels reads the *bound* framebuffer: drained
        // after endAndPresent() it would read the window, which is the surface the agent
        // activity overlay draws on two lines below.
        capture?.drain(targets.offscreen)

        targets.surface.endAndPresent()

        for (index in overlays.indices) {
            overlays[index].render(targets.screen, dtSeconds)
        }

        frameCount++
    }

    /**
     * Releases the GL resources this pipeline owns, in reverse construction order.
     *
     * Idempotent: disposing a `SpriteBatch` twice is undefined in LibGDX, and a second call
     * is far likelier to be a shutdown path running twice than a real defect, so it is a
     * no-op rather than a failure.
     */
    public fun dispose() {
        if (disposed) return
        disposed = true
        // Waiters first: a caller blocked in `capture` must be told the pipeline has gone
        // before its GL resources are released, or it waits out its whole deadline on a
        // pipeline that can no longer draw the frame it is waiting for.
        capture?.close()
        for (index in owned.indices.reversed()) {
            owned[index].dispose()
        }
    }

    /** True once [dispose] has run. [render] fails after that. */
    public val isDisposed: Boolean get() = disposed

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
