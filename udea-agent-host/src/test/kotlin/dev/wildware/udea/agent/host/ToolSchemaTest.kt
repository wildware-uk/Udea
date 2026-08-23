package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.tools.DiagToolsetMemoryTool
import dev.wildware.udea.agent.tools.EventsToolsetRecentEventsTool
import dev.wildware.udea.agent.tools.SayToolsetSayTool
import dev.wildware.udea.agent.tools.TimeToolsetRewindTool
import dev.wildware.udea.agent.tools.TimeToolsetStepTool
import dev.wildware.udea.agent.tools.WorldToolsetGetComponentTool
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One schema dialect on the agent surface, checked against the generator that defines it.
 *
 * ## What was wrong
 *
 * Every tool this module publishes is hand-written, and so were its `inputSchema` strings. They
 * had drifted into a dialect of their own: no `$schema`, no `additionalProperties`, an empty
 * `"required":[]` where the generator omits the key, defaults described in prose the generator
 * would have folded in as `(default 0)`, and property descriptions that were a paraphrase of the
 * argument text rather than the argument text. Nothing compared the two, and nothing would have:
 * `game-bridge-mcp`'s parser is deliberately tolerant, so a schema in the wrong shape does not
 * fail anything - it quietly makes a capability harder for a model to call correctly.
 *
 * ## How this test can check a shape it cannot import
 *
 * `udea-codegen` is a KSP processor, so it is on no runtime classpath and `ToolManifest.schemaOf`
 * cannot be called from here. What *is* on the classpath is its **output**: the tool objects it
 * emitted into `udea-agent`, which this module takes as an `api` dependency. So [ToolSchema] is
 * fed a generated tool's own `args` and the result is compared to the string that tool carries.
 * If the emitter's shape moves - a keyword added, a default rendered differently - this fails
 * here, naming the tool, instead of this module publishing last year's dialect for ever.
 *
 * They are named one by one rather than found through `ServiceLoader`, because `udea-agent`
 * publishes no `ToolModule` service of its own - a host registers its generated tools explicitly -
 * so a loader-driven version of this test would find nothing and pass. [GENERATED] is chosen to
 * cover every shape the builder has to reproduce: no arguments at all, required arguments,
 * an optional with a default, and an optional without one.
 */
class ToolSchemaTest {

    /**
     * The load-bearing one: rebuild a **generated** tool's schema from its own `args` and get the
     * generated string back, byte for byte.
     */
    @Test
    fun `the builder reproduces the generator's schema for real generated tools`() {
        for (tool in GENERATED) {
            assertTrue(
                reproducible(tool),
                "${tool.name} uses a generator feature AgentToolArg cannot carry, so it is no " +
                    "longer a fair comparison; pick another tool rather than weakening this.",
            )
            assertEquals(
                tool.inputSchema,
                ToolSchema.of(tool.args),
                "${tool.name}: this module's schema builder no longer agrees with udea-codegen. " +
                    "Whichever moved, the two are a single dialect and must be brought back " +
                    "together here.",
            )
        }
    }

    /**
     * Every hand-written tool in this module publishes a schema derived from its own `args`.
     *
     * The assertion is equality with the derivation rather than a shape check, because a shape
     * check passes for a schema that describes the wrong arguments. This one fails the moment a
     * declaration adds an argument its schema does not mention, which is the drift that let
     * `afterTick` live in a schema after it had stopped meaning anything.
     */
    @Test
    fun `every tool this module publishes derives its schema from its arguments`() {
        assertTrue(AgentHostTools.tools.isNotEmpty(), "this module publishes no tools at all")
        for (tool in AgentHostTools.tools) {
            assertEquals(
                ToolSchema.of(tool.args),
                tool.inputSchema,
                "${tool.name} publishes a hand-written schema again",
            )
        }
    }

    /**
     * The four properties the generator's shape has that the old hand-written strings did not.
     *
     * Spelled out rather than left implicit in the equality above: these are what a reader of the
     * manifest actually gets, and a regression in any of them is worth its own failure message.
     */
    @Test
    fun `every published schema is in the generator's dialect`() {
        for (tool in AgentHostTools.tools) {
            val schema = tool.inputSchema
            assertContains(schema, """"${'$'}schema":"${ToolSchema.DIALECT}"""", message = tool.name)
            assertContains(schema, """"additionalProperties":false""", message = tool.name)
            assertTrue(
                """"required":[]""" !in schema,
                "${tool.name} emits an empty required array; the generator omits the key",
            )
            for (arg in tool.args) {
                assertContains(schema, """"${arg.name}":{"type":"${arg.type}"""", message = tool.name)
                if (arg.default != null) {
                    assertContains(schema, "(default ${arg.default})", message = tool.name)
                }
            }
        }
    }

    /** A default is folded into the description; an optional with none says so. */
    @Test
    fun `a default and an optional without one read differently`() {
        val withDefault = AgentToolArg("n", "integer", "How many.", required = false, default = "3")
        val optional = AgentToolArg("n", "integer", "How many.", required = false, default = null)
        val required = AgentToolArg("n", "integer", "How many.", required = true, default = null)

        assertContains(ToolSchema.of(listOf(withDefault)), "How many. (default 3)")
        assertContains(ToolSchema.of(listOf(optional)), "How many. (optional; omit for none)")
        assertContains(ToolSchema.of(listOf(required)), """"required":["n"]""")
        assertTrue(
            "optional" !in ToolSchema.of(listOf(required)),
            "a required argument must not be described as one to omit",
        )
    }

    /** A quote or a backslash in a description must not produce a document nothing can parse. */
    @Test
    fun `text that would break the document is escaped`() {
        val arg = AgentToolArg(
            "path",
            "string",
            """A "quoted" C:\path, and a newline:""" + "\n",
            required = true,
            default = null,
        )

        val schema = ToolSchema.of(listOf(arg))

        assertContains(schema, """\"quoted\"""")
        assertContains(schema, """C:\\path""")
        assertContains(schema, """\n""")
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Whether [tool] can be rebuilt from its `AgentToolArg`s alone.
     *
     * Two generator features live outside that model and are stated here rather than silently
     * tolerated:
     *
     * - an **enum** argument publishes its constants as a JSON Schema `enum`, and `AgentToolArg`
     *   carries no constants;
     * - a **list** argument appends its separator (and any constants) to the description, and
     *   `AgentToolArg` carries no list flag.
     *
     * Both are `udea-codegen`'s to model and neither is reachable from a hand-written declaration
     * in this module, so a tool using them is skipped instead of being asserted against a
     * derivation that could not include them.
     */
    private fun reproducible(tool: AgentToolDef<*>): Boolean =
        """"enum":""" !in tool.inputSchema && LIST_MARKER !in tool.inputSchema

    private companion object {

        /** The phrase `ToolManifest.describedWithDefault` appends for a list argument. */
        const val LIST_MARKER = "Several values, comma separated"

        /**
         * Generated tools whose schemas this module's builder must reproduce exactly.
         *
         * One per shape: `diag.memory` has no arguments, `world.get_component` has two required
         * ones, `time.step` has an optional with a default, `events.recent_events` has both kinds
         * of optional in one document, `say.say` mixes required and defaulted, and `time.rewind`
         * has a single required argument.
         */
        val GENERATED: List<AgentToolDef<*>> = listOf(
            DiagToolsetMemoryTool,
            EventsToolsetRecentEventsTool,
            SayToolsetSayTool,
            TimeToolsetRewindTool,
            TimeToolsetStepTool,
            WorldToolsetGetComponentTool,
        )
    }
}
