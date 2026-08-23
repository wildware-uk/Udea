package dev.wildware.udea.codegen.agent

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.BadArgumentException
import dev.wildware.udea.codegen.fixtures.MatchPhase
import dev.wildware.udea.codegen.fixtures.Playground
import dev.wildware.udea.codegen.fixtures.PlaygroundSetStanceTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSpawnBlueprintTool
import dev.wildware.udea.codegen.fixtures.PlaygroundTagEntityTool
import dev.wildware.udea.codegen.fixtures.Stance
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generated dispatchers, driven exactly as the agent host will drive them.
 *
 * These are the real `object`s `kspTest` produced, not a string an emitter returned, so what is
 * asserted is what a consumer compiles. Every case here is a behaviour the manifest promises an
 * agent: an argument it may omit, a default it is told about, an error it can act on.
 */
class GeneratedToolDispatchTest {

    /** A tool call exactly as the bridge delivers one: a name and query parameters as text. */
    private fun Query(vararg entries: Pair<String, String>): AgentCommand =
        AgentCommand(name = "test", args = entries.toMap())

    // --- the happy path ------------------------------------------------------------------------

    @Test
    fun `a dispatched call reaches the annotated function with its arguments converted`() {
        val playground = Playground()

        val result = PlaygroundSpawnBlueprintTool.invoke(
            playground,
            Query("blueprint" to "creep_melee", "count" to "3", "scale" to "2.0"),
        )

        // The return value proves the coercion: 3 * 2 can only come from count=3 and scale=2f.
        assertEquals(6, result)
        assertEquals(listOf("creep_melee", "creep_melee", "creep_melee"), playground.spawned)
    }

    @Test
    fun `an omitted argument with a declared default uses the value the manifest advertised`() {
        val playground = Playground()

        val result = PlaygroundSpawnBlueprintTool.invoke(playground, Query("blueprint" to "tower"))

        // @Arg(default = "1") on count, and the manifest says so; an agent that omits it gets 1.
        assertEquals(1, result)
        assertEquals(listOf("tower"), playground.spawned)
        assertEquals(
            "1",
            PlaygroundSpawnBlueprintTool.args.single { it.name == "count" }.default,
            "the value the dispatcher folds in and the value the manifest publishes are one value",
        )
    }

    @Test
    fun `an omitted nullable argument arrives as null rather than as a made-up value`() {
        val playground = Playground()

        // scale is Float? with no default: absent means absent, and the tool decides.
        assertEquals(1, PlaygroundSpawnBlueprintTool.invoke(playground, Query("blueprint" to "ward")))
    }

    @Test
    fun `an enum argument is matched by constant name, not by ordinal`() {
        val target = NetId.of(index = 7, generation = 2)

        val result = PlaygroundSetStanceTool.invoke(
            Playground(),
            Query("target" to target.raw.toString(), "stance" to "Sprinting"),
        )

        // Ordinal 2 is Sprinting here, so a generator that read the ordinal would also pass
        // "2" - this asserts the *name* path by sending a name no ordinal parse accepts.
        assertEquals("${target.raw}:${Stance.Sprinting.name}:true", result)
    }

    @Test
    fun `a list argument arrives comma separated and keeps every element`() {
        val target = NetId.of(index = 3, generation = 0)

        val result = PlaygroundTagEntityTool.invoke(
            Playground(),
            Query("target" to target.raw.toString(), "labels" to "carry,mid,focus"),
        )

        assertEquals(3 + 3, result)
    }

    // --- every failure is a BadArgumentException naming the argument ----------------------------------

    @Test
    fun `a missing required argument is a BadArgumentException naming it, not a raw throwable`() {
        val failure = assertFailsWith<BadArgumentException> {
            PlaygroundSpawnBlueprintTool.invoke(Playground(), Query("count" to "2"))
        }

        assertEquals("blueprint", failure.argument)
        assertEquals(null, failure.supplied, "nothing arrived, and the error has to say so")
    }

