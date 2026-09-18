package dev.wildware.udea.render.backend

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.PresentationBackend
import dev.wildware.udea.core.host.PresentationFactory
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGame
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.capture.BlockingFrameCapture
import dev.wildware.udea.render.kool.KoolSurface
import dev.wildware.udea.render.ui.UiLayer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The `Offscreen` and `Windowed` presentation backends: a real Kool context on OpenGL, either way.
 *
 * ## The one difference between the two modes
 *
 * Whether the window is shown. Both modes create a real context, the identical capturable pass and
 * the identical [RenderPipeline]; both capture; both run the identical `Simulation`, because the
 * simulation does not know this class exists. There is no `if (offscreen)` anywhere below, and
 * there must never be one: the moment a renderer branches on the mode, "it looks wrong in the
 * agent's screenshots but right on screen" becomes a class of bug the engine can have.
 *
 * `RenderMode.Headless` is not served here and cannot be. It is `udea-core`'s path - no context, no
 * pipeline, `no_render_context` from `GameHost.screenshot` - and asking for it here is a wiring
 * mistake worth failing on rather than quietly booting a window nobody asked for.
 *
 * ## How it fits `GameHost`
 *
 * ```
 * val backend = KoolBackend.start(RenderMode.Offscreen, WindowConfig(), registry)
 * val host = GameHost(RenderMode.Offscreen, definition, backend)   // PresentationFactory
 * backend.drive(host)                                              // frames start flowing
 * ...
 * backend.close()
 * ```
 *
 * The two-step - construct, *then* drive - is forced by a genuine cycle rather than a preference: a
 * `GameHost` builds its presentation inside its own constructor, so the presentation cannot be
 * handed the host it does not yet have. [KoolThread] models the gap as a state with defined
 * behaviour (serve render tasks, draw nothing).
 */
