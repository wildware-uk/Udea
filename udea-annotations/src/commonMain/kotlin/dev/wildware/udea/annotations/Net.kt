package dev.wildware.udea.annotations

/**
 * Who is allowed to write a [Net] field, and therefore which writes the server accepts
 * and which fields client-side prediction may touch.
 *
 * Read by the **`udea-codegen` KSP2 processor**, which bakes the value into the
 * generated `Replicator<T>` field descriptor. `udea-net` RPC guards, GAS prediction
 * eligibility and the agent's `set_component_field` then all read that one generated
 * descriptor, so the four consumers cannot drift (spec 5, "Authority vocabulary").
 */
public enum class Authority {
    /** Server owns the value outright. A client write is rejected; the client only ever receives it. */
    Server,

    /**
     * Server owns the value, but the owning client may predict it locally and is
     * reconciled against the server's snapshot. The only authority GAS prediction is
     * allowed to write through.
     */
    OwnerPredicted,

    /**
     * The owning client is the source of truth and the server relays. Reserved for
     * non-gameplay state such as cosmetic or presentation-only fields: **no gameplay
     * field is ever `OwnerWritable`** (spec 5).
     */
    OwnerWritable,
}

/**
 * How often a [Net] field is put on the wire.
 *
 * Read by the **`udea-codegen` KSP2 processor**, which places [OnCreate] fields in the
 * spawn/full-write payload only and leaves them out of the per-tick delta mask.
 */
public enum class Lifetime {
    /** Written once in the entity's full/spawn write and never delta-replicated again. */
    OnCreate,

    /** Included in the per-tick delta mask; replicated whenever capture-and-diff sees a change. */
    Always,
}

/**
 * Who may see a [Net] field.
 *
 * Read by the **`udea-codegen` KSP2 processor**, which emits the per-recipient mask
 * stripping that `udea-net` relevancy applies before a packet is written.
 */
public enum class Visibility {
    /** Replicated to every client that finds the entity relevant. */
    All,

    /** Replicated only to the entity's owning connection; stripped from the mask for everyone else. */
    OwnerOnly,
}

/**
 * Marks a property as **replicated and snapshotted** - one of the two masks of spec 3.1.
 * `@Net` lands in the generated `FieldStore` under both `NET_MASK` and `ALL_MASK`, so
 * `writeDelta` sees it and snapshot capture sees it. Contrast [Sim], which is snapshot only.
 *
 * The property is a plain `var`; there is no `by net(...)` delegate. Dirty determination
 * is capture-and-diff against the previous tick, because an in-place `Vector2.set(...)`
 * fires no setter (spec 3.2).
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which emits the field's bit index,
 * its read/write pair inside `Replicator<T>` and its entry in the agent-facing field
 * table. The **`udea-compiler-plugin` K2 FIR checkers** reject `@Net` on a `val` and
 * reject a component carrying more than 64 replicated fields.
 *
 * @param authority who may write the field; see [Authority]. Defaults to [Authority.Server].
 * @param lifetime whether the field is delta-replicated every tick or written once at spawn.
 * @param visibility whether the field reaches every relevant client or only the owner.
 * @param agentWritable whether the agent's `set_component_field` tool may write this field.
 *   Defaults to `false`: agent write access is opt-in per field, so a debug tool can never
 *   silently become a gameplay backdoor (spec 5).
 *
 * Retention is [AnnotationRetention.BINARY]: every consumer of this vocabulary reads it
 * through generated code, not reflection - the values above are baked into `Replicator<T>`
 * at build time - so it only has to survive to KSP's and the FIR checker's view of the
 * declaration and never needs to be in the runtime-visible annotation table.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Net(
    val authority: Authority = Authority.Server,
    val lifetime: Lifetime = Lifetime.Always,
    val visibility: Visibility = Visibility.All,
    val agentWritable: Boolean = false,
)
