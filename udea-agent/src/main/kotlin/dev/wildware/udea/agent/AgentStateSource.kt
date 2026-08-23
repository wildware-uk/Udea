package dev.wildware.udea.agent

import dev.wildware.udea.agent.state.GameStateSink
import kotlin.reflect.KClass

/**
 * One declaring type's contribution to the `game` block of `GET /state`.
 *
 * Implemented by `udea-codegen`'s emitted `object <Owner>AgentState`, one per class declaring
 * `@AgentState` properties. Not a
 * [dev.wildware.udea.agent.state.GameStateSource] because that has no receiver to read from:
 * the host owns the instance and therefore owns the pairing, which is what
 * [dev.wildware.udea.agent.state.AgentStateIndex] does.
 *
 * @param T the type declaring the `@AgentState` properties, which is the instance the digest
 *   builder reads from.
 */
public interface AgentStateSource<in T> {

    /** The digest keys this source publishes, sorted, so a collision is detectable. */
    public val names: List<String>

    /**
     * The class the published properties are declared on, as a class literal.
     *
     * Present for the same reason [AgentToolDef.owner] is: an index holding
     * `AgentStateSource<*>` has an erased `T` and must still pair this source with the one
     * instance a host registered for it, without reflection.
     */
    public val owner: KClass<*>

    /** Appends every published scalar to [out]. Allocation-free by construction. */
    public fun write(source: T, out: GameStateSink)
}

/** One Gradle module's contribution to the digest's `game` block, found through `ServiceLoader`. */
public interface StateModule {

    /** The Gradle module this index was generated for, in `UpperCamelCase`. */
    public val moduleName: String

    /** Every `@AgentState` source generated for this module, by declaring type name. */
    public val states: List<AgentStateSource<*>>
}
