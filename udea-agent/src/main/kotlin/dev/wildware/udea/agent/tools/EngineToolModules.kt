package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.ToolIndex

/**
 * The engine's own toolsets, as [ToolModule]s a host registers.
 *
 * ## Why these are not `META-INF/services` entries
 *
 * Every *generated* `ToolModule` is discovered through `ServiceLoader`, because a generated
 * tool's declaring class is a game type the host constructs anyway and the module knows nothing
 * that the host has to supply. These four are the opposite: a [WorldToolset] needs the `World`,
 * the component index and the `NetIdIndex`; a [TimeToolset] needs the `TimeControl`. Only the
 * host can build one.
 *
 * `ToolIndex.Builder.build` refuses a tool whose toolset was never registered - deliberately, so
 * a misconfigured host fails at start-up instead of answering `no_such_tool` weeks later. A
 * service entry for these would therefore turn *every* process with `udea-agent` on its
 * classpath into a build failure unless it wired all four, including `udea-codegen`'s own
 * `ServiceLoader` fixture tests. So they are registered by hand, one module per toolset, and a
 * host that wires only two gets exactly those two.
 *
 * [wireAll] is the whole-engine shortcut.
 */
public object EngineToolModules {

    /** `world.*`: query, inspect, mutate, spawn, destroy. */
    public val World: ToolModule = of("UdeaAgentWorld", WorldToolset.tools())

    /** `time.*`: pause, resume, step, scale, snapshot, list, rewind, fast-forward. */
    public val Time: ToolModule = of("UdeaAgentTime", TimeToolset.tools())

    /** `events.*`: read, clear, assert. */
    public val Events: ToolModule = of("UdeaAgentEvents", EventsToolset.tools())

    /** `diag.*`: frame report, timings, entity counts, memory. */
    public val Diag: ToolModule = of("UdeaAgentDiag", DiagToolset.tools())

    /** Every engine module, ascending by module name. */
    public val ALL: List<ToolModule> = listOf(Diag, Events, Time, World)

    /**
     * Registers every engine toolset that [toolsets] supplies an instance for.
     *
     * Only those: a module whose instance is missing is not added at all, so a headless host
     * with no renderer and no snapshot ring can wire `world` and `events` and leave `time` out
     * without the index refusing to build. What it may not do is register an instance and get
     * *nothing*, which is the wiring mistake this would otherwise hide.
     */
    public fun wireAll(builder: ToolIndex.Builder, vararg toolsets: Any): ToolIndex.Builder {
        for (toolset in toolsets) {
            builder.toolset(toolset)
            val module = when (toolset) {
                is WorldToolset -> World
                is TimeToolset -> Time
                is EventsToolset -> Events
                is DiagToolset -> Diag
                else -> throw IllegalArgumentException(
                    "${toolset::class.qualifiedName} is not an engine toolset; register a " +
                        "generated module's toolset with ToolIndex.Builder.toolset and let " +
                        "ServiceLoader find its ToolModule",
                )
            }
            builder.module(module)
        }
        return builder
    }

    private fun of(moduleName: String, tools: List<AgentToolDef<*>>): ToolModule {
        val ordered = tools.sortedBy { it.name }
        return object : ToolModule {
            override val moduleName: String = moduleName

            override val tools: List<AgentToolDef<*>> = ordered

            override fun toString(): String = "$moduleName(${ordered.size} tools)"
        }
    }
}
