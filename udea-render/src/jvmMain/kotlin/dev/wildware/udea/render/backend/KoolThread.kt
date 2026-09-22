package dev.wildware.udea.render.backend

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolContext
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.onResize
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.util.Time
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.system.Configuration
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * The Kool context, and the one thread that is allowed to touch it.
 *
 * ## Why there is a thread here at all
 *
 * `KoolApplication` *is* the frame loop: it creates the window and does not return until the window
 * has closed. A host that called it directly would never reach its next line, so nothing could ask
 * it for a screenshot, drive it a tick at a time, or shut it down. So the loop gets a thread of its
 * own and this class is the only door into it.
 *
 * Two ways through that door, and no third:
 *
 * - [submit] runs a block on the render thread and blocks the caller for its result. Everything
 *   that creates or destroys a render object goes through it.
 * - [driveWith] installs the per-frame callback. It is set *after* construction because the thing
 *   that drives frames is a `GameHost`, and a `GameHost` builds its presentation - this context - in
 *   its own constructor. Modelled as [Frames.Idle] or [Frames.Driven] rather than a `lateinit`, so
 *   "no driver yet" is a state with a defined behaviour (draw nothing, serve tasks) instead of an
 *   exception waiting for a race.
 *
 * Both run from Kool's `onRender` hook, which Kool calls once per frame on this thread before it
 * collects the scenes. So a frame driven here records into the batches, and Kool draws exactly what
 * was recorded in the same frame.
 *
 * ## One context per process
 *
 * Kool 0.19.0 allows one `KoolContext` per JVM, for the life of the JVM: its desktop platform
 * refuses a second `createContext` even after the first has closed. So a process starts at most
 * one of these, and a test suite that needs a context per test class forks a JVM per class.
 *
 * ## Failure
 *
 * A render thread that dies takes every future [submit] with it. The cause is kept in [failure] and
 * rethrown to the caller, rather than left as a caller blocked forever on a `Future` nobody will
 * ever complete.
 */
internal class KoolThread(private val window: WindowConfig, private val visible: Boolean) {

    private val tasks = ConcurrentLinkedQueue<FutureTask<*>>()
    private val ready = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>(null)
    private val frames = AtomicReference<Frames>(Frames.Idle)
    private val resizes = AtomicReference<((Int, Int) -> Unit)?>(null)
    private val context = AtomicReference<KoolContext?>(null)
    private val gl = AtomicReference<GlInfo?>(null)

    /**
     * Run once when the loop exits, however it exits.
     *
     * The render loop can die three ways - the window closed, [stop] asked it to, or a renderer
     * threw out of a frame - and only the second of those runs a host's shutdown path. Without
     * this, the third left every capture waiter waiting out its whole deadline on a loop that had
     * already gone, and reporting the timeout rather than the exception.
     */
    private val shutdown = AtomicReference<((Throwable?) -> Unit)?>(null)

    private val thread = Thread({ run() }, "udea-kool").apply { isDaemon = true }

    /**
     * True between a successful [start] and the render loop exiting.
     *
     * The loop's exit is read from [finished] and not from `Thread.isAlive` (issue #178): [run]'s
     * `finally` counts [finished] down and *then* fails the queued tasks and runs the shutdown
     * hook, so between the loop ending and the OS thread terminating there is a window nothing
     * bounds. Reading the latch makes the answer a consequence of the loop's own exit signal.
     * `thread.isAlive` stays in the conjunction because it is what answers *before* [start].
     */
    val isRunning: Boolean get() =
        thread.isAlive && finished.count > 0L && failure.get() == null

    /** The Kool context, once [start] has returned. Render thread only, like everything on it. */
    val ctx: KoolContext get() = checkNotNull(context.get()) { "the Kool context has not started" }

    /** What the driver said it is, once [start] has returned. Any thread: it is two strings. */
    val glInfo: GlInfo get() = checkNotNull(gl.get()) { "the Kool context has not started" }

    /**
     * Boots the context and returns once it is drawing frames.
     *
     * @throws GlContextException if the context could not be created, or did not come up inside
     *   [STARTUP_TIMEOUT_SECONDS]. Both are the same fact to the caller - there is no context -
     *   and both must be loud: a host that carried on would hand out a `Presentation` that draws
     *   into nothing.
     */
    fun start() {
        thread.start()
        val arrived = ready.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val cause = failure.get()
        if (cause != null) {
            throw GlContextException("the Kool context failed to start for $window", cause)
        }
        if (!arrived) {
            throw GlContextException(
                "the Kool context did not come up within ${STARTUP_TIMEOUT_SECONDS}s for $window",
            )
        }
    }

