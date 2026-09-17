package dev.wildware.udea.agent.dispatch

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.loop.simBarrier

/**
 * A world, a barrier, a bridge and a runtime, driven one host iteration at a time.
 *
 * The host loop is the thing under test as much as the dispatcher is, so the harness drives the
 * real sequence - `beforeFrame`, `step`, `afterFrame` - rather than calling the dispatcher
 * directly. A test that invoked the dispatcher itself would prove a tool runs and prove nothing
 * about *when*, which is the entire contract of this issue.
 */
internal class DispatchHarness(
    val tools: TestToolRegistry = TestToolRegistry(),
    val clock: ManualAgentClock = ManualAgentClock(),
    val digest: RecordingPublisher = RecordingPublisher(),
) {
    val bridge: AgentBridge = AgentBridge()

    val barrier: SimBarrier = SimBarrier()

    val ctx: GameContext = testGameContext { simBarrier(barrier) }

    /**
     * Two systems, so "every system saw it" is a claim with more than one witness.
     *
     * Constructed inside the `systems { }` block and captured out, because `SimSystem` resolves
     * its `GameContext` through `World.inject` at construction and that is only legal inside a
     * world configuration scope.
     */
    private var firstSystem: CountingSystem? = null

    private var secondSystem: CountingSystem? = null

    val world: World = configureWorld {
        injectables { gameContext(ctx) }
        systems {
            firstSystem = FirstCountingSystem().also { add(it) }
            secondSystem = SecondCountingSystem().also { add(it) }
        }
    }

    val first: CountingSystem = checkNotNull(firstSystem)

    val second: CountingSystem = checkNotNull(secondSystem)

    val simulation: WorldSimulation = WorldSimulation(ctx, world, barrier)

    val runtime: AgentRuntime = AgentRuntime(bridge, tools, world, ctx, digest, clock)

    /** Whether the simulation is frozen. A `resume` tool sets this back to false. */
    var paused: Boolean = false

    /**
     * One host-loop iteration, in the documented order.
     *
     * When paused the loop takes no steps, so `afterFrame(0)` is what has to drain the barrier
     * - otherwise `resume` can never arrive and the instance is wedged.
     */
    fun hostIteration() {
        runtime.beforeFrame()
        val ticks = if (paused) 0 else 1
        if (!paused) simulation.step()
        runtime.afterFrame(ticks)
    }

    fun run(iterations: Int) {
        repeat(iterations) { hostIteration() }
    }

    /** Submits [name] and returns its command id. */
    fun submit(name: String, args: Map<String, String> = emptyMap()): Long {
        val command = AgentCommand(name, args)
        bridge.submit(command)
        return command.id
    }
}

/**
 * Counts entities once per tick, from inside the tick, and can act mid-tick.
 *
 * Abstract with two named subclasses purely because Fleks rejects two instances of the same
 * system class in one world, and this test needs two witnesses to "every system saw it".
 */
internal abstract class CountingSystem : SimSystem() {

    private val observed = ArrayList<Int>()

    /** Entity count as seen from inside `onTick`, one entry per tick. */
    val entityCountPerTick: List<Int> get() = observed

    /** Runs inside the tick, before the count is taken. */
    var duringTick: (() -> Unit)? = null

    override fun onTick() {
        duringTick?.invoke()
        observed += world.numEntities
    }
}

/** The first witness. */
internal class FirstCountingSystem : CountingSystem()

/** The second witness. */
internal class SecondCountingSystem : CountingSystem()

/** A [ToolRegistry] a test fills in. The generated one lands with the toolset issues. */
internal class TestToolRegistry : ToolRegistry {

    private val tools = LinkedHashMap<String, (AgentContext) -> AgentResult>()
    private val budgets = LinkedHashMap<String, Long>()

    /** Calls made, in order, including ones that threw. */
    val calls: MutableList<String> = ArrayList()

    fun register(name: String, budgetMs: Long = 0L, tool: (AgentContext) -> AgentResult) {
        tools[name] = tool
        budgets[name] = budgetMs
    }

    override fun contains(toolName: String): Boolean = tools.containsKey(toolName)

    override fun budgetMs(toolName: String): Long = budgets[toolName] ?: 0L

    /**
     * Empty: a tool registered here is a lambda, so it has no declared arguments to publish.
     *
     * Stated rather than left to a default on the interface. A registry that answered with
     * something plausible would give the activity ring an anchor rule derived from arguments
     * that do not exist, and the overlay would then be labelling calls after a guess.
     */
    override fun declaredArgs(toolName: String): List<dev.wildware.udea.agent.AgentToolArg> =
        emptyList()

    override fun invoke(command: AgentCommand, context: AgentContext): AgentResult {
        calls += command.name
        return tools.getValue(command.name).invoke(context)
    }
}

/** Wall time a test controls. No sleeps: a budget test that slept would measure the scheduler. */
internal class ManualAgentClock(
    /** Nanoseconds added by every reading, so one call spans exactly this much. */
    var advancePerCall: Long = 0L,
) : AgentClock {

    var nanos: Long = 0L
        private set

    override fun nowNanos(): Long {
        val now = nanos
        nanos += advancePerCall
        return now
    }
}

/** Records that publication happened, and when, without building a real document. */
internal class RecordingPublisher : DigestPublisher {

    /** Something the test appends to from a tool, a system and deferred work. */
    val order: MutableList<String> = ArrayList()

    var publishes: Int = 0
        private set

    override fun publishIfDue() {
        publishes++
        order += "publish"
    }
}