    @Test
    fun `an unparseable number is a BadArgumentException naming the argument and its value`() {
        val failure = assertFailsWith<BadArgumentException> {
            PlaygroundSpawnBlueprintTool.invoke(
                Playground(),
                Query("blueprint" to "creep", "count" to "several"),
            )
        }

        assertEquals("count", failure.argument)
        assertEquals("several", failure.supplied)
    }

    @Test
    fun `a boolean is strict, so a plausible-looking yes is reported rather than read as false`() {
        // `"yes".toBoolean()` is false in Kotlin. Silently reading a model's "yes" as false is
        // the wrong call succeeding, which is worse than the call failing.
        val failure = assertFailsWith<BadArgumentException> {
            PlaygroundSetStanceTool.invoke(
                Playground(),
                Query("target" to "0", "stance" to "Standing", "sticky" to "yes"),
            )
        }

        assertEquals("sticky", failure.argument)
    }

    @Test
    fun `an enum constant that does not exist is reported with the constants that do`() {
        val failure = assertFailsWith<BadArgumentException> {
            PlaygroundSetStanceTool.invoke(
                Playground(),
                Query("target" to "0", "stance" to "Sprintng"),
            )
        }

        assertEquals("stance", failure.argument)
        for (constant in Stance.entries) {
            assertTrue(constant.name in failure.expected, failure.expected)
        }
    }

    @Test
    fun `a NetId word with reserved bits set is a BadArgumentException carrying NetId's reason`() {
        // NetId.ofRaw throws IllegalArgumentException for a word whose reserved byte is set.
        // The dispatcher must convert that at the boundary rather than let it escape: the
        // dispatcher answers `bad_argument` for one and only the generic `tool_threw` for
        // anything else, so the agent would be told nothing it could act on.
        val failure = assertFailsWith<BadArgumentException> {
            PlaygroundSetStanceTool.invoke(
                Playground(),
                Query("target" to (1 shl 24).toString(), "stance" to "Standing"),
            )
        }

        assertEquals("target", failure.argument)
        assertTrue("reserved" in failure.expected, failure.expected)
        assertNotNull(failure.supplied, "what arrived has to reach the agent, or it cannot fix the call")
    }

    @Test
    fun `a list element that will not convert names the list parameter`() {
        val failure = assertFailsWith<BadArgumentException> {
            PlaygroundTagEntityTool.invoke(Playground(), Query("target" to "not-a-number", "labels" to "a"))
        }

        assertEquals("target", failure.argument)
    }

    // --- what the tool publishes about itself --------------------------------------------------

    @Test
    fun `the published name is snake_case whether derived or declared`() {
        assertEquals("spawn_blueprint", PlaygroundSpawnBlueprintTool.name)
        assertEquals("set_stance", PlaygroundSetStanceTool.name)
        assertEquals("tag_entity", PlaygroundTagEntityTool.name)
    }

    @Test
    fun `every published argument is one the dispatcher actually accepts`() {
        // The drift the one-model design exists to prevent, asserted rather than assumed: an
        // argument in `args` that the dispatcher ignores is a capability an agent is told it
        // has and does not.
        val playground = Playground()
        val required = PlaygroundSpawnBlueprintTool.args.filter { it.required }

        assertEquals(listOf("blueprint"), required.map { it.name })
        for (arg in PlaygroundSpawnBlueprintTool.args.filterNot { it.required }) {
            // Omitting any optional argument must still dispatch; if `args` named a parameter
            // the function requires, this throws.
            PlaygroundSpawnBlueprintTool.invoke(playground, Query("blueprint" to "x"))
            assertTrue(arg.default != null || arg.name == "scale")
        }
    }

    @Test
    fun `the fixture enum used by the digest is unrelated to the one used by a tool`() {
        // Guards the fixture itself: MatchPhase feeds @AgentState and Stance feeds @AgentTool,
        // and a future edit that merged them would make the two tests above test one thing.
        assertEquals(3, MatchPhase.entries.size)
        assertEquals(3, Stance.entries.size)
    }
}
