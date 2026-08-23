package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.DigestBudgets
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.loop.SimBarrier

/**
 * What the engine is costing, including what the agent surface itself costs.
 *
 * ## The digest's own line is the point
 *
 * `digest < 0.3ms at 500 entities` is a Phase 1 exit criterion, and a budget that is only ever
 * checked in CI is a budget nobody sees on the machine that is actually slow. [StateDigest]
 * records its build into [AgentTimings] under [DigestBudgets.TIMING_NAME] on every build, and
 * `diag.system_timings` publishes it - so an agent can ask what its own polling is costing the
 * game it is debugging, which is the only way that number stays honest.
 *
 * ## What is deliberately missing, and why it is not stubbed
 *
 * Issue #72 asks `system_timings` for **per-Fleks-system** tick cost. `udea-core` has no
 * per-system instrumentation: `WorldSimulation.step` runs the Fleks world and records nothing,
 * and adding a timing hook to the simulation is the core epic's change to make, not this
 * module's. So this tool publishes exactly the timers that exist - every slot anything has
 * recorded into [AgentTimings] - and says how many that is. It does not invent a per-system
 * breakdown, and it does not report zeroes for systems nobody measured: a diagnostic surface
 * that reports a confident zero for something it never looked at is worse than one that is
 * short, because the zero gets believed.
 */
public class DiagToolset(
    private val bridge: AgentBridge,
    private val clock: SimClock,
    private val timings: AgentTimings,
    private val census: EntityCensus,
    /** The digest, for its build cost and document size. `null` for a host that publishes another way. */
    private val digest: StateDigest? = null,
    /** The barrier, for its drain counters. `null` where the host has not wired one in. */
    private val barrier: SimBarrier? = null,
) {

    @AgentTool(
        name = "diag.frame_report",
        description = "One snapshot of the engine's own health: tick, frame, command " +
            "queue depth, event ring occupancy, digest cost against its budget and " +
            "barrier drain counters. Reach for it first when the game feels wrong but " +
            "nothing has failed.",
    )
    public fun frameReport(): AgentResult = AgentResult.ok {
        put("tick", clock.tick.value)
        put("frame", bridge.frame)
        put("pendingCommands", bridge.pendingCommands)
        put("completedCommandId", bridge.completedCommandId())
        put("eventsHeld", bridge.events.size)
        put("eventsRecorded", bridge.events.totalRecorded)
        obj("digest") {
            if (digest == null) {
                put("wired", false)
            } else {
                put("wired", true)
                put("builds", digest.builds)
                put("lastBuildNanos", digest.lastBuildNanos)
                put("budgetNanos", DigestBudgets.BUILD_NANOS)
                put("lastLength", digest.lastLength)
                put("maxBytes", DigestBudgets.MAX_BYTES)
                put("lastBuildTick", digest.lastBuildTick)
            }
        }
        obj("barrier") {
            if (barrier == null) {
                put("wired", false)
            } else {
                put("wired", true)
                put("drainedThisTick", barrier.drainedThisTick)
                put("totalDrained", barrier.totalDrained)
                // Non-zero means a mutation threw inside a drain and the log line named it.
                // Surfaced here because otherwise the only trace is a log nobody is reading.
                put("failedActions", barrier.failedActions)
                put("pending", barrier.pendingCount())
            }
        }
    }

    @AgentTool(
        name = "diag.system_timings",
        description = "Every timer the engine has recorded into, including the state " +
            "digest build - which is what the agent surface costs the game you are " +
            "debugging. Read it when a tool call seems to be slowing the simulation down.",
    )
    public fun systemTimings(): AgentResult = AgentResult.ok {
        put("tick", clock.tick.value)
        put("registered", timings.size)
        // Stated rather than implied: a caller that expected a line per Fleks system needs to
        // know the difference between "this system cost nothing" and "nothing timed it".
        put(
            "note",
            "these are the timers something has recorded into; udea-core does not instrument " +
                "Fleks systems individually yet, so there is no line per system",
        )
        arr("timings") {
            timings.forEach { name, lastNanos, totalNanos, calls ->
                element {
                    put("name", name)
                    put("lastNanos", lastNanos)
                    put("totalNanos", totalNanos)
                    put("calls", calls)
                }
            }
        }
    }

    @AgentTool(
        name = "diag.entity_counts",
        description = "How many entities exist, broken down by archetype. The total is " +
            "the same entityCount the state digest publishes, so use it to see what a " +
            "population change is actually made of.",
    )
    public fun entityCounts(): AgentResult = AgentResult.ok {
        // The same number the Tier-0 digest publishes as `entityCount`, from the same census,
        // so the two can never disagree about how big the world is.
        put("entityCount", census.entityCount)
        obj("archetypes") {
            census.forEachArchetype(ArchetypeVisitor { archetype, count -> put(archetype, count) })
        }
    }

    /**
     * Heap as the JVM reports it.
     *
     * `Runtime` and not a JMX bean: this is called from a tool, off any per-tick path, and the
     * three numbers below are the ones that answer "is this run about to die" without pulling
     * in a management interface a headless CI container may not have.
     */
    @AgentTool(
        name = "diag.memory",
        description = "Heap used, committed and maximum, as the JVM reports it. Use it " +
            "to tell a leak apart from a slow tick before spending a session on the " +
            "wrong one.",
    )
    public fun memory(): AgentResult {
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory()
        val free = runtime.freeMemory()
        return AgentResult.ok {
            put("usedBytes", total - free)
            put("committedBytes", total)
            put("maxBytes", runtime.maxMemory())
            put("processors", runtime.availableProcessors())
        }
    }

    override fun toString(): String = "DiagToolset(${timings.size} timer(s))"
}
