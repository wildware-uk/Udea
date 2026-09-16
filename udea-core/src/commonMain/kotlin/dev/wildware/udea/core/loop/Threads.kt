package dev.wildware.udea.core.loop

/**
 * The three thread primitives the loop and the headless host need, and nothing more (issue #203).
 *
 * Spec section 6 puts the simulation step, the barrier drain and rendering on one thread on every
 * platform. What still crosses a thread today is the JVM agent host: an MCP request pauses the loop
 * from an HTTP thread while `GameHost.run()` ticks on its own. These are what let [GameLoop] tell
 * "paused" from "will stop after the tick already in flight" on a runtime that has threads, and
 * what each single-threaded runtime answers instead.
 */

/**
 * An identity for the calling thread, compared with `===`.
 *
 * Only ever compared, never inspected, so each platform returns whatever object is cheapest to
 * make unique per thread.
 */
internal expect fun currentThreadToken(): Any

/**
 * Yields while another thread finishes the tick it is inside.
 *
 * Reached only when [currentThreadToken] differs between the pauser and the ticking thread, so a
 * single-threaded runtime cannot reach it.
 */
internal expect fun yieldToTickingThread()

/**
 * Blocks the calling thread for about [nanos] nanoseconds while a headless host is paused.
 *
 * A single-threaded runtime cannot honour this: with the loop paused nothing runs, so nothing
 * could ever resume it.
 */
internal expect fun parkWhilePaused(nanos: Long)
