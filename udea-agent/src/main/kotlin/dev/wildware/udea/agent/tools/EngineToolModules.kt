package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.ToolIndex

/**
 * The engine's own toolsets, as [ToolModule]s a host registers.
 *
 * ## Every tool named here is generated
 *
 * `WorldToolset`, `TimeToolset`, `EventsToolset` and `DiagToolset` declare `@AgentTool`
 * functions and `udea-codegen` emits one `object <Toolset><Fn>Tool` per function, exactly as it
 * does for a game's own toolset. There is one mechanism on this surface, not an engine one and
 * a game one: the schema, the manifest fragment, the argument coercion and the description gate
 * all come from the same KSP pass, so a reworded engine description arrives as a diff in the
 * generated manifest like anybody else's.
 *
 * Two things had to exist in the generator before that was possible, and both are general
 * rather than engine-shaped: a tool name may carry its own toolset (`@AgentTool(name =
 * "world.query_entities")`, `AgentNaming.QUALIFIED_NAME_FORMAT`), and a tool may declare an
 * `AgentContext` parameter, which makes the emitted object a [ContextualToolDef]. Without the
 * first, the engine's toolsets could only be named after their declaring classes - which would
 * mean a class called `World` in a module that imports Fleks' `World` on almost every line.
 * Without the second, `time.step`, `time.rewind` and `time.fast_forward` could not be generated
 * at all: they must run outside the barrier drain they were called in, and the only way to do
 * that and still answer is `AgentContext.answerLater`.
 *
 * ## Why these are still not `META-INF/services` entries
 *
 * The generated `ToolModule` index is emitted only when the build sets
 * `udea.toolModuleService`, and `udea-agent`'s own build deliberately does not. Every *other*
 * generated `ToolModule` is discovered through `ServiceLoader` because a generated tool's
 * declaring class is a game type the host constructs anyway. These four are the opposite: a
 * [WorldToolset] needs the `World`, the component index and the `NetIdIndex`; a [TimeToolset]
 * needs the `TimeControl`. Only the host can build one.
 *
 * `ToolIndex.Builder.build` refuses a tool whose toolset was never registered - deliberately, so
 * a misconfigured host fails at start-up instead of answering `no_such_tool` weeks later. A
 * service entry for these would therefore turn *every* process with `udea-agent` on its
 * classpath into a start-up failure unless it wired all four, including `udea-codegen`'s own
 * `ServiceLoader` fixture tests, which register a `Playground` and nothing else. So the modules
 * are assembled here by hand, one per toolset, and a host that wires only two gets exactly
 * those two.
 *
 * The list below is hand-written and compile-checked: naming a tool object that does not exist
 * does not compile, and a tool whose `@AgentTool` was deleted takes its object with it. What it
 * cannot catch is a *new* `@AgentTool` nobody added here, so `EngineToolSurfaceTest` asserts
 * these modules against the generated manifest resource rather than against a second list.
 *
 * [wireAll] is the whole-engine shortcut.
 */
public object EngineToolModules {

    /** `world.*`: query, inspect, mutate, spawn, destroy. */
    public val World: ToolModule = of(
        "UdeaAgentWorld",
        listOf(
            WorldToolsetDescribeEntityTool,
            WorldToolsetDestroyEntityTool,
            WorldToolsetGetComponentTool,
            WorldToolsetListBlueprintsTool,
            WorldToolsetListComponentsTool,
            WorldToolsetQueryEntitiesTool,
            WorldToolsetSetComponentFieldTool,
            WorldToolsetSpawnBlueprintTool,
        ),
    )

    /** `time.*`: pause, resume, step, scale, snapshot, list, rewind, fast-forward. */
    public val Time: ToolModule = of(
        "UdeaAgentTime",
        listOf(
            TimeToolsetFastForwardTool,
            TimeToolsetListSnapshotsTool,
            TimeToolsetPauseTool,
            TimeToolsetResumeTool,
            TimeToolsetRewindTool,
            TimeToolsetSetTimeScaleTool,
            TimeToolsetSnapshotTool,
            TimeToolsetStepTool,
        ),
    )

    /** `events.*`: read, clear, assert. */
    public val Events: ToolModule = of(
        "UdeaAgentEvents",
        listOf(
            EventsToolsetAssertEventTool,
            EventsToolsetClearEventsTool,
            EventsToolsetRecentEventsTool,
        ),
    )

    /** `diag.*`: frame report, timings, entity counts, memory. */
    public val Diag: ToolModule = of(
        "UdeaAgentDiag",
        listOf(
            DiagToolsetEntityCountsTool,
            DiagToolsetFrameReportTool,
            DiagToolsetMemoryTool,
            DiagToolsetSystemTimingsTool,
        ),
    )

    /**
     * `agent.*`: the caption a human watching the window reads (spec 3.7).
     *
     * Its own module rather than a member of one of the four above, because a headless host has
     * no window and nothing to caption: it wires the other four and leaves this out, and the
     * index it builds then advertises no capability it cannot serve.
     */
    public val Say: ToolModule = of(
        "UdeaAgentSay",
        listOf(SayToolsetClearTool, SayToolsetSayTool),
    )

    /** Every engine module, ascending by module name. */
    public val ALL: List<ToolModule> = listOf(Diag, Events, Say, Time, World)

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
                is SayToolset -> Say
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
