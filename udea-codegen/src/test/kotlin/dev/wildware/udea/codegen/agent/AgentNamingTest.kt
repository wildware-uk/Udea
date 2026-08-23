package dev.wildware.udea.codegen.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one rule that turns a Kotlin identifier into a name an agent types.
 *
 * It is fixed in one place rather than left to each author because a manifest holding both
 * `spawnBlueprint` and `spawn_blueprint` holds one tool spelled two ways, and the collision
 * check can only see that if a single rule produced both.
 */
class AgentNamingTest {

    @Test
    fun `a camelCase function name becomes lower_snake_case`() {
        assertEquals("spawn_blueprint", AgentNaming.snakeCase("spawnBlueprint"))
        assertEquals("reset", AgentNaming.snakeCase("reset"))
        assertEquals("step_n_ticks", AgentNaming.snakeCase("stepNTicks"))
    }

    @Test
    fun `a trailing acronym stays one word and a following word is split off it`() {
        // Without the second boundary rule `parseHTTPHeader` becomes `parse_httpheader`, which
        // is a name no reader would guess and no author would type.
        assertEquals("spawn_npc", AgentNaming.snakeCase("spawnNPC"))
        assertEquals("parse_http_header", AgentNaming.snakeCase("parseHTTPHeader"))
    }

    @Test
    fun `a digit does not start a new word`() {
        assertEquals("step2_ticks", AgentNaming.snakeCase("step2Ticks"))
    }

    @Test
    fun `every derived name is one the format accepts`() {
        for (identifier in listOf("spawnBlueprint", "reset", "stepNTicks", "spawnNPC", "parseHTTPHeader")) {
            val name = AgentNaming.snakeCase(identifier)
            assertTrue(
                AgentNaming.NAME_FORMAT.matches(name),
                "'$name' from '$identifier' does not match ${AgentNaming.NAME_FORMAT.pattern}",
            )
        }
    }

    @Test
    fun `the format rejects the spellings a manifest must not contain`() {
        // The check that makes the rule enforceable rather than decorative.
        for (rejected in listOf("spawnBlueprint", "Spawn_blueprint", "2step", "spawn-blueprint", "")) {
            assertTrue(!AgentNaming.NAME_FORMAT.matches(rejected), "'$rejected' should not be legal")
        }
    }

    @Test
    fun `generated object names are unique per declaring type and function`() {
        // Two toolsets in one package may each declare `reset`; the owner's name is what keeps
        // the two generated objects apart.
        assertEquals("PlaygroundResetTool", AgentNaming.toolObjectName("Playground", "reset"))
        assertEquals("ArenaResetTool", AgentNaming.toolObjectName("Arena", "reset"))
        assertEquals("MatchClockAgentState", AgentNaming.stateObjectName("MatchClock"))
    }
}
