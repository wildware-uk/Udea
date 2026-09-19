package dev.wildware.udea.nav

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.nav.tools.NavToolModules
import dev.wildware.udea.nav.tools.NavToolset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nav.*` driven the way an agent drives it: by name, with string arguments, through a real
 * [ToolIndex] over a real running game.
 *
 * Not by calling `NavToolset.path(...)`, which would prove the Kotlin function works and nothing
 * about the *tool*. What an agent reaches is a generated `AgentToolDef` - its name, its schema, its
 * coercion of `"12"` into a `Float` - resolved against an index that refuses a tool whose toolset
 * was never registered. Every one of those is a place the surface can break while the function
 * stays right.
 */
class NavToolTest {

    private fun scene(): NavScene = NavScene(
        NavGridLayout(originX = -16f, originY = -16f, cellSize = 0.5f, width = 64, height = 64),
    )

    private fun index(scene: NavScene): ToolIndex =
        NavToolModules.wire(ToolIndex.builder(), NavToolset(scene.navigation)).build()

    @Test
    fun `nav path reports a route round a building`() {
        val scene = scene()
        scene.spawnBuilding(x = 0f, y = 0f, halfWidth = 1f, halfDepth = 4f)
        scene.step()
        val tools = index(scene)

        val json = ok(call(tools, "nav.path", "fromX" to "-8", "fromY" to "0", "toX" to "8", "toY" to "0"))

        assertTrue(field(json, "found") == "true", json)
        assertTrue(field(json, "cost").toInt() > 0)
        // The route leaves the straight line: crossing 16m of open ground would be 32 cells, and
        // going round a 8m-tall building costs more than that.
        assertTrue(field(json, "cells").toInt() > 32, "the reported route is $json")
        assertTrue(json.contains("\"route\":[{"), "the route itself is in the answer: $json")
    }

    @Test
    fun `nav path names why there is no route`() {
        val scene = scene()
        // A room with no door.
        for (offset in -4..4) {
            scene.spawnBuilding(x = offset.toFloat(), y = 4f, halfWidth = 0.5f, halfDepth = 0.5f)
            scene.spawnBuilding(x = offset.toFloat(), y = -4f, halfWidth = 0.5f, halfDepth = 0.5f)
            scene.spawnBuilding(x = 4f, y = offset.toFloat(), halfWidth = 0.5f, halfDepth = 0.5f)
            scene.spawnBuilding(x = -4f, y = offset.toFloat(), halfWidth = 0.5f, halfDepth = 0.5f)
        }
        scene.step()
        val tools = index(scene)

        val json = ok(call(tools, "nav.path", "fromX" to "-10", "fromY" to "-10", "toX" to "0", "toY" to "0"))

        assertEquals("false", field(json, "found"))
        assertEquals(NavRouteOutcome.NoRoute.name, field(json, "outcome"))
        assertTrue(field(json, "reason").contains("cannot get from"), json)
    }

    @Test
    fun `nav path refuses a start that is off the grid`() {
        val tools = index(scene())

        val json = ok(call(tools, "nav.path", "fromX" to "-500", "fromY" to "0", "toX" to "0", "toY" to "0"))

        assertEquals(NavRouteOutcome.StartOffGrid.name, field(json, "outcome"))
        assertTrue(field(json, "reason").contains("64x64"), json)
    }

    @Test
    fun `nav path answers a wide unit differently from a narrow one`() {
        val scene = scene()
        // A wall across the map with a one-cell gap in it, at half-metre cells.
        for (index in 0 until 64) {
            if (index == 32) continue
            scene.spawnBuilding(x = 0f, y = -16f + index * 0.5f + 0.25f, halfWidth = 0.25f, halfDepth = 0.25f)
        }
        scene.step()
        val tools = index(scene)

        val narrow = ok(call(tools, "nav.path", "fromX" to "-8", "fromY" to "0", "toX" to "8", "toY" to "0", "radius" to "0.2"))
        val wide = ok(call(tools, "nav.path", "fromX" to "-8", "fromY" to "0", "toX" to "8", "toY" to "0", "radius" to "0.9"))

        assertEquals("true", field(narrow, "found"), narrow)
        assertEquals("false", field(wide, "found"), wide)
        assertEquals(NavRouteOutcome.NoRoute.name, field(wide, "outcome"))
        assertEquals("1", field(narrow, "clearanceCells"))
        assertEquals("2", field(wide, "clearanceCells"))
    }

    @Test
    fun `nav path refuses a radius that is not a size`() {
        val tools = index(scene())

        val result = call(tools, "nav.path", "fromX" to "0", "fromY" to "0", "toX" to "1", "toY" to "1", "radius" to "0")

        assertTrue(result is AgentResult.Failed, "expected a refusal, got $result")
        assertEquals("bad_argument", result.error.kind.id)
    }

    @Test
    fun `nav grid draws the building it was told about`() {
        val scene = scene()
        scene.spawnBuilding(x = 0f, y = 0f, halfWidth = 1f, halfDepth = 1f)
        scene.step()
        val tools = index(scene)

        val json = ok(call(tools, "nav.grid", "centreX" to "0", "centreY" to "0", "halfSpan" to "6", "radius" to "0.2"))

        assertEquals("64", field(json, "width"))
        // A 2m x 2m footprint on half-metre cells: sixteen cells.
        assertEquals("16", field(json, "blockedCells"))
        assertTrue(json.contains("####"), "the picture shows the building: $json")
        assertTrue(json.contains("............."), "and the open ground around it: $json")
    }

    @Test
    fun `nav grid refuses a window wider than it will draw`() {
        val tools = index(scene())

        val result = call(tools, "nav.grid", "halfSpan" to "64")

        assertTrue(result is AgentResult.Failed, "expected a refusal, got $result")
        assertEquals("bad_argument", result.error.kind.id)
    }

    @Test
    fun `every tool the module publishes can be called by name`() {
        // The hand-written list in `NavToolModules` cannot catch an `@AgentTool` nobody added to
        // it, so this walks what the module does publish and dispatches each one: a name in the
        // module with no receiver, or a schema the dispatcher cannot fill, fails here.
        val scene = scene()
        val tools = index(scene)
        val names = NavToolModules.Nav.tools.map { it.name }

        assertEquals(listOf("nav.grid", "nav.path"), names)
        // What each one needs, and nothing more: the generated schemas declare
        // `additionalProperties: false`, so an argument a tool does not name is refused rather
        // than ignored - which this found the first time it was run with one set for both.
        val arguments = mapOf(
            "nav.grid" to emptyArray<Pair<String, String>>(),
            "nav.path" to arrayOf("fromX" to "0", "fromY" to "0", "toX" to "1", "toY" to "1"),
        )
        for (name in names) {
            val result = call(tools, name, *(arguments.getValue(name)))
            assertTrue(result is AgentResult.Ok, "$name answered $result")
        }
    }

    private fun call(index: ToolIndex, name: String, vararg args: Pair<String, String>): AgentResult =
        index.invoke(AgentCommand(name, args.toMap()))

    private fun ok(result: AgentResult): String {
        assertTrue(result is AgentResult.Ok, "expected success, got $result")
        return result.json
    }

    /** One field of a flat JSON object, without adding a JSON parser to this module's tests. */
    private fun field(json: String, name: String): String {
        val key = "\"$name\":"
        val start = json.indexOf(key)
        require(start >= 0) { "no field $name in $json" }
        var cursor = start + key.length
        if (json[cursor] == '"') {
            val end = json.indexOf('"', cursor + 1)
            return json.substring(cursor + 1, end)
        }
        val end = generateSequence(cursor) { it + 1 }.first { json[it] == ',' || json[it] == '}' || json[it] == ']' }
        return json.substring(cursor, end)
    }
}
