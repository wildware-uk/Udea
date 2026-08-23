package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AgentStateSource
import dev.wildware.udea.agent.OwnerBinding
import dev.wildware.udea.agent.StateModule
import java.util.ServiceLoader

/**
 * The [GameStateSource] over every generated `@AgentState` writer on the classpath.
 *
 * ## The seam this closes
 *
 * `udea-codegen` emits `object <Owner>AgentState : AgentStateSource<Owner>` and a
 * `<Module>StateModule` service entry; [StateDigest] takes a [GameStateSource]. The two were
 * generated and consumed with nothing in between, so `@AgentState` produced code that compiled,
 * loaded and never reached a digest. This is the join.
 *
 * ## Two things it does that a KSP round cannot
 *
 * - **Rejects a digest key published twice across modules.** Within one module the processor
 *   already refuses it (`UDEA0012`), but a round sees one module: `moba` and a shared module
 *   both publishing `phase` is invisible at build time and would reach an agent as a `game`
 *   block with the same key twice - which is not even valid JSON. It is refused in [build],
 *   naming both modules.
 * - **Pairs each source with the instance it reads.** See [AgentStateSource.owner].
 *
 * ## Publishing allocates nothing
 *
 * [publish] runs inside the digest build, which is gated at zero render allocation by
 * `DigestBudgetTest`. So the bound pairs are flattened into two parallel arrays at build time
 * and walked by index: no iterator, no lambda, no boxing, and the cast is done once per source
 * when the index is built rather than once per source per digest.
 */
public class AgentStateIndex private constructor(
    private val sources: Array<AgentStateSource<Any>>,
    private val receivers: Array<Any>,
    /** Every digest key this index publishes, ascending. A host reports it; a test asserts it. */
    public val names: List<String>,
) : GameStateSource {

    override fun publish(sink: GameStateSink) {
        var index = 0
        while (index < sources.size) {
            sources[index].write(receivers[index], sink)
            index++
        }
    }

    override fun toString(): String = "AgentStateIndex(${names.size} keys from ${sources.size} source(s))"

    /**
     * Collects modules and the instances their sources read, then resolves them.
     *
     * Separate from the index for the same reason `ToolIndex.Builder` is: the pairing has to be
     * complete before anything may read it.
     */
    public class Builder internal constructor() {

        private val modules = ArrayList<StateModule>()
        private val instances = ArrayList<Any>()

        /** Adds one module's state sources. Usually [discover] instead. */
        public fun module(module: StateModule): Builder = apply { modules.add(module) }

        /** Adds every [StateModule] on [loader], as its `META-INF/services` entry declares it. */
        public fun discover(loader: ClassLoader = AgentStateIndex::class.java.classLoader): Builder =
            apply { ServiceLoader.load(StateModule::class.java, loader).forEach(modules::add) }

        /** Registers the object whose `@AgentState` properties are being published. */
        public fun source(instance: Any): Builder = apply { instances.add(instance) }

        /**
         * @throws IllegalStateException if two modules publish one digest key, or a source's
         *   declaring instance was not registered, or more than one registered instance fits it.
         */
        public fun build(): AgentStateIndex {
            val bound = ArrayList<AgentStateSource<Any>>()
            val receivers = ArrayList<Any>()
            val owners = HashMap<String, String>()
            for (module in modules) {
                for (source in module.states) {
                    for (name in source.names) {
                        val clash = owners.put(name, module.moduleName)
                        check(clash == null) {
                            "two modules publish the digest key $name: $clash and " +
                                "${module.moduleName}; the game block is one flat object and " +
                                "cannot carry a key twice"
                        }
                    }
                    receivers.add(
                        OwnerBinding.resolve(
                            source.owner,
                            instances,
                            "the digest source",
                            source.owner.qualifiedName ?: source.owner.java.name,
                        ),
                    )
                    bound.add(unchecked(source))
                }
            }
            return AgentStateIndex(bound.toTypedArray(), receivers.toTypedArray(), owners.keys.sorted())
        }

        /**
         * The one unchecked cast on the digest path, done once per source at build time.
         *
         * Sound because the receiver stored beside it was resolved through `owner.isInstance`,
         * and the two arrays are filled in lockstep in the loop above and never again: the
         * index's constructor is private and this is its only caller.
         */
        @Suppress("UNCHECKED_CAST")
        private fun unchecked(source: AgentStateSource<*>): AgentStateSource<Any> =
            source as AgentStateSource<Any>
    }

    public companion object {
        /** Starts an index. */
        public fun builder(): Builder = Builder()
    }
}
