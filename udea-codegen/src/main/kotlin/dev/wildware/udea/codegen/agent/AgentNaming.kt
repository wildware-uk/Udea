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

    /**
     * `world.query` - a tool name that names its own toolset.
     *
     * ## Why a name is allowed a dot at all
     *
     * A toolset is otherwise the declaring class's name, snake_cased, and for a game's own
     * `Playground` that is exactly right. It is wrong for the *engine's* toolsets, and that
     * wrongness is what kept `world`, `time`, `events` and `diag` hand-written for a whole
     * wave: the agent host groups tools by the `<toolset>.` prefix on the tool name, the
     * engine's four are addressed as `world.query` and `time.step` in the frozen
     * `docs/contracts/agent-tools.md`, and no name derived from a class could carry a dot.
     * The alternative - naming the declaring class `World` - puts a type called `World` in a
     * module that imports Fleks' `World` on almost every line.
     *
     * So an explicit `@AgentTool(name = "world.query")` names the toolset in the one place a
     * reader already looks for the tool's address, and [toolsetOf] reads it back out. The
     * derived form is untouched: a tool that does not spell a toolset still gets its declaring
     * class's, so nothing a game wrote changes.
     *
     * Both halves are [NAME_FORMAT], so `World.query`, `world.`, `.query` and `a.b.c` are all
     * refused - a prefix that is not itself a legal toolset name would group tools under
     * something an agent cannot type.
     */
    val QUALIFIED_NAME_FORMAT: Regex = Regex("""[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*""")

    /** Whether [name] is addressable: bare, or `toolset.tool`. */
    fun isLegalName(name: String): Boolean =
        NAME_FORMAT.matches(name) || QUALIFIED_NAME_FORMAT.matches(name)

    /**
     * The toolset [name] belongs to: the part before its dot, or the declaring class's own
     * name snake_cased when it has none.
     */
    fun toolsetOf(name: String, ownerSimpleName: String): String =
        if ('.' in name) name.substringBefore('.') else snakeCase(ownerSimpleName)

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
