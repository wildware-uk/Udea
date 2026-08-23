package dev.wildware.udea.agent

import kotlin.reflect.KClass

/**
 * Pairs a generated declaration with the one registered instance it reads or runs against.
 *
 * Both generated indexes have the same problem and it is solved once here rather than twice:
 * `AgentToolDef<*>` and `AgentStateSource<*>` have an erased `T`, so an index that holds them
 * cannot recover the toolset instance from the type. Each declaration carries its declaring
 * class as a `::class` literal instead ([AgentToolDef.owner], [AgentStateSource.owner]), and
 * this matches it against the instances a host registered.
 *
 * Matching is by assignability rather than by exact class, so a host may register a subclass -
 * a `MobaToolset : Toolset` still serves a tool generated on `Toolset`. That is also why an
 * ambiguity is possible and why it is refused rather than resolved by declaration order: two
 * instances that both fit means the host has to say which, and silently taking the first would
 * route a mutation to whichever happened to be registered earlier.
 */
internal object OwnerBinding {

    /**
     * The single registered instance [owner] accepts.
     *
     * @throws IllegalStateException when none fits or more than one does. Both are wiring
     *   mistakes with no runtime remedy, and they are raised when the index is built - once, at
     *   start-up - rather than on a tool call, so a misconfigured host fails before an agent
     *   has been told the tool exists.
     */
    fun resolve(owner: KClass<*>, instances: List<Any>, what: String, subject: String): Any {
        val fitting = instances.filter { owner.isInstance(it) }
        check(fitting.isNotEmpty()) {
            "$what $subject is declared on ${owner.qualifiedName}, but no instance of it was " +
                "registered; call toolset(...) with the object that declares it before build()"
        }
        check(fitting.size == 1) {
            "$what $subject is declared on ${owner.qualifiedName} and " +
                "${fitting.size} registered instances fit it " +
                "(${fitting.joinToString { it::class.qualifiedName ?: it::class.java.name }}); " +
                "register exactly one"
        }
        return fitting.single()
    }
}
