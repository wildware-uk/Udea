package dev.wildware.udea.agent.assets

import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.ToolIndex

/**
 * The `assets` toolset as a [ToolModule], for a host that has a daemon.
 *
 * ## Its own object, not a member of `EngineToolModules`
 *
 * `EngineToolModules.ALL` is a `val` initialised when that object is first touched, so a module
 * listed there is loaded in every process that touches it. [AssetsToolset] names
 * `udea-assets-compiler` types, `udea-agent` takes that dependency `compileOnly`, and
 * `UDEA-MG-005` forbids the Kotlin scripting host it carries on the shipped game's runtime
 * classpath. Listing it there would turn a shipped game's first agent tool call into a
 * `NoClassDefFoundError`. It lives here instead, beside the class it serves, and only a dev host
 * that constructed an `AssetDaemon` can reach it.
 *
 * The list below is hand-written and compile-checked: naming a tool object that does not exist
 * does not compile, and a tool whose `@AgentTool` was deleted takes its generated object with it.
 * What that cannot catch is a *new* `@AgentTool` nobody added here, so `AssetToolSurfaceTest`
 * asserts this list against the generated manifest resource rather than against a second
 * hand-written list.
 */
public object AssetToolModule {

    /** `assets.*`: list, get, search, graph, resolve_reference, validate, write, patch. */
    public val Assets: ToolModule = object : ToolModule {
        override val moduleName: String = "UdeaAgentAssets"

        override val tools: List<AgentToolDef<*>> = listOf(
            AssetsToolsetChangedSinceTool,
            AssetsToolsetGetTool,
            AssetsToolsetGraphTool,
            AssetsToolsetListTool,
            AssetsToolsetPatchTool,
            AssetsToolsetResolveReferenceTool,
            AssetsToolsetSearchTool,
            AssetsToolsetValidateTool,
            AssetsToolsetWriteTool,
        ).sortedBy { it.name }

        override fun toString(): String = "UdeaAgentAssets(${tools.size} tools)"
    }

    /** Registers [toolset] and the module it serves on [builder]. */
    public fun wire(builder: ToolIndex.Builder, toolset: AssetsToolset): ToolIndex.Builder =
        builder.toolset(toolset).module(Assets)
}
