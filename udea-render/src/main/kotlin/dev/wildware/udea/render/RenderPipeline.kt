package dev.wildware.udea.render

import dev.wildware.udea.core.loop.Presentation

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
 * The blit and the capture themselves belong to later issues; the ordering they depend on is
 * here, tested, and the phase enum is what encodes it.
 *
 * ## Ownership
 *
 * The pipeline owns the GL resources handed to it in [RenderTargets.owned] -- the one
 * `SpriteBatch`, the one `ShapeRenderer` -- and [dispose] releases them in reverse
 * construction order. In the old tree `GameScreen` constructed a batch and a shape renderer
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

        // Indexed loops: this is the per-frame path and an iterator per phase per frame is
        // garbage the collector has to deal with in the middle of drawing.
        for (index in systems.indices) {
            systems[index].render(targets.offscreen, alpha)
        }

        // ---- capture point: a FrameCapture reads targets.offscreen here (spec 3.7) ----

        val dtSeconds = timer.nextFrameSeconds()
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
        for (index in targets.owned.indices.reversed()) {
            targets.owned[index].dispose()
        }
    }

    /** True once [dispose] has run. [render] fails after that. */
    public val isDisposed: Boolean get() = disposed

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
