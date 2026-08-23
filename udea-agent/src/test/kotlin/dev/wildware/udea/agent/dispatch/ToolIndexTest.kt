package dev.wildware.udea.agent.dispatch

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.BadArgumentException
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ToolIndex]: the join between a generated `ToolModule` and [AgentDispatcher].
 *
 * The defs here are hand-written rather than KSP output on purpose - this module must not need a
 * codegen round to be testable, and every shape the emitter can produce is expressible by hand.
 * The generated article is driven through this same class from `udea-codegen`'s
 * `GeneratedToolIndexTest`, which is where "the emitter and this agree" is proven.
 */
class ToolIndexTest {

    private val ctx: GameContext = testGameContext()

    private val world: World = configureWorld { injectables { gameContext(ctx) } }

    // --- resolution, which all happens in build() ----------------------------------------------

    @Test
    fun `a tool reaches the toolset instance the host registered`() {
        val spawner = Spawner()
        val index = ToolIndex.builder().module(SPAWN_MODULE).toolset(spawner).build()

        val result = call(index, "spawn", "count" to "3")

        assertEquals("3", assertIs<AgentResult.Ok>(result).json)
        assertEquals(3, spawner.spawned, "the call must land on the registered instance")
    }

    @Test
    fun `two toolsets are routed by owner and not by registration order`() {
        val spawner = Spawner()
        val labeller = Labeller()
        // Registered in the opposite order to the module's tool order, so "first wins" fails.
        val index = ToolIndex.builder().module(BOTH_MODULE).toolset(labeller).toolset(spawner).build()

        call(index, "spawn", "count" to "2")
        call(index, "label", "text" to "hi")

        assertEquals(2, spawner.spawned)
        assertEquals("hi", labeller.label)
    }

    @Test
    fun `a subclass serves a tool declared on its supertype`() {
        val subclass = LoudSpawner()
        val index = ToolIndex.builder().module(SPAWN_MODULE).toolset(subclass).build()

        call(index, "spawn", "count" to "1")

        assertEquals(1, subclass.spawned)
    }

    @Test
    fun `a tool with no registered toolset is refused when the index is built, not on the call`() {
        val failure = assertFailsWith<IllegalStateException> {
            ToolIndex.builder().module(SPAWN_MODULE).build()
        }

        val message = failure.message.orEmpty()
        assertTrue("spawn" in message, message)
        assertTrue(
            Spawner::class.qualifiedName!! in message,
            "the message must name the class to register: $message",
        )
    }

