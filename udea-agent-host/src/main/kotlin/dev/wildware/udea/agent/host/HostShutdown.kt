package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.tools.GameShutdown
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The teardown `close` runs: an ordered list of steps, run once, none of which may stop the rest.
 *
 * ## Why a host needs this and not just `agentHost.stop()`
 *
 * Because stopping the HTTP server is the *last* of three things and the least important two of
 * them are the ones a reviewer cannot see. A `close` that only unbound the port would leave the
 * frame loop spinning, the window on the desktop and the process alive - and the bridge, which
 * takes silence on the port as its confirmation, would report a clean close over a game that is
 * still running. That is a worse failure than no `close` at all, because it is a lie the caller
 * has no way to check.
 *
 * So the host registers what it owns, in the order it must go:
 *
 * ```
 * HostShutdown()
 *     .onClose("frame-loop") { loop.stop() }        // stop simulating
 *     .onClose("agent-host") { agentHost?.stop() }  // then let the port go quiet
 * ```
 *
 * Loop first is deliberate and is the ordering `MobaAgent` already used by hand: unbinding the
 * port while a tool call is mid-drain hands the caller a closed connection to a command that
 * *did* run, which is the one outcome an agent cannot reason about.
 *
 * ## Run once, on the simulation thread, and never mid-tick
 *
 * [shutdown] is invoked from [dev.wildware.udea.agent.dispatch.AgentContext.defer], so it runs
 * after every system of the tick that carried the `close` and outside the `SimBarrier` drain the
 * tool ran inside. [LifecycleToolset][dev.wildware.udea.agent.tools.LifecycleToolset] already
 * refuses a second `close`; the flag here is the second half of that, because a host may also
 * call [shutdown] from a JVM shutdown hook and the two must not interleave.
 *
 * ## A step that throws does not strand the steps after it
 *
 * The same containment `DeferredQueue` and `SimBarrier` apply, and for the same reason: the
 * remaining steps belong to other subsystems, and stranding the port because a render loop
 * refused to exit turns one broken teardown into an instance the bridge can never let go of.
 */
public class HostShutdown : GameShutdown {

    private val steps = CopyOnWriteArrayList<Step>()

    private val done = AtomicBoolean(false)

    /** Whether teardown has run. */
    public val isClosed: Boolean get() = done.get()

    /** Why it ran, or `null` while it has not. */
    @Volatile
    public var reason: String? = null
        private set

    /**
     * Appends [step], to run after everything already registered.
     *
     * @param name reported if the step throws. A label rather than a lambda's `toString`,
     *   because a lambda's is a synthetic class name and tells a reader nothing.
     */
    public fun onClose(name: String, step: () -> Unit): HostShutdown {
        steps.add(Step(name, step))
        return this
    }

    override fun shutdown(reason: String) {
        if (!done.compareAndSet(false, true)) return
        this.reason = reason
        steps.forEach { step ->
            try {
                step.run()
            } catch (failure: Throwable) {
                // Printed rather than rethrown: the caller is the frame loop, and a teardown
                // that failed half-way still has to finish releasing the port.
                System.err.println("[udea-agent-host] close step '${step.name}' failed: $failure")
            }
        }
    }

    override fun toString(): String =
        "HostShutdown(${steps.size} step(s), closed=$isClosed, reason=$reason)"

    private class Step(val name: String, private val body: () -> Unit) {
        fun run(): Unit = body()
    }
}
