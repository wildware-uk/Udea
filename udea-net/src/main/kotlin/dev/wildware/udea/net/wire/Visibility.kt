package dev.wildware.udea.net.wire

import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.Replicator

/**
 * The `@Net(visibility = OwnerOnly)` fields of a component, as a mask (issue #167).
 *
 * `udea-annotations` has declared `visibility = All | OwnerOnly` since Phase 0 and **nothing
 * read it**, which made the declaration decorative in the direction that leaks: an author who
 * wrote `OwnerOnly` on a champion's gold or inventory believed the field was private to that
 * connection, and it went to every client the entity was relevant to. This interface is what
 * turns the declaration into bytes not sent.
 *
 * ## Field-level, and therefore not covered by relevancy
 *
 * Entity-level relevancy (issue #111) decides whether a client hears about an *entity* at all.
 * `OwnerOnly` matters precisely for entities every client is already relevant to: two champions
 * fighting each other are both on each other's screens, and neither may read the other's
 * inventory. A relevancy set cannot express that, because its answer is one bit per entity.
 *
 * ## Why an interface a `Replicator` opts into, rather than a property on `Replicator`
 *
 * The same reason [CreateOnlyFields] gives, and it is worth restating rather than
 * cross-referencing, because the two are independent: `Replicator` is frozen
 * (`docs/contracts/replicator.md`, Phase 0) and `udea-net` may not widen it. So the *policy*
 * lives here, in the module that writes packets, expressed against a marker the generator can
 * implement without a contract change, and [VisibilityPolicy] degrades to "nothing is
 * owner-only" for every replicator that does not declare one.
 *
 * `udea-codegen` implements it: `ComponentModelBuilder` reads `@Net(visibility = ...)` and
 * `ReplicatorEmitter` adds this superinterface and an `ownerOnlyMask` **only** to a component
 * that actually declares an owner-only field, so a module whose components are all
 * `visibility = All` gains no reference to `udea-net` for a mask that would say nothing — and,
 * more to the point, no extra bit on the wire. `moba`'s `Inventory` is the first shipped
 * component to use it, and `InventoryVisibilityTest` proves the stripping against the
 * generated replicator over a real `ReplicationServer`.
 */
public interface OwnerOnlyFields {

    /**
     * Bits of `@Net(visibility = OwnerOnly)` fields.
     *
     * Must be a subset of [Replicator.netMask]: an owner-only field is still a replicated field,
     * it just reaches one recipient. `OwnerOnlyVisibilityTest` asserts the subset property for
     * every replicator in its registry, because a bit outside `netMask` would silently subtract
     * nothing while looking like it subtracted something.
     */
    public val ownerOnlyMask: FieldMask
}

/**
 * Which fields of a component one recipient may be told about.
 *
 * ## Stripping clears a bit; it never renumbers one
 *
 * This is the invariant the whole feature rests on. `Replicator.write` puts a **fixed-width**
 * field mask on the wire — `MaskOps.writeTo(mask, out, FIELD_COUNT)` — and then the values of
 * exactly the set bits, so removing a field from a recipient's packet is one bit going to zero
 * and one value not being written. Nothing after it moves.
 *
 * That matters more than it looks, because the frozen contract
 * (`docs/contracts/replicator.md`) makes `fieldNames[i]`, `FieldMask` bit *i* and `FieldStore`
 * field index *i* the same *i*, and `DesyncReport` names a differing field by indexing
 * `fieldNames` with a bit of a mask diff. A stripping implementation that compacted the
 * surviving fields down would not fail: it would decode cleanly and report the wrong field
 * name for the rest of the session. `OwnerOnlyVisibilityTest`'s "stripping clears a bit and
 * never renumbers one" is what catches that, and it asserts on the name `DesyncReport` produces
 * rather than on a mask.
 *
 * ## At the write site, not the diff site
 *
 * For [LifetimePolicy]'s reason and one more of its own. Capture-and-diff (spec 3.2) compares
 * two `FieldStore` slots and has no idea who the packet is for — there is one diff per entity
 * per tick and *n* recipients — so a per-recipient policy branch there would be both wrong and
 * on the hottest loop in the engine. Here it costs one `and` per component per recipient.
 */
public object VisibilityPolicy {

    /**
     * Fields a packet for [recipientOwnsEntity] may carry, out of the whole `@Net` set.
     *
     * The owner sees everything replicated; everybody else sees `netMask and ownerOnlyMask.inv()`.
     * Applied to a `Create`, to a full resend after baseline loss and to every `Update`, because
     * an owner-only field must not reach a non-owner by any of those routes.
     */
    public fun visibleMask(replicator: Replicator<*>, recipientOwnsEntity: Boolean): FieldMask =
        if (recipientOwnsEntity) replicator.netMask else MaskOps.andNot(replicator.netMask, ownerOnlyMask(replicator))

    /** [OwnerOnlyFields.ownerOnlyMask], or empty for a replicator that declares none. */
    public fun ownerOnlyMask(replicator: Replicator<*>): FieldMask {
        val declared = (replicator as? OwnerOnlyFields)?.ownerOnlyMask ?: return MaskOps.EMPTY
        require(MaskOps.containsAll(replicator.netMask, declared)) {
            "${replicator.typeId} declares an owner-only field outside its netMask; a " +
                "visibility = OwnerOnly field is still a @Net field"
        }
        return declared
    }
}
