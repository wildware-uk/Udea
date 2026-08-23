package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.core.host.GameHost
import java.util.concurrent.locks.LockSupport

/**
 * The frame loop that pumps [AgentRuntime] around [GameHost], for a host with no render backend.
 *
 * ## Why this exists at all
 *
 * `GameHost.run()` drives `GameLoop.tickIfRunning()` and nothing else. It never calls
 * [AgentRuntime.beforeFrame] or [AgentRuntime.afterFrame], so a game started with `run()` and an
 * [AgentHost] bound in front of it accepts commands onto the bridge queue and **executes none of
 * them**: nothing drains the queue onto the `SimBarrier`, nothing publishes the digest, and
 * `/state` answers whatever the constructor left behind, forever. In a windowed or offscreen
 * build the render backend's frame callback is the thing that calls the pair; headless there is
 * no backend, and this class is the missing half.
 *
 * ## Draining is not conditional on the tick
 *
 * The order below is the load-bearing part:
 *
 * ```
 * beforeFrame()                    // queue -> barrier, whatever the loop is doing
 * host.frame(wallDelta)            // 0..maxCatchUp ticks; each one drains the barrier
 * afterFrame(loop.lastFrameTicks)  // 0 ticks -> drain the barrier here instead
 * ```
 *
 * `AgentRuntime.afterFrame(0)` drains the barrier itself, which is what makes a **paused** game
 * still answer. Pause is the first move of the spec's own Phase 1 demo — pause, spawn, step,
 * screenshot, rewind, inspect — and a loop that only drained inside a tick would hang every one
 * of those steps: the command would sit on the queue, `completedCommandId` would never advance,
 * and the caller polling `/state` would report a healthy game as frozen. Pausing must stop the
 * *simulation*, never the *surface*.
 *
 * For the same reason the park at the bottom is unconditional and short rather than a
 * pause-only sleep: the loop has to keep coming round to drain, so a paused host costs a wakeup
 * every [FRAME_NANOS] rather than a core at 100%.
 *
 * ## Wall time is read here and nowhere else
 *
 * `GameLoop` takes its delta as a parameter precisely so that nothing below it reads a clock.
 * This is the one place in a headless agent host that does, which is why a `SimHarness` run —
 * which pumps the same two methods by hand — is deterministic and this is not.
 */
public class AgentGameLoop(
    private val host: GameHost,
    private val runtime: AgentRuntime,
    /** Reads the wall clock. A parameter so a test can drive frames without sleeping. */
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /**
     * False once [stop] is called. [run] returns when it goes false.
     *
     * `@Volatile` for the same reason `GameHost.running` is: the writer is an HTTP handler or a
     * shutdown hook on another thread, and without it the JIT may hoist the read out of the loop
     * and a cross-thread stop would never be observed.
     */
    @Volatile
    public var running: Boolean = true
        private set

    /** Iterations run. What `frame` in the digest counts, once this loop is driving. */
    public var frames: Long = 0L
        private set

    /** Makes [run] return after the iteration in flight. Safe from any thread. */
    public fun stop() {
        running = false
    }

    /**
     * Pumps until [stop], or until the host stops itself.
     *
     * Blocks the calling thread; that thread becomes the simulation thread, and every tool call
     * runs on it inside a barrier drain.
     */
    public fun run() {
        var last = nanoTime()
        while (running && host.running) {
            val now = nanoTime()
            val wallDelta = (now - last).toFloat() / NANOS_PER_SECOND
            last = now
            pump(wallDelta)
            LockSupport.parkNanos(FRAME_NANOS)
        }
    }

    /**
     * One iteration: drain, advance by [wallDelta], publish.
     *
     * Public and separate from [run] so a caller that owns its own cadence — a render backend's
     * frame callback, a test — drives exactly the same sequence rather than a copy of it.
     */
    public fun pump(wallDelta: Float) {
        runtime.beforeFrame()
        host.frame(wallDelta)
        runtime.afterFrame(host.loop.lastFrameTicks)
        frames++
    }

    override fun toString(): String = "AgentGameLoop(frames=$frames, running=$running)"

    public companion object {

        /** Nanoseconds in a second, as a float divisor. */
        private const val NANOS_PER_SECOND: Float = 1_000_000_000f

        /**
         * How long an iteration parks before the next one.
         *
         * A sixtieth of a second: fast enough that a command reaches the barrier within one
         * frame of arriving, slow enough that a paused host is idle. The simulation's own rate
         * is `EngineConfig.tickRate` and is unaffected — `GameLoop` converts whatever wall time
         * actually elapsed into whole ticks, so a long park produces more ticks, not slower ones.
         */
        public const val FRAME_NANOS: Long = 16_666_667L
    }
}