public class KoolBackend private constructor(
    /** The mode this backend was started for. Reported by `/health` through `GameHost.mode`. */
    public val mode: RenderMode,
    private val window: WindowConfig,
    private val registry: RenderRegistry,
    private val kool: KoolThread,
) : PresentationFactory, AutoCloseable {

    private val built = AtomicReference<RenderPipeline?>(null)

    /**
     * Claimed by [create] **before** it allocates anything, and never cleared.
     *
     * Separate from [built] because [built] is cleared by [close], and "has this backend ever
     * created a pipeline" and "does it hold one now" are different questions: a second [create] has
     * to be refused in both states, and refused before it re-runs `registry.build` - which calls
     * `onBind(world, ctx)` again on the *same retained system instances* and would replace the live
     * pipeline's bound Families on its way to throwing.
     */
    private val claimed = AtomicBoolean(false)

    /** The interface [show] was given, which [close] then owns. `null` until it is. */
    private val shown = AtomicReference<UiLayer?>(null)

    /** The pipeline, once [create] has run. `null` before that. */
    public val pipeline: RenderPipeline? get() = built.get()

    /**
     * Builds the pipeline and its Kool scene **on the render thread**, and puts the scene on the
     * context.
     *
     * @throws IllegalStateException if called more than once. One host, one pipeline.
     */
    override fun create(game: UdeaGame): PresentationBackend {
        check(claimed.compareAndSet(false, true)) {
            "$this already built a pipeline; one backend serves one GameHost"
        }
        val pipeline = kool.submit {
            val surface = KoolSurface(
                width = window.renderWidth,
                height = window.renderHeight,
                windowWidth = window.windowWidth,
                windowHeight = window.windowHeight,
            )
            val pipeline = registry.build(game.world, game.ctx, surface.targets())
            surface.attach(kool.ctx)
            pipeline
        }
        built.set(pipeline)
        kool.onResize { width, height -> pipeline.resize(width, height) }
        // If the render loop dies - a renderer threw, the window was closed, the driver went away -
        // every queued capture must be told, or each one waits out its full deadline and reports a
        // timeout for something that was an exception seconds earlier.
        kool.onShutdown { pipeline.capture?.close() }

        return PresentationBackend(pipeline, pipeline.capture?.let(::BlockingFrameCapture))
    }

    /**
     * Hands the frame cadence to the render thread, which drives [host] from here on.
     *
     * Until this is called the context is up and serving render work but nothing is simulated and
     * nothing is drawn - which is the correct behaviour for the window between [start] and the host
     * existing, not a degraded mode.
     */
    public fun drive(host: GameHost) {
        kool.driveWith(host::frame)
    }

    /**
     * Hands the frame cadence to [frame], which is called on the render thread with the wall delta,
     * once per frame, instead of [GameHost.frame].
     *
     * The overload exists for one caller and it is worth naming: an agent host has to bracket every
     * frame with `AgentRuntime.beforeFrame()` and `afterFrame(...)`, or commands reach the bridge
     * queue and are never dispatched.
     *
     * @param frame called with real seconds since the previous frame. It must not block: it is the
     *   render loop.
     */
    public fun drive(frame: (Float) -> Unit) {
        kool.driveWith(frame)
    }

    /**
     * Puts [ui]'s screens on the context, over everything the game draws, and takes ownership.
     *
     * The door a game goes through to have an interface at all (issue #224). Here rather than on
     * [UiLayer] itself for two reasons: attaching is render-thread work, which only this class can
     * submit; and a `KoolContext` is a Kool type, which `UDEA-MG-002` keeps out of every game's
     * hands, so a game that had to name one to show a menu could not show a menu.
     *
     * Owned from here on: [close] detaches and closes it, on the render thread, before the pipeline
     * goes. A second call is refused - one backend draws one interface, the same way it builds one
     * pipeline - because two layers would both be "the top scene" and the second would silently win.
     *
     * @throws IllegalStateException if an interface has been shown already.
     * @throws GlContextException if the render thread is gone.
     */
    public fun show(ui: UiLayer) {
        check(shown.compareAndSet(null, ui)) { "$this is already showing $shown" }
        kool.submit { ui.attach(kool.ctx) }
    }

    /**
     * Runs [block] on the render thread and returns its result.
     *
     * The door for the render work a host legitimately has outside a frame: making a texture,
     * reading back a buffer.
     *
     * @throws GlContextException if the render thread is gone or stops before reaching [block].
     * @throws IllegalStateException if the backend has been closed.
     */
    public fun <T> onRenderThread(block: () -> T): T = kool.submit(block)

    /** Blocks until the render loop exits, whether from [close] or the window being closed. */
    public fun awaitExit() {
        kool.awaitExit()
    }

    /**
     * Whether the render loop is still running, asked without waiting for anything.
     *
     * Internal, for `OffscreenBackendTest`, which has to be able to say "the loop stopped" as a fact
     * rather than as a consequence of some later call throwing.
     */
    internal val renderLoopRunning: Boolean get() = kool.isRunning

    /**
     * Disposes the pipeline on the render thread, then stops the context.
     *
     * In that order: `dispose` releases render objects and must happen where they were made, and it
     * also fails every queued capture so no agent thread is left waiting on a loop that has gone.
     * A pipeline whose loop is already gone is not disposed - its objects went with the context -
     * but its captures are still failed, by the shutdown hook [create] installed.
     */
    override fun close() {
        val ui = shown.getAndSet(null)
        if (ui != null && kool.isRunning) {
            // Before the pipeline: the interface's scene sits on the context above the pipeline's,
            // and a scene left listed after its canvas has gone is a scene Kool still tries to draw.
            try {
                kool.submit { ui.detachAndClose() }
            } catch (stopped: GlContextException) {
                System.err.println("udea-render: interface not closed, the render loop had stopped: ${stopped.message}")
            }
        }
        val pipeline = built.getAndSet(null)
        if (pipeline != null && kool.isRunning) {
            try {
                kool.submit { pipeline.dispose() }
            } catch (stopped: GlContextException) {
                // The loop ended between the check and the task: the context's objects went with
                // it, and the shutdown hook has failed the captures. Stopping is still correct.
                System.err.println("udea-render: pipeline not disposed, the render loop had stopped: ${stopped.message}")
            }
        }
        kool.stop()
    }

    override fun toString(): String = "KoolBackend($mode, $window)"

    public companion object {

        /**
         * Boots a context and returns once it is drawing frames.
         *
         * @param mode [RenderMode.Offscreen] for a hidden window, [RenderMode.Windowed] for a
         *   visible one.
         * @throws IllegalArgumentException for [RenderMode.Headless], which has no context by
         *   definition and is served by `udea-core` alone.
         * @throws GlContextException if no context could be created - no display, no driver, no
         *   natives. Loud, because the alternative is a host that draws into nothing and hands an
         *   agent a black picture it will believe.
         */
        public fun start(
            mode: RenderMode,
            window: WindowConfig,
            registry: RenderRegistry,
        ): KoolBackend {
            val visible = when (mode) {
                RenderMode.Headless -> throw IllegalArgumentException(
                    "RenderMode.Headless has no render context and no Presentation: it is " +
                        "udea-core's path, and GameHost never calls a PresentationFactory in it. " +
                        "Starting a backend for it would create the window the mode exists to avoid.",
                )
                RenderMode.Offscreen -> false
                RenderMode.Windowed -> true
            }

            val kool = KoolThread(window, visible)
            kool.start()
            return KoolBackend(mode, window, registry, kool)
        }
    }
}