    /**
     * Runs [block] on the render thread and returns its result.
     *
     * @throws IllegalStateException if the render loop is not running.
     * @throws GlContextException if the loop dies while the block is queued, or does not reach it
     *   inside [TASK_TIMEOUT_MILLIS].
     */
    fun <T> submit(block: () -> T): T {
        check(isRunning) { "the Kool render thread is not running" }
        val task = FutureTask(Callable { block() })
        tasks.add(task)

        // Polled rather than a plain get(), because a render thread that has already died will
        // never drain the queue: an unbounded wait would be a hang with no diagnostic at all.
        var waitedMillis = 0L
        while (true) {
            try {
                return task.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                waitedMillis += POLL_MILLIS
            } catch (cancelled: CancellationException) {
                throw GlContextException("the Kool render thread stopped before running a task", cancelled)
            }
            val cause = failure.get()
            if (cause != null || finished.count == 0L) {
                throw GlContextException("the Kool render thread stopped before running a task", cause)
            }
            if (waitedMillis >= TASK_TIMEOUT_MILLIS) {
                task.cancel(false)
                throw GlContextException(
                    "a render task was not run within ${TASK_TIMEOUT_MILLIS}ms; the render loop is " +
                        "alive but is not draining its queue",
                )
            }
        }
    }

    /** Installs the per-frame callback, replacing whatever was there. */
    fun driveWith(driver: (Float) -> Unit) {
        frames.set(Frames.Driven(driver))
    }

    /**
     * Stops calling the per-frame callback; the loop goes on serving [submit] and drawing nothing.
     *
     * Render thread, and in the same task that releases what the callback draws with: a task runs at
     * the top of a frame, *before* that frame's callback, so a callback still installed would draw
     * that very frame on what the task had just released (issue #275).
     */
    fun stopDriving() {
        frames.set(Frames.Idle)
    }

    /** Installs the resize callback, which is called on the render thread. */
    fun onResize(handler: (Int, Int) -> Unit) {
        resizes.set(handler)
    }

    /**
     * Installs the callback run when the loop exits, whatever ended it.
     *
     * Called on the render thread after the loop has gone, so it must not touch render objects: it
     * is for releasing the things *waiting* on the loop, not the things the loop owned. It is handed
     * the exception that stopped the loop, or `null` when the loop was asked to stop or its window was
     * closed, so a waiter can be told why rather than only that (issue #275).
     */
    fun onShutdown(hook: (Throwable?) -> Unit) {
        shutdown.set(hook)
    }

    /**
     * Blocks until the render loop has exited, and says so if it exited because something threw.
     *
     * @throws GlContextException with the exception as its cause, when a frame, a task or Kool itself
     *   threw out of the loop. Returning normally there is what let a game's `main` exit 0 with
     *   nothing said after its window closed itself (issue #275): the loop had died, and the only
     *   record of why was a field nobody read.
     */
    fun awaitExit() {
        finished.await()
        val cause = failure.get() ?: return
        throw GlContextException("the Kool render loop stopped because it threw: $cause", cause)
    }

