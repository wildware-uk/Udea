package dev.wildware.udea.render.backend

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The LWJGL3 context, and the one thread that is allowed to touch it.
 *
 * ## Why there is a thread here at all
 *
 * `Lwjgl3Application`'s constructor *is* the frame loop: it creates the window and then does
 * not return until every window has closed. A host that called it directly would never reach
 * its next line, so nothing could ask it for a screenshot, drive it a tick at a time, or shut
 * it down. That is exactly the shape Phase 1 needs — an agent's tool call arrives on the agent
 * host's thread and has to be served on the render thread — so the loop gets a thread of its
 * own and this class is the only door into it.
 *
 * Two ways through that door, and no third:
 *
 * - [submit] runs a block on the GL thread and blocks the caller for its result. Everything
 *   that creates or destroys a GL object goes through it.
 * - [driveWith] installs the per-frame callback. It is set *after* construction because the
 *   thing that drives frames is a `GameHost`, and a `GameHost` builds its presentation — this
 *   context — in its own constructor. Modelled as a value that is either [Frames.Idle] or
 *   [Frames.Driven] rather than a `lateinit`, so "no driver yet" is a state with a defined
 *   behaviour (draw nothing, serve tasks) instead of an exception waiting for a race.
 *
 * ## Failure
 *
 * A GL thread that dies takes every future [submit] with it. The cause is kept in [failure] and
 * rethrown to the caller, rather than left as a caller blocked forever on a `Future` nobody
 * will ever complete.
 */
internal class GlThread(private val window: WindowConfig, visible: Boolean) {

    private val tasks = ConcurrentLinkedQueue<FutureTask<*>>()
    private val ready = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>(null)
    private val frames = AtomicReference<Frames>(Frames.Idle)
    private val resizes = AtomicReference<((Int, Int) -> Unit)?>(null)

    private val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle(window.title)
        setWindowedMode(window.windowWidth, window.windowHeight)
        setInitialVisible(visible)
        useVsync(window.vsync)
        setForegroundFPS(window.framesPerSecond)
        // An Offscreen host's window is never focused, so LibGDX paces it by the *idle* rate.
        // Left at the default, a hidden host would run at a rate nobody chose.
        setIdleFPS(window.framesPerSecond)
        // Nothing in udea-render plays audio, and OpenAL device enumeration is the single
        // most common way a context fails to come up on a machine with no sound card - a CI
        // box, a container, a remote session.
        disableAudio(true)
    }

    private val thread = Thread({ run() }, "udea-gl").apply { isDaemon = true }

    /** True between a successful [start] and the GL thread exiting. */
    val isRunning: Boolean get() = thread.isAlive && failure.get() == null

    /**
     * Boots the context and returns once it is current and usable.
     *
     * @throws GlContextException if the context could not be created, or did not come up
     *   inside [STARTUP_TIMEOUT_SECONDS]. Both are the same fact to the caller — there is no
     *   context — and both must be loud: a host that carried on would hand out a
     *   `Presentation` that draws into nothing.
     */
    fun start() {
        thread.start()
        val arrived = ready.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val cause = failure.get()
        if (cause != null) {
            throw GlContextException("the LWJGL3 context failed to start for $window", cause)
        }
        if (!arrived) {
            throw GlContextException(
                "the LWJGL3 context did not come up within ${STARTUP_TIMEOUT_SECONDS}s for $window",
            )
        }
    }

    /**
     * Runs [block] on the GL thread and returns its result.
     *
     * @throws GlContextException if the GL thread is not running, dies while the block is
     *   queued, or does not reach it inside [TASK_TIMEOUT_SECONDS].
     */
    fun <T> submit(block: () -> T): T {
        check(isRunning) { "the GL thread is not running" }
        val task = FutureTask(Callable { block() })
        tasks.add(task)

        // Polled rather than a plain get(), because a GL thread that has already died will
        // never drain the queue: an unbounded wait would be a hang with no diagnostic at all.
        var waitedMillis = 0L
        while (true) {
            try {
                return task.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                waitedMillis += POLL_MILLIS
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw GlContextException("the GL thread stopped before running a task", cancelled)
            }
            val cause = failure.get()
            if (cause != null || !thread.isAlive) {
                throw GlContextException("the GL thread stopped before running a task", cause)
            }
            if (waitedMillis >= TASK_TIMEOUT_MILLIS) {
                task.cancel(false)
                throw GlContextException(
                    "a GL task was not run within ${TASK_TIMEOUT_MILLIS}ms; the render loop is " +
                        "alive but is not draining its queue",
                )
            }
        }
    }

    /** Installs the per-frame callback, replacing whatever was there. */
    fun driveWith(driver: (Float) -> Unit) {
        frames.set(Frames.Driven(driver))
    }

    /** Installs the resize callback, which LibGDX calls on the GL thread. */
    fun onResize(handler: (Int, Int) -> Unit) {
        resizes.set(handler)
    }

    /**
     * Asks the loop to exit and waits for the thread to finish.
     *
     * Idempotent: a host's shutdown path and a test's `finally` both call it.
     */
    /** Blocks until the render loop has exited. */
    fun awaitExit() {
        finished.await()
    }

    fun stop() {
        if (thread.isAlive) {
            // `postRunnable` rather than `submit`: the block ends the loop that would have
            // completed a `submit`'s future, so waiting for it to return is waiting forever.
            runCatching { Gdx.app?.postRunnable { Gdx.app.exit() } }
        }
        finished.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun run() {
        try {
            Lwjgl3Application(Listener(), config)
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        } finally {
            ready.countDown()
            finished.countDown()
            failAllQueued()
        }
    }

    private fun failAllQueued() {
        while (true) {
            val task = tasks.poll() ?: return
            task.cancel(false)
        }
    }

    private inner class Listener : ApplicationListener {

        override fun create() {
            ready.countDown()
        }

        override fun render() {
            drainTasks()
            when (val driver = frames.get()) {
                is Frames.Idle -> Unit
                is Frames.Driven -> driver.tick(Gdx.graphics.deltaTime)
            }
        }

        override fun resize(width: Int, height: Int) {
            resizes.get()?.invoke(width, height)
        }

        override fun pause(): Unit = Unit

        override fun resume(): Unit = Unit

        override fun dispose(): Unit = Unit

        private fun drainTasks() {
            while (true) {
                val task = tasks.poll() ?: return
                // FutureTask captures the failure for the submitter; letting it escape here
                // would kill the whole context because one caller asked for a bad texture.
                task.run()
            }
        }
    }

    /** Whether a frame callback has been installed yet. */
    private sealed interface Frames {

        /** Before [driveWith]: the loop still serves [submit], it just draws nothing. */
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

        /** How often a blocked [submit] rechecks that the GL thread is still alive. */
        const val POLL_MILLIS: Long = 50L
    }
}

/**
 * No GL context, or a context that died.
 *
 * Typed and loud, because the alternative a host would otherwise reach for is carrying on with
 * a `Presentation` that draws into nothing — which looks like a black screen to an agent and
 * like a working build to CI.
 */
public class GlContextException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