    @Test
    fun `two instances that both fit one tool are refused rather than silently picked`() {
        val failure = assertFailsWith<IllegalStateException> {
            ToolIndex.builder().module(SPAWN_MODULE).toolset(Spawner()).toolset(LoudSpawner()).build()
        }

        assertTrue("2 registered instances" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `two modules publishing one tool name are refused, naming both`() {
        val failure = assertFailsWith<IllegalStateException> {
            ToolIndex.builder()
                .module(SPAWN_MODULE)
                .module(RIVAL_MODULE)
                .toolset(Spawner())
                .build()
        }

        val message = failure.message.orEmpty()
        assertTrue("spawn" in message, message)
        assertTrue("Engine" in message && "Rival" in message, "both modules must be named: $message")
    }

    // --- serving ------------------------------------------------------------------------------

    @Test
    fun `an unknown name is no_such_tool rather than an exception`() {
        val index = ToolIndex.builder().module(SPAWN_MODULE).toolset(Spawner()).build()

        assertEquals(false, index.contains("teleport"))
        val failed = assertIs<AgentResult.Failed>(call(index, "teleport"))
        assertEquals(AgentErrorKind.NO_SUCH_TOOL, failed.error.kind)
    }

    @Test
    fun `a generated coercion failure travels as BadArgumentException for the dispatcher to map`() {
        val index = ToolIndex.builder().module(SPAWN_MODULE).toolset(Spawner()).build()

        // Deliberately not caught here: AgentDispatcher owns the mapping to
        // ok:false/bad_argument, and a second place that caught it would be a second answer to
        // the same failure.
        assertFailsWith<BadArgumentException> { call(index, "spawn", "count" to "many") }
    }

    @Test
    fun `tools are listed ascending by name whatever order they arrived in`() {
        val index = ToolIndex.builder().module(BOTH_MODULE).toolset(Spawner()).toolset(Labeller()).build()

        assertEquals(listOf("label", "spawn"), index.tools.map { it.name })
        assertEquals(listOf("Engine"), index.moduleNames)
    }

    @Test
    fun `no generated tool declares a budget, so none is reported`() {
        val index = ToolIndex.builder().module(SPAWN_MODULE).toolset(Spawner()).build()

        assertEquals(0L, index.budgetMs("spawn"))
    }

    // --- rendering the return value ------------------------------------------------------------

    @Test
    fun `every scalar a tool can return renders as a JSON value`() {
        val index = returnsIndex()

        assertEquals("\"text\"", ok(index, "returns_string"))
        assertEquals("7", ok(index, "returns_int"))
        assertEquals("8", ok(index, "returns_long"))
        assertEquals("1.5", ok(index, "returns_float"))
        assertEquals("true", ok(index, "returns_boolean"))
        assertEquals("{}", ok(index, "returns_unit"))
        assertEquals("{}", ok(index, "returns_null"))
    }

    @Test
    fun `a string return is escaped rather than spliced in raw`() {
        // A tool returning text with a quote in it would otherwise produce a document no parser
        // accepts, and the agent would read a healthy game as broken.
        assertEquals("\"he \\\"said\\\" it\"", ok(returnsIndex(), "returns_quotes"))
    }

    @Test
    fun `an AgentResult a tool built itself is passed straight through`() {
        assertEquals("""{"spawned":2}""", ok(returnsIndex(), "returns_result"))
    }

    @Test
    fun `a return type that is not a JSON value is reported, not stringified`() {
        val failed = assertIs<AgentResult.Failed>(call(returnsIndex(), "returns_object"))

        assertEquals(ToolIndex.UNRENDERABLE_RESULT, failed.error.kind)
        assertTrue(
            Receipt::class.qualifiedName!! in failed.error.message,
            "the message must name the offending type: ${failed.error.message}",
        )
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun returnsIndex(): ToolIndex =
        ToolIndex.builder().module(RETURNS_MODULE).toolset(Returns()).build()

    private fun call(index: ToolIndex, tool: String, vararg args: Pair<String, String>): AgentResult {
        val command = AgentCommand(tool, args.toMap())
        return index.invoke(command, AgentContext(world, ctx, command, DeferredQueue(), AgentBridge()))
    }

    private fun ok(index: ToolIndex, tool: String): String =
        assertIs<AgentResult.Ok>(call(index, tool)).json

    // --- fixtures ------------------------------------------------------------------------------

    private open class Spawner {
        var spawned: Int = 0

        fun spawn(count: Int): Int {
            spawned += count
            return spawned
        }
    }

    private class LoudSpawner : Spawner()

    private class Labeller {
        var label: String = ""
    }

    /** Not a JSON value, and the reason [ToolIndex] refuses to `toString` a return value. */
    private class Receipt(val id: Int)

    private class Returns {
        fun quotes(): String = "he \"said\" it"
    }

    /** The hand-written equivalent of what `ToolEmitter` produces for one function. */
    private class Def<T : Any>(
        override val name: String,
        override val owner: KClass<*>,
        /**
         * What this stand-in accepts, and it has to be the truth.
         *
         * A generated tool's `args` is the list its `inputSchema` was rendered from, and
         * `ToolIndex.invoke` refuses a call naming anything outside it - the schema publishes
         * `additionalProperties: false` and nothing used to enforce it. A fixture that read
         * `count` while declaring no arguments would be a fixture asserting that the manifest
         * may lie, so it declares what it reads.
         */
        private val accepts: List<String> = emptyList(),
        private val call: (T, AgentCommand) -> Any?,
    ) : AgentToolDef<T> {
        override val description: String = "A hand-written stand-in for a generated $name."
        override val args: List<AgentToolArg> = accepts.map {
            AgentToolArg(it, "string", "The $it this stand-in reads.", required = true, default = null)
        }
        override val inputSchema: String = """{"type":"object","properties":{}}"""
        override fun invoke(receiver: T, command: AgentCommand): Any? = call(receiver, command)
    }

    private class Module(
        override val moduleName: String,
        override val tools: List<AgentToolDef<*>>,
    ) : ToolModule

    private companion object {
        val SPAWN = Def<Spawner>("spawn", Spawner::class, listOf("count")) { spawner, command ->
            spawner.spawn(command.int("count"))
        }

        val LABEL = Def<Labeller>("label", Labeller::class, listOf("text")) { labeller, command ->
            labeller.label = command.str("text")
        }

        val SPAWN_MODULE = Module("Engine", listOf<AgentToolDef<*>>(SPAWN))
        val RIVAL_MODULE = Module("Rival", listOf<AgentToolDef<*>>(SPAWN))
        val BOTH_MODULE = Module("Engine", listOf<AgentToolDef<*>>(SPAWN, LABEL))

        val RETURNS_MODULE = Module(
            "Engine",
            listOf<AgentToolDef<*>>(
                Def<Returns>("returns_string", Returns::class) { _, _ -> "text" },
                Def<Returns>("returns_quotes", Returns::class) { returns, _ -> returns.quotes() },
                Def<Returns>("returns_int", Returns::class) { _, _ -> 7 },
                Def<Returns>("returns_long", Returns::class) { _, _ -> 8L },
                Def<Returns>("returns_float", Returns::class) { _, _ -> 1.5f },
                Def<Returns>("returns_boolean", Returns::class) { _, _ -> true },
                Def<Returns>("returns_unit", Returns::class) { _, _ -> Unit },
                Def<Returns>("returns_null", Returns::class) { _, _ -> null },
                Def<Returns>("returns_object", Returns::class) { _, _ -> Receipt(1) },
                Def<Returns>("returns_result", Returns::class) { _, _ ->
                    AgentResult.Ok(Json.render { put("spawned", 2) })
                },
            ),
        )
    }
}
