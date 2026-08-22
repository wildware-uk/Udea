package dev.wildware.udea.core.replication

/**
 * The stable numeric identity of a component type on the wire and in a snapshot.
 *
 * Assigned by the one generator from sorted FQNs, pinned in `net-protocol.lock` and hashed
 * into the packet `protoHash` (spec 5, "Id assignment"). That makes it a domain identity in
 * exactly the sense [dev.wildware.udea.core.identity.NetId] is, not an index: it is minted by
 * one authority, checked in, and read by four modules.
 *
 * It is a value class for the reason [FieldStore] gives dedicated `setNetId`/`setTick`
 * accessors rather than laundering identity through `setInt`: as a bare `Int` a type id is
 * interchangeable at compile time with a `slot`, a `fieldIndex` and every other registry's
 * id, so a framing layer writing `writeVarInt(slot)` where it meant `writeVarInt(typeId)`
 * compiles cleanly and produces a stream that decodes into the wrong component type. Wrapped,
 * that transposition does not compile.
 *
 * `@JvmInline`, so it unwraps to [raw] for an array or `IntMap` lookup and costs nothing on
 * the per-packet path.
 */
@JvmInline
public value class ComponentTypeId(public val raw: Int) {
    init {
        require(raw >= 0) { "a component type id must not be negative, was $raw" }
    }

    override fun toString(): String = "ComponentTypeId($raw)"
}
