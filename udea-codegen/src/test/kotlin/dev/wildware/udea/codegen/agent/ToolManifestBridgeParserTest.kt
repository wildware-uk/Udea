package dev.wildware.udea.codegen.agent

import dev.wildware.udea.codegen.ModuleRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated manifest, read the way `game-bridge-mcp` reads it.
 *
 * **A tolerant parser is a silent one.** `normaliseManifest` in `src/manifest.ts` drops a tool
 * whose `name` is missing or not a string, drops a toolset whose name is empty, and reads a
 * `tools` that is not an array as no tools at all — none of which is an error anywhere. So a
 * manifest that this generator gets subtly wrong does not fail: it makes capabilities invisible
 * to the agent, with a green build and a healthy-looking instance.
 *
 * These tests are that parser's rules, re-applied to the real generated file. They are written
 * as *drop* counts rather than as field assertions, because "nothing was dropped" is the claim
 * the bridge's tolerance would otherwise hide.
 */
class ToolManifestBridgeParserTest {

    private val document: Map<String, Any?> by lazy {
        TestJson.obj(TestJson.parse(generatedManifest.readText()))
    }

    @Test
    fun `the generated manifest is valid JSON at all`() {
        // The failure with no symptom: the bridge answers an unparseable manifest by falling
        // back to a built-in one and reporting the instance as `live-no-manifest`, so the game
        // looks healthy and has no tools.
        assertEquals("CodegenFixtures", document["module"])
        assertEquals(ToolManifestFacts.PROTOCOL, document["protocol"])
    }

    @Test
    fun `every toolset survives the bridge's toolset rules`() {
        val toolsets = TestJson.arr(document["toolsets"])
        assertTrue(toolsets.isNotEmpty(), "the fixture module publishes no toolsets")
        for (raw in toolsets) {
            val toolset = TestJson.obj(raw)
            val name = toolset["name"]
            // `if (!name) continue` in the bridge: an empty or non-string name silently
            // discards the whole group.
            assertTrue(name is String && name.isNotEmpty(), "a toolset would be skipped: $toolset")
            assertTrue(toolset["tools"] is List<*>, "`tools` must be an array or it reads as none")
        }
    }

    @Test
    fun `not one generated tool is dropped by the bridge's asToolDef`() {
        val dropped = mutableListOf<String>()
        var kept = 0
        for (raw in TestJson.arr(document["toolsets"])) {
            for (entry in TestJson.arr(TestJson.obj(raw)["tools"])) {
                if (asToolDef(entry) == null) dropped += entry.toString() else kept++
            }
        }

        assertEquals(emptyList(), dropped, "tools the bridge would silently drop")
        assertEquals(6, kept, "the fixture module publishes six tools")
    }

    @Test
    fun `every argument survives the bridge's asArgDefs and carries the five documented fields`() {
        for (tool in allTools()) {
            val args = TestJson.arr(tool["args"])
            assertTrue(args.isNotEmpty(), "${tool["name"]} publishes no args")
            for (raw in args) {
                val arg = TestJson.obj(raw)
                // asArgDefs keeps an entry only if `name` is a string; everything else is
                // optional to the parser and mandatory to us, because the model reads it.
                assertTrue(arg["name"] is String, "an arg would be dropped: $arg")
                assertTrue(arg["type"] is String, "${arg["name"]} has no JSON Schema type")
                assertTrue((arg["description"] as? String).orEmpty().isNotBlank(), "$arg")
                assertTrue(arg["required"] is Boolean, "${arg["name"]} has no required flag")
                assertTrue("default" in arg, "${arg["name"]} must state its default, even as null")
            }
        }
    }

    @Test
    fun `inputSchema is an object, not the string the generated Kotlin holds`() {
        // `if (raw.inputSchema && typeof raw.inputSchema === "object")` — a string here is
        // dropped, and the bridge falls back to building a schema from `args`. That fallback
        // loses `additionalProperties` and the enum constants, so the agent's schema and the
        // dispatcher stop agreeing.
        for (tool in allTools()) {
            assertTrue(
                tool["inputSchema"] is Map<*, *>,
                "${tool["name"]}'s inputSchema must be an object: ${tool["inputSchema"]}",
            )
        }
    }

    @Test
    fun `the fragment merges into a document the bridge reads as one game`() {
        // The fragment is per module by design: no KSP round sees every module, so the agent
        // host merges them. This is that merge, done the way the host will, and read back.
        val merged = buildString {
            append("{\"game\":{\"name\":\"CodegenFixtures\",\"version\":\"0.0.0\",\"protocol\":1},")
            append("\"toolsets\":")
            append(renderToolsets())
            append('}')
        }

        val document = TestJson.obj(TestJson.parse(merged))
        val names = TestJson.arr(document["toolsets"])
            .flatMap { TestJson.arr(TestJson.obj(it)["tools"]) }
            .map { TestJson.obj(it)["name"] }

        // Grouped by toolset and then by name, which is why the `sim.*` pair is contiguous
        // here and interleaved in the ServiceLoader index: the manifest is what a model reads,
        // and a model reads a toolset at a time.
        assertEquals(
            listOf(
                "set_overlays", "set_stance", "spawn_blueprint", "tag_entity",
                "sim.advance", "sim.describe",
            ),
            names,
        )
    }

    @Test
    fun `tool names are unique across the merged document`() {
        // `toolIndex` keeps the *first* definition of a name and silently ignores later ones,
        // so a duplicate is a capability that exists and cannot be reached.
        val names = allTools().map { it["name"] as String }
        assertEquals(names.size, names.toSet().size, "duplicate tool names in $names")
    }

    @Test
    fun `the parser these tests use would actually reject a malformed manifest`() {
        // A conformance test whose parser accepts everything passes vacuously. These are the
        // three failures the assertions above are meant to catch.
        assertEquals(null, asToolDef(mapOf("description" to "no name")))
        assertEquals(null, asToolDef("bare string is a name to the bridge, an object to us"))
        assertTrue(runCatching { TestJson.parse("""{"a": 1,}""") }.isFailure, "trailing comma")
    }

    /** The bridge's `asToolDef`, minus the leniencies this generator does not rely on. */
    private fun asToolDef(value: Any?): Map<String, Any?>? {
        val raw = value as? Map<*, *> ?: return null
        val name = raw["name"] as? String ?: return null
        if (name.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun allTools(): List<Map<String, Any?>> = TestJson.arr(document["toolsets"])
        .flatMap { TestJson.arr(TestJson.obj(it)["tools"]) }
        .map { TestJson.obj(it) }

    private fun renderToolsets(): String {
        val text = generatedManifest.readText()
        val start = text.indexOf("\"toolsets\":")
        return text.substring(text.indexOf('[', start), text.lastIndexOf(']') + 1)
    }

    private companion object {
        val generatedManifest: File = ModuleRoot
            .file("build/generated/ksp/test/resources/udea/CodegenFixtures-agent-tools.json")
            .also {
                check(it.isFile) { "no generated manifest at ${it.absolutePath}; run :udea-codegen:kspTestKotlin" }
            }
    }
}
