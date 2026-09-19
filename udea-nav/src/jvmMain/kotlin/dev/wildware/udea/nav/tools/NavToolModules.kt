package dev.wildware.udea.nav.tools

import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.ToolIndex

/**
 * `nav.*` as a [ToolModule] a host registers by hand.
 *
 * ## Why this is not on the generated registry's `ToolModule` facet
 *
 * For the reason `ReplayToolModules` gives, and it applies here in the same shape.
 * `ToolIndex.Builder.build` refuses a tool whose toolset instance was never registered, so a
 * generated facet would turn *every* game whose registry lists `udea-nav` into a start-up failure
 * unless it had also wired a [NavToolset] - and a [NavToolset] needs the running game's
 * `Navigation`, which only the host that built the game can hand over.
 *
 * So the module is assembled here out of the objects `udea-codegen` emitted from the `@AgentTool`
 * functions, and a host that wants the navigation tools writes one line:
 *
 * ```kotlin
 * NavToolModules.wire(builder, NavToolset(game.ctx[NavModule.NAVIGATION]))
 * ```
 *
 * The list is compile-checked in the way that matters: naming a tool object that does not exist
 * does not compile, and a tool whose `@AgentTool` was deleted takes its object with it. What that
 * cannot catch is a *new* `@AgentTool` nobody added here, which is what `NavToolTest` asserts by
 * dispatching every name the module publishes through a real [ToolIndex].
 */
public object NavToolModules {

    /** `nav.*`: the route between two points, and the grid the routes are found on. */
    public val Nav: ToolModule = of(
        "Nav",
        listOf(
            NavToolsetGridTool,
            NavToolsetPathTool,
        ),
    )

    /** Registers [toolset] and this module on [builder]. The one-line host wiring. */
    public fun wire(builder: ToolIndex.Builder, toolset: NavToolset): ToolIndex.Builder =
        builder.toolset(toolset).module(Nav)

    private fun of(moduleName: String, tools: List<AgentToolDef<*>>): ToolModule {
        val ordered = tools.sortedBy { it.name }
        return object : ToolModule {
            override val moduleName: String = moduleName

            override val tools: List<AgentToolDef<*>> = ordered

            override fun toString(): String = "$moduleName(${ordered.size} tools)"
        }
    }
}
