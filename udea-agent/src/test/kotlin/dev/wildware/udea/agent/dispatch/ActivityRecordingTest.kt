package dev.wildware.udea.agent.dispatch

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.activity.AgentActivityRing
import dev.wildware.udea.agent.activity.AgentOutcome
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.activity.AnchorKind
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the dispatcher records every call into the activity ring, with the anchor derived from
 * the tool's **declared** arguments (issue #157).
 *
 * The anchor path is deliberately reachable from a hand-written [ToolRegistry], which is why
 * `declaredArgs` is on the interface rather than reached for through a cast to [ToolIndex]: a
 * cast would have made this the one part of dispatch that only a KSP round could exercise.
 */
class ActivityRecordingTest {

    private val bridge = AgentBridge()

    private val tools = DeclaringRegistry()

    private val ctx: GameContext = testGameContext()

    private val world: World = configureWorld { injectables { gameContext(ctx) } }

    private val clock = ManualAgentClock(advancePerCall = 0L)

    private val dispatcher = AgentDispatcher(bridge, tools, DeferredQueue(), clock)

    @Test
    fun `a successful call lands with its tool, arguments, session, tick and duration`() {
        tools.register(
            "world.get_component",
            listOf(arg("id", "integer"), arg("component", "string")),
        ) { AgentResult.ok { put("ok", true) } }
        clock.advancePerCall = 2_000_000L // 2ms across the two readings `invoke` takes
        val sessions = AgentSessions()
        val agent = sessions.intern("claude-a")

        dispatcher.run(
            AgentCommand("world.get_component", mapOf("id" to "266", "component" to "Health"), session = agent),
            world,
            ctx,
        )

        val call = newest()
        assertEquals("world.get_component", call.toolName)
        assertEquals(AgentOutcome.OK, call.outcome)
        assertEquals(agent, call.session)
        assertEquals(ctx.clock.tick.value, call.tick)
        assertEquals(2_000_000L, call.durationNanos)
        assertTrue(call.argDigest.contains("id=266"), "the digest lost the arguments: ${call.argDigest}")
        assertEquals(AnchorKind.ENTITY, call.anchorKind)
        assertEquals(266, call.anchorNetId)
    }

    @Test
    fun `a tool that threw is recorded as failed rather than not at all`() {
        tools.register("world.destroy_entity", listOf(arg("id", "integer"))) {
            throw IllegalStateException("boom")
        }

        dispatcher.run(AgentCommand("world.destroy_entity", mapOf("id" to "3")), world, ctx)

        assertEquals(AgentOutcome.FAILED, newest().outcome)
    }

    @Test
    fun `an unknown tool is recorded, and distinguishably`() {
        // A mis-wired agent and a broken game are different problems, and a human watching the
        // window is the one person who can tell them apart at a glance - if the overlay lets
        // them.
        dispatcher.run(AgentCommand("world.no_such_thing"), world, ctx)

        val call = newest()
        assertEquals("world.no_such_thing", call.toolName)
        assertEquals(AgentOutcome.UNKNOWN, call.outcome)
    }

    @Test
    fun `a refusal is recorded as failed`() {
        tools.register("world.set_component_field", listOf(arg("id", "integer"))) {
            AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, "no")
        }

        dispatcher.run(AgentCommand("world.set_component_field", mapOf("id" to "1")), world, ctx)

        assertEquals(AgentOutcome.FAILED, newest().outcome)
    }

    @Test
    fun `a tool whose declaration names no entity and no point anchors to nothing`() {
        tools.register("world.list_components", emptyList()) { AgentResult.EMPTY }

        dispatcher.run(AgentCommand("world.list_components"), world, ctx)

        assertEquals(AnchorKind.NONE, newest().anchorKind)
    }

    @Test
    fun `a positional tool anchors to the point the caller supplied`() {
        tools.register(
            "world.spawn_blueprint",
            listOf(arg("blueprint", "string"), arg("x", "number"), arg("y", "number")),
        ) { AgentResult.EMPTY }

        dispatcher.run(
            AgentCommand("world.spawn_blueprint", mapOf("blueprint" to "minion", "x" to "8", "y" to "-2.5")),
            world,
            ctx,
        )

        val call = newest()
        assertEquals(AnchorKind.POINT, call.anchorKind)
        assertEquals(8f, call.anchorX)
        assertEquals(-2.5f, call.anchorY)
    }

    @Test
    fun `the declaration is read once per tool, not once per call`() {
        // The anchor rule depends only on the declaration, which cannot change while the process
        // runs. Re-deriving it per call would put a walk over the argument list on the dispatch
        // path for an answer that is fixed.
        tools.register("world.describe_entity", listOf(arg("id", "integer"))) { AgentResult.EMPTY }

        repeat(5) { dispatcher.run(AgentCommand("world.describe_entity", mapOf("id" to "1")), world, ctx) }

        assertEquals(1, tools.declarationReads["world.describe_entity"])
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun arg(name: String, type: String): AgentToolArg =
        AgentToolArg(name, type, "$name of the call", required = true, default = null)

    private fun newest(): Recorded {
        var found: Recorded? = null
        bridge.activity.forEachRecent(1) { call ->
            found = Recorded(
                call.toolName, call.argDigest, call.tick, call.durationNanos, call.outcome,
                call.session, call.anchorKind, call.anchorNetId, call.anchorX, call.anchorY,
            )
        }
        return checkNotNull(found) { "the dispatcher recorded nothing" }
    }

    /** A copy: [AgentActivityRing]'s cursor is reused and must not outlive the visit. */
    private class Recorded(
        val toolName: String,
        val argDigest: String,
        val tick: Long,
        val durationNanos: Long,
        val outcome: AgentOutcome,
        val session: AgentSessionId,
        val anchorKind: AnchorKind,
        val anchorNetId: Int,
        val anchorX: Float,
        val anchorY: Float,
    )

    /** A hand-written registry that publishes declarations, and counts who asked for them. */
    private class DeclaringRegistry : ToolRegistry {

        private val bodies = LinkedHashMap<String, (AgentContext) -> AgentResult>()
        private val args = LinkedHashMap<String, List<AgentToolArg>>()

        /** How many times each tool's declaration has been read. */
        val declarationReads: MutableMap<String, Int> = LinkedHashMap()

        fun register(name: String, declared: List<AgentToolArg>, body: (AgentContext) -> AgentResult) {
            bodies[name] = body
            args[name] = declared
        }

        override fun contains(toolName: String): Boolean = bodies.containsKey(toolName)

        override fun budgetMs(toolName: String): Long = 0L

        override fun declaredArgs(toolName: String): List<AgentToolArg> {
            declarationReads[toolName] = (declarationReads[toolName] ?: 0) + 1
            return args[toolName] ?: emptyList()
        }

        override fun invoke(command: AgentCommand, context: AgentContext): AgentResult =
            bodies.getValue(command.name).invoke(context)
    }
}
