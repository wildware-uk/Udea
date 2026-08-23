package dev.wildware.udea.agent.dispatch

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.OwnerBinding
import dev.wildware.udea.agent.ToolModule
import java.util.ServiceLoader

/**
 * The [ToolRegistry] over every generated tool on the classpath.
 *
 * ## The seam this closes
 *
 * `udea-codegen` emits `object <Owner><Fn>Tool : AgentToolDef<Owner>` and a
 * `<Module>ToolModule` service entry per Gradle module; [AgentDispatcher] takes a
 * [ToolRegistry]. Until this class existed the two halves were generated and consumed with
 * nothing in between: the tools compiled, `ServiceLoader` could find them, and no shipped code
 * could call one. This is the join, and it is the only place in the tree that casts an
 * `AgentToolDef<*>` in order to call it.
 *
 * ## What it checks, and when
 *
 * Everything is settled in [Builder.build] and nothing on the call path:
 *
 * - **two modules publishing one tool name** is refused, naming both modules. No single KSP
 *   round can see this - a round sees one module - so it is unenforceable at build time, and
 *   this is the first place the whole classpath is visible at once;
 * - **a tool whose toolset was never registered** is refused, naming the tool and its owner.
 *   The alternative is a `no_such_tool` at the first call, weeks later, for a tool the
 *   manifest had been advertising the whole time;
 * - the name-to-entry map and the receiver are resolved once, so [invoke] is a hash lookup and
 *   a virtual call with no allocation of its own.
 */
