package dev.wildware.udea.codegen.agent

/**
 * How a Kotlin identifier becomes a name an agent types.
 *
 * MCP tool names are snake_case by convention across every server a model has seen, and the
 * name is part of what the model reasons over, so the derivation is fixed here rather than
 * left to each author: two tools named `spawnBlueprint` and `spawn_blueprint` in one manifest
 * are the same tool spelled two ways, and the collision is only detectable if one rule
 * produced both.
 */
internal object AgentNaming {

    /** The only shape a generated tool or toolset name may have. */
    val NAME_FORMAT: Regex = Regex("[a-z][a-z0-9_]*")

    private val BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    /**
     * `spawnBlueprint` -> `spawn_blueprint`, `spawnNPC` -> `spawn_npc`, `stepNTicks` ->
     * `step_n_ticks`.
     *
     * The second alternative in the boundary pattern is what keeps an acronym followed by a
     * word from running together: without it `parseHTTPHeader` becomes `parse_httpheader`.
     */
    fun snakeCase(identifier: String): String =
        identifier.split(BOUNDARY).joinToString("_") { it.lowercase() }

    /** `Playground` + `spawnBlueprint` -> `PlaygroundSpawnBlueprintTool`. */
    fun toolObjectName(ownerSimpleName: String, functionName: String): String =
        ownerSimpleName + functionName.replaceFirstChar(Char::uppercaseChar) + "Tool"

    /** `MatchClock` -> `MatchClockAgentState`. */
    fun stateObjectName(ownerSimpleName: String): String = ownerSimpleName + "AgentState"
}
