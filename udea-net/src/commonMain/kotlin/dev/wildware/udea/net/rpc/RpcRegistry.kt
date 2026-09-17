package dev.wildware.udea.net.rpc

/**
 * Every [RpcDescriptor] both peers agreed on, in one order, addressed by a dense index.
 *
 * ## Why the index is assigned here rather than baked into the generated object
 *
 * A `ComponentTypeId` is baked in, from the reviewed project-wide `net-components.lock`, and an
 * RPC could have been given the same treatment. It is not, for one reason: a component id is on
 * the wire in **recorded replays**, so renumbering breaks artefacts that already exist, whereas
 * an RPC index only ever lives inside a live connection whose `protoHash` both ends have
 * already agreed on. Sorting by [RpcDescriptor.name] here gives both peers the same assignment
 * from the same modules with no second lock file to keep current - and an RPC added to one peer
 * and not the other changes the protocol hash, so the connection is refused by name before a
 * single index is exchanged.
 *
 * ## Duplicate names
 *
 * Refused loudly at construction, naming both, for exactly the reason `NetRegistry` refuses two
 * modules claiming one component id: the failure otherwise is two peers dispatching one index
 * to two different functions, silently, with everything reporting agreement.
 */
public class RpcRegistry(descriptors: List<RpcDescriptor>) {

    /** Ascending [RpcDescriptor.name]. The order the wire index is assigned from. */
    private val sorted: List<RpcDescriptor> = descriptors.sortedBy(RpcDescriptor::name)

    private val indices: Map<String, Int> = buildMap {
        sorted.forEachIndexed { index, descriptor ->
            val existing = put(descriptor.name, index)
            check(existing == null) {
                "two RPCs are registered as ${descriptor.name}. An RPC name is its identity " +
                    "across the connection, so two of them share one wire index and one peer " +
                    "runs the wrong function for every call to either."
            }
        }
    }

    /** How many RPCs this build speaks. */
    public val size: Int get() = sorted.size

    /** The descriptor at [index], or `null` when the index is not one this build knows. */
    public fun at(index: Int): RpcDescriptor? = sorted.getOrNull(index)

    /**
     * [descriptor]'s wire index.
     *
     * @throws IllegalArgumentException if it is not in this registry, which for a *send* is a
     *   programming error and not a wire event: the sender is holding a descriptor its own
     *   session never registered, so the call would be written under an index the receiver
     *   resolves to something else.
     */
    public fun indexOf(descriptor: RpcDescriptor): Int = requireNotNull(indices[descriptor.name]) {
        "${descriptor.name} is not in this registry, which holds ${sorted.map(RpcDescriptor::name)}"
    }

    /** Every descriptor, in wire-index order. */
    public fun all(): List<RpcDescriptor> = sorted
}
