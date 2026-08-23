package dev.wildware.udea.render.backend

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.PresentationBackend
import dev.wildware.udea.core.host.PresentationFactory
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGame
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderTargets
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.capture.GlPixelSource
import java.util.concurrent.atomic.AtomicReference

/**
 * The `Offscreen` and `Windowed` presentation backends: a real LWJGL3 context, either way.
 *
 * ## The one difference between the two modes
 *
 * `setInitialVisible(false)`. That is the whole of it. Both modes create a real context, a real
 * backbuffer and the identical [RenderPipeline]; both capture; both run the identical
 * `Simulation`, because the simulation does not know this class exists. There is no
 * `if (offscreen)` anywhere below, and there must never be one: the moment a renderer branches
 * on the mode, "it looks wrong in the agent's screenshots but right on screen" becomes a class
 * of bug the engine can have.
 *
 * `RenderMode.Headless` is not served here and cannot be. It is `udea-core`'s path — no
 * context, no pipeline, `no_render_context` from `GameHost.screenshot` — and asking for it here
 * is a wiring mistake worth failing on rather than quietly booting a window nobody asked for.
 *
 * ## How it fits `GameHost`
 *
 * ```
 * val backend = Lwjgl3Backend.start(RenderMode.Offscreen, WindowConfig(), registry)
 * val host = GameHost(RenderMode.Offscreen, definition, backend)   // PresentationFactory
 * backend.drive(host)                                              // frames start flowing
 * ...
 * backend.close()
 * ```
 *
 * The two-step — construct, *then* drive — is forced by a genuine cycle rather than a
 * preference: a `GameHost` builds its presentation inside its own constructor, so the
 * presentation cannot be handed the host it does not yet have. [GlThread] models the gap as a
 * state with defined behaviour (serve GL tasks, draw nothing) instead of a `lateinit` that
 * throws if anything happens in the wrong order.
 */
public class Lwjgl3Backend private constructor(
    /** The mode this backend was started for. Reported by `/health` through `GameHost.mode`. */
    public val mode: RenderMode,
    private val window: WindowConfig,
    private val registry: RenderRegistry,
    private val gl: GlThread,
) : PresentationFactory, AutoCloseable {

    private val built = AtomicReference<RenderPipeline?>(null)

    /** The pipeline, once [create] has run. `null` before that. */
    public val pipeline: RenderPipeline? get() = built.get()

    /**
     * Builds the pipeline and its GL resources **on the render thread**.
     *
     * Every object made here is a GL object with thread affinity — a `SpriteBatch` compiles a
     * shader, a `FrameBuffer` allocates a texture — so this runs inside [GlThread.submit] and
     * the calling thread waits. Constructing them on the caller's thread is the classic
     * version of this bug: it appears to work, and then fails on a driver that checks.
     *
     * @throws IllegalStateException if called more than once. One host, one pipeline; a second
     *   would leak the first's batch and framebuffer and quietly render into the wrong one.
     */
    override fun create(game: UdeaGame): PresentationBackend {
        val pipeline = gl.submit {
            val batch = SpriteBatch()
            val buffer = FrameBuffer(
                Pixmap.Format.RGBA8888,
                window.renderWidth,
                window.renderHeight,
                // No depth buffer: this is a 2D engine, and an unused depth attachment is
                // memory and bandwidth on every frame of every capture.
                false,
            )
            val targets = RenderTargets(
                offscreen = OffscreenTarget(buffer.width, buffer.height),
                screen = ScreenTarget(Gdx.graphics.width, Gdx.graphics.height),
                batch = batch,
                surface = GlFrameSurface(buffer, batch),
                pixels = GlPixelSource(),
                // Construction order, disposed in reverse: the surface's TextureRegion points
                // at the framebuffer's colour attachment, so the framebuffer must outlive the
                // batch that blits it.
                owned = listOf(buffer, batch),
            )
            registry.build(game.world, game.ctx, targets)
        }

        check(built.compareAndSet(null, pipeline)) {
            "$this already built a pipeline; one backend serves one GameHost"
        }
        gl.onResize { width, height -> pipeline.resize(width, height) }

        return PresentationBackend(pipeline, pipeline.capture)
    }

    /**
     * Hands the frame cadence to the render thread, which drives [host] from here on.
     *
     * Until this is called the context is up and serving GL work but nothing is simulated and
     * nothing is drawn — which is the correct behaviour for the window between
     * [Lwjgl3Backend.start] and the host existing, not a degraded mode.
     */
    public fun drive(host: GameHost) {
        gl.driveWith(host::frame)
    }

    /**
     * Runs [block] on the render thread and returns its result.
     *
     * The door for the GL work a host legitimately has outside a frame: uploading a texture,
     * compiling a shader, reading back a buffer. GL objects have thread affinity, and doing any
     * of it from the caller's thread is the bug that appears to work until it meets a driver
     * that checks.
     *
     * @throws GlContextException if the render thread is gone or stops before reaching [block].
     * @throws IllegalStateException if the backend has been closed.
     */
    public fun <T> onRenderThread(block: () -> T): T = gl.submit(block)

    /** Blocks until the render loop exits, whether from [close] or the window being closed. */
    public fun awaitExit() {
        gl.awaitExit()
    }

    /**
     * Disposes the pipeline on the render thread, then stops the context.
     *
     * In that order: `dispose` releases GL objects and must happen where they were made, and
     * it also fails every queued capture so no agent thread is left waiting on a loop that has
     * gone.
     */
    override fun close() {
        val pipeline = built.getAndSet(null)
        if (pipeline != null && gl.isRunning) {
            runCatching { gl.submit { pipeline.dispose() } }
        }
        gl.stop()
    }

    override fun toString(): String = "Lwjgl3Backend($mode, $window)"

    public companion object {

        /**
         * Boots a context and returns once it is current.
         *
         * @param mode [RenderMode.Offscreen] for a hidden window, [RenderMode.Windowed] for a
         *   visible one.
         * @throws IllegalArgumentException for [RenderMode.Headless], which has no context by
         *   definition and is served by `udea-core` alone.
         * @throws GlContextException if no context could be created — no display, no driver,
         *   no natives. Loud, because the alternative is a host that draws into nothing and
         *   hands an agent a black picture it will believe.
         */
        public fun start(
            mode: RenderMode,
            window: WindowConfig,
            registry: RenderRegistry,
        ): Lwjgl3Backend {
            val visible = when (mode) {
                RenderMode.Headless -> throw IllegalArgumentException(
                    "RenderMode.Headless has no GL context and no Presentation: it is udea-core's " +
                        "path, and GameHost never calls a PresentationFactory in it. Starting a " +
                        "backend for it would create the window the mode exists to avoid.",
                )
                RenderMode.Offscreen -> false
                RenderMode.Windowed -> true
            }

            val gl = GlThread(window, visible)
            gl.start()
            return Lwjgl3Backend(mode, window, registry, gl)
        }
    }
}