public class ToolIndex private constructor(
    private val entries: Map<String, Entry>,
) : ToolRegistry {

    /** One resolved pairing: what to run, and what to run it on. */
    private class Entry(
        val def: AgentToolDef<*>,
        val receiver: Any,
        val moduleName: String,
    )

    /** Every tool this index serves, ascending by name. What a manifest is rendered from. */
    public val tools: List<AgentToolDef<*>> = entries.values.map { it.def }.sortedBy { it.name }

    /** The modules that contributed, ascending. Reported by health so wiring is visible. */
    public val moduleNames: List<String> = entries.values.map { it.moduleName }.distinct().sorted()

    override fun contains(toolName: String): Boolean = entries.containsKey(toolName)

    /**
     * Always `0`: `@AgentTool` declares no budget, so no generated tool has one.
     *
     * Stated rather than left to a default, because the honest answer here is "this mechanism
     * carries no budget" and not "this tool is fast". [AgentDispatcher] reads `0` as
     * "no declared budget" and emits no `slow_tool` event, which is the correct behaviour for a
     * surface that never declared one.
     */
    override fun budgetMs(toolName: String): Long = 0L

    /**
     * Delegates to [invoke]`(command)`, ignoring [context].
     *
     * A generated tool is never handed an [AgentContext]: `AgentToolDef.invoke` takes a receiver
     * and a command and nothing else, because a tool reaches the world through the toolset it is
     * a member of - which the host constructed with whatever that tool mutates. Threading a
     * context through here would suggest generated tools can read one, and the first tool that
     * tried would find no way to.
     */
    override fun invoke(command: AgentCommand, context: AgentContext): AgentResult = invoke(command)

    /** Runs [command] against the toolset bound to it. */
    public fun invoke(command: AgentCommand): AgentResult {
        val entry = entries[command.name] ?: return AgentResult.failed(
            AgentErrorKind.NO_SUCH_TOOL,
            "no tool named ${command.name} is registered; call the tools listing to see what is",
        )
        return render(command, invokeUnchecked(entry, command))
    }

    /**
     * The one unchecked cast on the agent surface, and the reason [AgentToolDef.owner] exists.
     *
     * `T` is erased, so an `AgentToolDef<*>` cannot be called without one. What makes it sound
     * is that [Builder.build] resolved [Entry.receiver] through `owner.isInstance`, so the
     * receiver is an instance of the class the generated `invoke` down-casts to. Nothing can
     * put a mismatched pair in the map: the constructor is private and the builder is its only
     * caller.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeUnchecked(entry: Entry, command: AgentCommand): Any? =
        (entry.def as AgentToolDef<Any>).invoke(entry.receiver, command)

    /**
     * The tool's return value as a JSON document.
     *
     * A closed set, checked here rather than assumed, because `@AgentTool` does not constrain a
     * return type at all: a tool returning a data class would otherwise reach an agent as
     * `"Spawned(count=3)"` - a `toString` that reads like data and is not. So a type this
     * cannot render is reported as a failure naming the type, which is a bug report the game's
     * author can act on, rather than a plausible-looking string an agent will parse.
     *
     * Returning an [AgentResult] is the escape hatch and the recommended shape for anything
     * structured: the tool renders with [Json] and this passes it straight through.
     */
    private fun render(command: AgentCommand, produced: Any?): AgentResult {
        if (produced == null || produced == Unit) return AgentResult.EMPTY
        if (produced is AgentResult) return produced
        val json = Json()
        when (produced) {
            is String -> json.value(produced)
            is Int -> json.value(produced)
            is Long -> json.value(produced)
            is Float -> json.value(produced)
            is Boolean -> json.value(produced)
            else -> return AgentResult.failed(
                UNRENDERABLE_RESULT,
                "${command.name} returned ${produced::class.qualifiedName}, which is not a JSON " +
                    "value; return an AgentResult built with Json.render, or a scalar",
            )
        }
        return AgentResult.Ok(json.toString())
    }

    override fun toString(): String =
        "ToolIndex(${entries.size} tools from ${moduleNames.size} module(s))"

    /**
     * Collects modules and toolset instances, then resolves them into a [ToolIndex].
     *
     * Separate from the index because the pairing has to be complete before anything may be
     * called: a builder that also served calls would have a window in which half the tools
     * answered `no_such_tool` for a reason the caller could not tell from a typo.
     */
    public class Builder internal constructor() {

        private val modules = ArrayList<ToolModule>()
        private val instances = ArrayList<Any>()

        /** Adds one module's tools. Usually [discover] instead; explicit for tests and hosts. */
        public fun module(module: ToolModule): Builder = apply { modules.add(module) }

        /**
         * Adds every [ToolModule] on [loader], as its `META-INF/services` entry declares it.
         *
         * Order is `ServiceLoader`'s, which is classpath order, and deliberately not sorted:
         * the index refuses a duplicate name rather than letting an order decide a winner, so
         * nothing here depends on that order being stable.
         */
        public fun discover(loader: ClassLoader = ToolIndex::class.java.classLoader): Builder =
            apply { ServiceLoader.load(ToolModule::class.java, loader).forEach(modules::add) }

        /**
         * Registers the object whose `@AgentTool` functions are being served.
         *
         * The host owns toolset lifetimes - a toolset holds the world, the context, or whatever
         * its tools mutate - so it constructs them and hands them over here.
         */
        public fun toolset(instance: Any): Builder = apply { instances.add(instance) }

        /**
         * @throws IllegalStateException if two modules publish one tool name, or a tool's
         *   toolset was not registered, or more than one registered instance fits one.
         */
        public fun build(): ToolIndex {
            val entries = LinkedHashMap<String, Entry>()
            for (module in modules) {
                for (def in module.tools) {
                    val clash = entries[def.name]
                    check(clash == null) {
                        "two modules publish the tool ${def.name}: ${clash?.moduleName} and " +
                            "${module.moduleName}; a tool name is the agent's whole address for " +
                            "it and cannot be shared"
                    }
                    val receiver = OwnerBinding.resolve(def.owner, instances, "the tool", def.name)
                    entries[def.name] = Entry(def, receiver, module.moduleName)
                }
            }
            return ToolIndex(entries)
        }
    }

    public companion object {
        /** A tool returned a value that is not a JSON value. See [render]. */
        public val UNRENDERABLE_RESULT: AgentErrorKind = AgentErrorKind("unrenderable_result")

        /** Starts an index. */
        public fun builder(): Builder = Builder()
    }
}
