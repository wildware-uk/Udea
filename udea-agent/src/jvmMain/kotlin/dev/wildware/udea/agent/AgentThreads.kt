package dev.wildware.udea.agent

import java.util.concurrent.ThreadFactory

/**
 * Threads the agent surface is allowed to run on: daemon ones, always.
 *
 * ## Why this is a named policy rather than a lambda at the call site
 *
 * The reference implementation ran its debug HTTP server with `executor = null`, which gives
 * `HttpServer` its own **non-daemon** dispatch thread, and that one default halted six
 * separate automated sessions and cost five manual process kills.
 *
 * The failure is worth writing out, because nothing about it looks like a bug from the
 * outside. Every `close` command the bridge accepts is *deferred* to the loop - correctly,
 * because tearing the world down halfway through delivering an event is worse. So if the game
 * dies before or during startup there is no loop left to run the deferred work: the crash
 * handler writes its report and returns, the main thread ends, and a non-daemon dispatch
 * thread keeps the JVM - and the port - alive with nothing behind it that can answer.
 * Measured on exactly that husk:
 *
 * ```
 * /health  -> {"ok":true,"frame":0}      // looks alive
 * /state   -> {"ready":false}            // is not
 * close    -> {"accepted":true, ...}     // accepted, never executed
 * ```
 *
 * An agent that is forbidden from killing processes - which is the correct rule for a live
 * game - then has no way out at all, and the port stays held until a human logs in. One typo
 * in a menu constructor costs a port for the rest of the day.
 *
 * A daemon thread makes the JVM exit conditional on the game rather than on the thing watching
 * it, which is the correct dependency: the agent surface exists to observe the game and has no
 * business outliving it.
 *
 * ## Why it lives in `udea-agent` and not in the host module
 *
 * The HTTP server is `udea-agent-host`. The policy is here, one module down, for the same two
 * reasons it was stated in `core` rather than in the desktop launcher: a policy with a name
 * does not get dropped as an incidental edit when branches are merged, and this is the module
 * whose tests can reach it. `AgentThreadsTest` asserts it.
 *
 * An `object` with no state - the one shape the no-mutable-singletons rule permits, because
 * there is nothing here to mutate.
 */
public object AgentThreads {

    /**
     * A factory whose threads are daemons and carry [name], so a stack dump says which
     * subsystem is holding what.
     *
     * [name] is used verbatim for a single-threaded executor. Where several threads share a
     * factory the JVM will hand them the same name, which is worse than a suffix but better
     * than the alternative the reference had, which was no name at all.
     */
    public fun daemonFactory(name: String): ThreadFactory {
        require(name.isNotBlank()) { "an agent thread needs a name; a stack dump of Thread-7 tells nobody anything" }
        return ThreadFactory { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
    }
}