    /**
     * Asks the loop to exit and waits for it to say it has.
     *
     * Idempotent. When it returns having observed the exit, [isRunning] is false and every later
     * [submit] is refused. It can also return on [SHUTDOWN_TIMEOUT_SECONDS] with the loop still
     * running; that is a loop which did not stop, and [isRunning] still says so.
     */
    fun stop() {
        if (thread.isAlive && finished.count > 0L) {
            // Queued rather than submitted: the block ends the loop that would have completed a
            // submit's future, so waiting for it to return is waiting forever. Closing the window
            // from the render thread is what Kool's own close path expects.
            tasks.add(FutureTask(Callable { (context.get() as? Lwjgl3Context)?.close() }))
        }
        finished.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun run() {
        try {
            initGlfwOnX11WhenNoWayland()
            KoolApplication(config()) {
                val ctx = this.ctx
                context.set(ctx)
                // Read here, on the thread the context is current on, and printed once: which
                // OpenGL a launch got is the first question about any launch that draws wrong.
                val info = GlInfo(
                    renderer = GL11.glGetString(GL11.GL_RENDERER).orEmpty(),
                    version = GL11.glGetString(GL11.GL_VERSION).orEmpty(),
                )
                gl.set(info)
                println("[udea-render] $info")
                ctx.onRender += { onFrame() }
                ctx.window.onResize { size -> resizes.get()?.invoke(size.x, size.y) }
                ready.countDown()
            }
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
            report(t)
        } finally {
            ready.countDown()
            finished.countDown()
            failAllQueued()
            // Last, and outside the failure path's control: a broken hook must not stop the two
            // latches above from having been counted down. Its own failure is recorded and
            // reported, not lost.
            try {
                shutdown.get()?.invoke(failure.get())
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                report(t)
            }
        }
    }

    /**
     * Puts the exception that ended the loop on stderr, with its stack trace.
     *
     * Not log-and-continue: nothing continues, the loop is over. This is the one report a person
     * gets who holds neither a capture nor [awaitExit] - a player whose window has just closed - and
     * without it the window closed with nothing said at all (issue #275). The trace names the frame
     * that threw, which for a game's renderer or overlay is the game's own line.
     */
    private fun report(failure: Throwable) {
        System.err.println(
            "udea-render: the Kool render loop stopped because it threw, and nothing will be drawn again: $failure",
        )
        failure.printStackTrace()
    }

    private fun onFrame() {
        drainTasks()
        when (val driver = frames.get()) {
            is Frames.Idle -> Unit
            is Frames.Driven -> driver.tick(Time.deltaT)
        }
    }

    private fun drainTasks() {
        while (true) {
            val task = tasks.poll() ?: return
            // FutureTask captures the failure for the submitter; letting it escape here would kill
            // the whole context because one caller asked for something bad.
            task.run()
        }
    }

    private fun failAllQueued() {
        while (true) {
            val task = tasks.poll() ?: return
            task.cancel(false)
        }
    }

    private fun config(): KoolConfigJvm = KoolConfigJvm(
        // Issue #210: Udea starts Kool on OpenGL, and captures read back in GL's row order.
        renderBackend = RenderBackendGl,
        // Off, so a context that cannot come up on GL says so instead of quietly being something
        // whose read-back rows run the other way.
        useOpenGlFallback = false,
        windowTitle = window.title,
        windowSize = Vec2i(window.windowWidth, window.windowHeight),
        showWindowOnStart = visible,
        isVsync = window.vsync,
        maxFrameRate = window.framesPerSecond,
        // An Offscreen host's window is never focused, so Kool paces it by the unfocused rate.
        // Left at the default, a hidden host would run at a rate nobody chose.
        windowNotFocusedFrameRate = window.framesPerSecond,
        // Scenes are collected on this thread, after the frame callback has recorded into the
        // batches. Kool's default collects the *next* frame on a coroutine while this one draws,
        // which would read the batches while a renderer is writing them.
        asyncSceneUpdate = false,
        // One sample: a capture is exact texels, not a resolve of a multisampled buffer.
        numSamples = 1,
    )

    /** Whether the loop has a frame callback to call. */
    private sealed interface Frames {

        /**
         * No callback: the loop still serves [submit], it just draws nothing. Before [driveWith],
         * and again after [stopDriving].
         */
        data object Idle : Frames

        class Driven(private val driver: (Float) -> Unit) : Frames {
            fun tick(wallDelta: Float) {
                driver(wallDelta)
            }
        }
    }

    private companion object {

        /** Cold JVM, driver init and window creation. Generous; only a hang crosses it. */
        const val STARTUP_TIMEOUT_SECONDS: Long = 30L

        const val TASK_TIMEOUT_MILLIS: Long = 30_000L

        const val SHUTDOWN_TIMEOUT_SECONDS: Long = 10L

        /** How often a blocked [submit] rechecks that the render thread is still alive. */
        const val POLL_MILLIS: Long = 50L

        /** The value Kool 0.19.0's `createContext` gives LWJGL's `Configuration.STACK_SIZE`. */
        const val KOOL_STACK_SIZE_KB: Int = 128

        /**
         * Kool 0.19.0's GLFW window subsystem hints `GLFW_PLATFORM_WAYLAND` whenever GLFW was
         * *built* with Wayland support, which LWJGL's GLFW always is on Linux, and never falls
         * back. With no Wayland compositor - xvfb, a CI box, an X11 desktop - `glfwInit` fails and
         * Kool throws "Unable to initialize GLFW" (measured in the #200 spike).
         *
         * `glfwInit` returns at once when GLFW is already initialised, so initialising it here on
         * X11 first makes Kool's own hint a no-op. Only done on Linux with no Wayland display
         * advertised; elsewhere Kool's own platform choice stands.
         */
        fun initGlfwOnX11WhenNoWayland() {
            if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return
            if (System.getenv("WAYLAND_DISPLAY") != null) return
            // Set before LWJGL is first touched on this thread, as Kool's createContext would:
            // otherwise this thread's MemoryStack is made at LWJGL's default size.
            Configuration.STACK_SIZE.set(KOOL_STACK_SIZE_KB)
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
            check(GLFW.glfwInit()) { "glfwInit failed on X11 (DISPLAY=${System.getenv("DISPLAY")})" }
        }
    }
}

/**
 * No render context, or a context that died.
 *
 * Typed and loud, because the alternative a host would otherwise reach for is carrying on with a
 * `Presentation` that draws into nothing - which looks like a black screen to an agent and like a
 * working build to CI.
 */
public class GlContextException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
