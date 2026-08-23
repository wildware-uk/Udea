package dev.wildware.udea.codegen.agent

import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.codegen.fixtures.PlaygroundSetOverlaysTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSetStanceTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSpawnBlueprintTool
import dev.wildware.udea.codegen.fixtures.PlaygroundTagEntityTool
import dev.wildware.udea.codegen.fixtures.Stance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated `inputSchema` is JSON Schema an MCP client will accept, and it describes the
 * call the dispatcher actually makes.
 *
 * Two distinct claims, and both are load-bearing. A schema that is not valid JSON Schema is
 * rejected by a strict client and the tool disappears from the agent's list; a schema that is
 * valid but describes a different call is worse, because the agent believes it and the call
 * fails at run time with the model having done nothing wrong.
 */
class GeneratedToolSchemaTest {

    private val tools: List<AgentToolDef<*>> = listOf(
        PlaygroundSpawnBlueprintTool,
        PlaygroundSetStanceTool,
        PlaygroundTagEntityTool,
        PlaygroundSetOverlaysTool,
    )

    @Test
    fun `every generated schema is a JSON object schema in the draft the bridge expects`() {
        for (tool in tools) {
            val schema = TestJson.obj(TestJson.parse(tool.inputSchema))
            assertEquals(
                ToolManifestFacts.SCHEMA_DIALECT,
                schema["\$schema"],
                "${tool.name} declares the wrong dialect",
            )
            assertEquals("object", schema["type"], "${tool.name} must be an object schema")
            assertEquals(false, schema["additionalProperties"], "${tool.name} must be closed")
        }
    }

    @Test
    fun `every property carries a JSON Schema type name and a description`() {
        for (tool in tools) {
            val properties = TestJson.obj(TestJson.obj(TestJson.parse(tool.inputSchema))["properties"])
            assertTrue(properties.isNotEmpty(), "${tool.name} publishes no properties")
            for ((name, raw) in properties) {
                val property = TestJson.obj(raw)
                assertTrue(
                    property["type"] in JSON_TYPES,
                    "${tool.name}.$name has type ${property["type"]}, which is not a JSON Schema type",
                )
                val description = property["description"] as? String
                assertTrue(
                    !description.isNullOrBlank(),
                    "${tool.name}.$name has no description; the model has nothing to reason over",
                )
            }
        }
    }

    @Test
    fun `required names only properties the schema declares`() {
        // A `required` entry with no matching property is a schema no input can ever satisfy,
        // and a strict validator rejects every call.
        for (tool in tools) {
            val schema = TestJson.obj(TestJson.parse(tool.inputSchema))
            val properties = TestJson.obj(schema["properties"]).keys
            val required = (schema["required"] as? List<*>).orEmpty().map { it as String }
            assertTrue(properties.containsAll(required), "${tool.name}: $required vs $properties")
        }
    }

    @Test
    fun `the schema and the published args describe the same arguments`() {
        // The one-model claim: three outputs come from one `ToolModel`, so a property the args
        // list does not mention cannot exist. This is what would fail if the schema emitter and
        // the manifest emitter were ever allowed to drift apart.
        for (tool in tools) {
            val schema = TestJson.obj(TestJson.parse(tool.inputSchema))
            val properties = TestJson.obj(schema["properties"])
            assertEquals(
                tool.args.map { it.name },
                properties.keys.toList(),
                "${tool.name}: args and schema properties disagree",
            )
            for (arg in tool.args) {
                assertEquals(
                    arg.type,
                    TestJson.obj(properties[arg.name])["type"],
                    "${tool.name}.${arg.name}: args type and schema type disagree",
                )
            }
            assertEquals(
                tool.args.filter { it.required }.map { it.name },
                (schema["required"] as? List<*>).orEmpty().map { it as String },
                "${tool.name}: args required flags and schema required list disagree",
            )
        }
    }

    @Test
    fun `an enum argument publishes its constants so the model never has to guess one`() {
        val stance = TestJson.obj(
            TestJson.obj(TestJson.obj(TestJson.parse(PlaygroundSetStanceTool.inputSchema))["properties"])["stance"],
        )

        assertEquals("string", stance["type"])
        assertEquals(Stance.entries.map { it.name }, TestJson.arr(stance["enum"]))
    }

    @Test
    fun `a list argument is published as the comma-separated string the query string carries`() {
        // `array` here would be the schema instructing a model to do something the transport
        // cannot do. A tool call reaches the game as `GET /command?...`, and `game-bridge-mcp`
        // puts each argument on it with `String(v)` unless it is an object - a JSON array is
        // one, so an `array` argument arrives as the text `["carry","mid"]` for the
        // dispatcher's comma split to tear in half. ToolArgumentEncodingTest drives that
        // encoding for real; this pins the schema that keeps it from ever happening.
        val labels = TestJson.obj(
            TestJson.obj(TestJson.obj(TestJson.parse(PlaygroundTagEntityTool.inputSchema))["properties"])["labels"],
        )

        assertEquals("string", labels["type"])
        assertTrue("items" !in labels.keys, "a string property has no items: $labels")
        // The separator has nowhere else to live: a list travels as one query parameter.
        assertTrue("comma separated" in labels["description"] as String, labels.toString())
    }

    @Test
    fun `no argument anywhere is published as an array`() {
        // The rule and not the instance: a list argument added later must not reintroduce the
        // one JSON Schema type a query string cannot carry.
        val arrays = tools.flatMap { tool -> tool.args.map { "${tool.name}.${it.name}" to it.type } }
            .filter { (_, type) -> type == "array" }

        assertEquals(emptyList(), arrays, "arguments typed `array` cannot survive the bridge")
    }

    @Test
    fun `a default is folded into the description rather than emitted as a typed default`() {
        // The bridge does exactly this when it builds a schema itself, and says why: a `default`
        // on a strictly-typed property is something a strict client is entitled to reject, and
        // the text is what the model reads anyway.
        val count = TestJson.obj(
            TestJson.obj(TestJson.obj(TestJson.parse(PlaygroundSpawnBlueprintTool.inputSchema))["properties"])["count"],
        )

        assertTrue("(default 1)" in count["description"] as String, count.toString())
        assertTrue("default" !in count.keys, "the schema must not carry a default key: $count")
    }

    @Test
    fun `the schema string is one line, so an unrelated edit is not a multi-line diff`() {
        for (tool in tools) {
            assertEquals(-1, tool.inputSchema.indexOf('\n'), "${tool.name}'s schema is multi-line")
        }
    }

    private companion object {
        /** Every type name JSON Schema defines. Anything else is not a type. */
        val JSON_TYPES = setOf("string", "number", "integer", "boolean", "object", "array", "null")
    }
}

/** The generator's own constants, restated so a test failure names a value and not a symbol. */
internal object ToolManifestFacts {
    const val SCHEMA_DIALECT: String = "https://json-schema.org/draft/2020-12/schema"
    const val PROTOCOL: Double = 1.0
}
