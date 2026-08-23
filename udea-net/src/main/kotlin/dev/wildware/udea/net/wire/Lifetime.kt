package dev.wildware.udea.net.wire

import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.Replicator

/**
 * The `@Net(lifetime = OnCreate)` fields of a component, as a mask (issue #114).
 *
 * `udea-annotations` has declared `lifetime = OnCreate | Always` since Phase 0 and **nothing
 * reads it**, which makes the declaration decorative: a team id or a spawn tick written once
 * and never meaningfully changed is re-sent on every tick that capture-and-diff happens to see
 * it move. This interface is what turns the declaration into bytes not sent.
 *
 * ## Why an interface a `Replicator` opts into, rather than a property on `Replicator`
 *
 * `Replicator` is frozen (`docs/contracts/replicator.md`, Phase 0) and `udea-net` may not
 * widen it. Issue #114's own scope puts the emitted constant in `udea-codegen`
 * (`CREATE_ONLY_MASK` beside `NET_MASK` and `ALL_MASK`) — a module this agent does not own.
 * So the *policy* lives here, in the module that writes packets, expressed against a marker
 * the generator can implement without a contract change, and [LifetimePolicy] degrades to
 * "nothing is create-only" for every replicator that does not declare one.
 *
 * That degradation is the honest part and the dangerous part: until `udea-codegen` emits this,
 * a generated `Replicator` reports an empty create-only mask and every field keeps riding
 * deltas exactly as it does today. The enforcement below is real and tested; the generator
 * side of #114 is not done.
 */
public interface CreateOnlyFields {

    /**
     * Bits of `@Net(lifetime = OnCreate)` fields.
     *
     * Must be a subset of [Replicator.netMask]: a create-only field is still a replicated
     * field, it just never rides an update. `LifetimeMaskTest` asserts the subset property for
     * every replicator it is given, because a bit outside `netMask` would silently subtract
     * nothing while looking like it subtracted something.
     */
    public val createOnlyMask: FieldMask
}

/**
 * Which fields may appear in a full write and which in a delta write.
 *
 * The stripping happens **here**, at the write site, and deliberately not at the diff site.
 * Capture-and-diff (spec 3.2) compares two `FieldStore` slots and has no idea what a field is
 * *for*; giving it a per-field policy branch would put a lifetime lookup on the hottest loop in
 * the engine to save nothing, since the mask intersection below costs one `and` per component.
 */
public object LifetimePolicy {

    /**
     * Fields a `Create` or a full resend may carry: the whole `@Net` set, create-only included.
     *
     * A full write is exactly the case an `OnCreate` field exists for. It is also what a client
     * gets when its baseline has aged out of the ring, which is why
     * `LifetimeMaskTest.onCreateFieldSurvivesBaselineLoss` matters: a client that recovered
     * through a resend must not be left with an undefined team id forever.
     */
    public fun fullMask(replicator: Replicator<*>): FieldMask = replicator.netMask

    /**
     * Fields an `Update` may carry: `netMask and createOnlyMask.inv()`.
     *
     * Applied regardless of whether diff saw the field change, so an `OnCreate` field cannot
     * reach a delta packet by any route.
     */
    public fun deltaMask(replicator: Replicator<*>): FieldMask =
        MaskOps.andNot(replicator.netMask, createOnlyMask(replicator))

    /** [CreateOnlyFields.createOnlyMask], or empty for a replicator that declares none. */
    public fun createOnlyMask(replicator: Replicator<*>): FieldMask {
        val declared = (replicator as? CreateOnlyFields)?.createOnlyMask ?: return MaskOps.EMPTY
        require(MaskOps.containsAll(replicator.netMask, declared)) {
            "${replicator.typeId} declares a create-only field outside its netMask; a " +
                "lifetime = OnCreate field is still a @Net field"
        }
        return declared
    }
}
