package dev.wildware.udea.codegen.agent

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.codegen.fixtures.Playground
import dev.wildware.udea.codegen.fixtures.PlaygroundSetOverlaysTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSetStanceTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSpawnBlueprintTool
import dev.wildware.udea.codegen.fixtures.PlaygroundTagEntityTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated tools, reached the way an agent actually reaches them: through
 * `game-bridge-mcp`'s own argument encoding.
 *
 * `ToolManifestBridgeParserTest` re-applies the bridge's **manifest** parser, and it can say
 * nothing about this. Between the manifest and the game there is a second translation —
 * `call_tool` turning an MCP `arguments` object into the query string `GET /command` carries —
 * and a schema that disagrees with *that* is invisible to every test reading only one side of
 * it. It is also the place this generator got it wrong: a list argument published as `array`
 * arrives here as JSON text and was split on the comma inside it, handing the tool `["carry`
 * and `"mid"]` and reporting `ok:true`.
 */
class ToolArgumentEncodingTest {

    private val tools: List<AgentToolDef<Playground>> = listOf(
        PlaygroundSpawnBlueprintTool,
        PlaygroundSetStanceTool,
        PlaygroundTagEntityTool,
        PlaygroundSetOverlaysTool,
    )

    @Test
    fun `a call written the way the schema describes survives the bridge's encoding`() {
        // One argument of every published type, as the JSON value a schema-conforming client
        // would send, put through `encode` and dispatched. Nothing here converts on the way in:
        // whatever `encode` produces is what the dispatcher is handed.
        val playground = Playground()

        val spawned = PlaygroundSpawnBlueprintTool.invoke(
            playground,
            encode("spawn_blueprint", mapOf("blueprint" to "creep_melee", "count" to 3, "scale" to 2.0f)),
        )
        assertEquals(6, spawned)

        val tagged = PlaygroundTagEntityTool.invoke(
            playground,
            // `labels` is published `string`, so this is the value the schema asks for.
            encode("tag_entity", mapOf("target" to 3, "labels" to "carry,mid,focus")),
        )
        assertEquals(3 + 3, tagged)

        val overlays = PlaygroundSetOverlaysTool.invoke(
            playground,
            encode("set_overlays", mapOf("visible" to "true,0,1", "holds" to "4,0,0")),
        )
        assertEquals(2 + 4, overlays, "two of three overlays on, plus a four-tick hold")
    }

    @Test
    fun `an omitted optional argument is dropped by the encoding rather than sent as null`() {
        // `if (v === undefined || v === null) continue` - the bridge never puts a null on the
        // query string, so "absent" is the only way an optional argument can arrive absent, and
        // `"scale" in command` is the dispatcher reading exactly that.
        val command = encode("spawn_blueprint", mapOf("blueprint" to "ward", "scale" to null))

        assertTrue("scale" !in command, "a null argument must not reach the query string")
        assertEquals(1, PlaygroundSpawnBlueprintTool.invoke(Playground(), command))
    }

    @Test
    fun `an array argument would arrive as JSON text, which is why no tool publishes one`() {
        // The defect, demonstrated rather than described. If a list were published as `array`,
        // a conforming client would send a JSON array, `encode` would stringify it, and the
        // dispatcher's comma split would tear the JSON apart into values nobody sent - as
        // `ok:true`, because every piece is still a valid String.
        val encoded = encode("tag_entity", mapOf("labels" to listOf("carry", "mid"))).args.getValue("labels")

        assertEquals("[\"carry\",\"mid\"]", encoded, "a JS array is an object, so it is JSON.stringify'd")
        assertEquals(listOf("[\"carry\"", "\"mid\"]"), encoded.split(','), "what the dispatcher would see")

        // So no argument may be published as one. The schema is the enforcement: a client
        // validating `inputSchema` will not hand the bridge an array in the first place.
        for (tool in tools) {
            for (arg in tool.args) {
                assertTrue(arg.type != "array", "${tool.name}.${arg.name} is typed array")
            }
        }
    }

    @Test
    fun `every published argument type is one this encoding can carry`() {
        // A query string carries text. `integer`, `number`, `boolean` and `string` all reach it
        // through `String(v)` unchanged; `object` and `array` do not, and are the two a
        // generated schema must never name.
        val carried = setOf("string", "integer", "number", "boolean")
        val offenders = tools.flatMap { tool -> tool.args.map { "${tool.name}.${it.name}: ${it.type}" to it.type } }
            .filterNot { (_, type) -> type in carried }
            .map { (label, _) -> label }

        assertEquals(emptyList(), offenders)
    }

    /**
     * `game-bridge-mcp`'s `GameClient.command`, reproduced:
     *
     * ```ts
     * const qs = new URLSearchParams({ cmd: name });
     * for (const [k, v] of Object.entries(args)) {
     *   if (v === undefined || v === null) continue;
     *   qs.set(k, typeof v === "object" ? JSON.stringify(v) : String(v));
     * }
     * ```
     *
     * Reproduced and not described, because the whole point of this class is that this is what
     * *actually arrives*: a description of it agrees with the code by assertion, and this
     * agrees with it by construction.
     */
    private fun encode(tool: String, arguments: Map<String, Any?>): AgentCommand {
        val query = LinkedHashMap<String, String>()
        for ((key, value) in arguments) {
            if (value == null) continue
            query[key] = if (value is List<*> || value is Map<*, *>) stringify(value) else asJsString(value)
        }
        return AgentCommand(name = tool, args = query)
    }

    /** `String(v)` for the scalars a JSON argument can hold. */
    private fun asJsString(value: Any): String = when (value) {
        // JS has one number type and prints 2 for 2.0, which is exactly the text a model's
        // `"scale": 2` reaches the game as.
        is Float -> if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        is Double -> if (value == value.toInt().toDouble()) value.toInt().toString() else value.toString()
        else -> value.toString()
    }

    /** `JSON.stringify`, for the shapes an MCP `arguments` object can hold. */
    private fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean, is Number -> asJsString(value)
        is List<*> -> value.joinToString(",", "[", "]", transform = ::stringify)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${stringify(it.key.toString())}:${stringify(it.value)}" }
        else -> stringify(value.toString())
    }
}
