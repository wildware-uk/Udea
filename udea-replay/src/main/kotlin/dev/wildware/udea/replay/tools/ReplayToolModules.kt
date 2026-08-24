package dev.wildware.udea.replay.tools

import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.ToolIndex

/**
 * `replay.*` as a [ToolModule] a host registers by hand.
 *
 * ## Why this is not a `META-INF/services` entry
 *
 * For the reason `EngineToolModules` gives about the engine's own toolsets, and one more.
 * `ToolIndex.Builder.build` refuses a tool whose toolset instance was never registered - so a
 * `ServiceLoader` entry would turn *every* process with this module on its classpath into a
 * start-up failure unless it had wired a [ReplayToolset]. And a [ReplayToolset] needs a
 * [ReplayHost], which knows how to build a world of a specific game: nothing but the host can
 * supply one, and no amount of discovery can invent it.
 *
 * So the module is assembled here, out of the objects `udea-codegen` emitted from the
 * `@AgentTool` functions, and a host that wants replay tools writes one line:
 *
 * ```kotlin
 * builder.toolset(ReplayToolset(host)).module(ReplayToolModules.Replay)
 * ```
 *
 * The list is compile-checked in the only way that matters here: naming a tool object that does
 * not exist does not compile, and a tool whose `@AgentTool` was deleted takes its object with
 * it. What it cannot catch is a *new* `@AgentTool` nobody added, which is why
 * `ReplayToolSurfaceTest` asserts this list against the generated manifest resource rather than
 * against a second hand-written list.
 */
public object ReplayToolModules {

    /** `replay.*`: load, info, verify, seek, step, rewind. */
    public val Replay: ToolModule = of(
        "UdeaReplay",
        listOf(
            ReplayToolsetInfoTool,
            ReplayToolsetLoadTool,
            ReplayToolsetRewindTool,
            ReplayToolsetSeekTool,
            ReplayToolsetStepTool,
            ReplayToolsetVerifyTool,
        ),
    )

    /** Registers [toolset] and this module on [builder]. The one-line host wiring. */
    public fun wire(builder: ToolIndex.Builder, toolset: ReplayToolset): ToolIndex.Builder =
        builder.toolset(toolset).module(Replay)

    private fun of(moduleName: String, tools: List<AgentToolDef<*>>): ToolModule {
        val ordered = tools.sortedBy { it.name }
        return object : ToolModule {
            override val moduleName: String = moduleName

            override val tools: List<AgentToolDef<*>> = ordered

            override fun toString(): String = "$moduleName(${ordered.size} tools)"
        }
    }
}
