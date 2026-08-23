package dev.wildware.udea.annotations

/**
 * Publishes one scalar property into the `game` block of the agent's `/state` digest.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which emits one `AgentStateSource`
 * per declaring class plus the `StateModule` ServiceLoader entry that lets a module
 * contribute match state without `udea-agent` knowing that module exists (spec 5,
 * "Id assignment": ServiceLoader discovery, no magic package).
 *
 * ### This is not a replication annotation
 *
 * `@AgentState` is deliberately **outside** the `Replicator` field space that [Net] and
 * [Sim] define. A replicated field owns three aligned indices at once — a position in
 * `fieldNames`, a `FieldMask` bit and a `FieldStore` slot — and the frozen `Replicator`
 * contract makes that alignment load-bearing for `desync_report`. A property that gets a
 * `fieldNames` entry but no mask bit and no store slot cannot exist in that space, so
 * `@AgentState` gets its own channel instead: it is never captured, never diffed, never
 * written to a packet and never restored by a rewind. It is read, once per digest, straight
 * into the digest buffer.
 *
 * Putting `@AgentState` and `@Net` on the same property is therefore legal and means two
 * unrelated things: replicate it, *and* surface it to the agent by name.
 *
 * ### Scalars only, by construction
 *
 * The bridge contract (`game-bridge-mcp`, `GET /state`) says of `game`: "scalar fields are
 * included in the digest. Nested objects and arrays are not." A non-scalar here would not be
 * a value that renders oddly — it would be a value that vanishes from every digest an agent
 * ever reads, silently. So the allowed types are `Int`, `Long`, `Float`, `Double`,
 * `Boolean`, `String` and enums (published by constant name), and anything else is a build
 * error at the property.
 *
 * Entity data is deliberately not reachable this way: that is `describe_entity` over
 * `Replicator.getField`, and keeping it out of the digest is the whole point of the
 * restriction.
 *
 * @param name the key the value appears under in the `game` block. Empty means "use the
 *   property's own name". Two properties resolving to the same effective name are a build
 *   error rather than a last-writer-wins collision.
 *
 * Retention is [AnnotationRetention.BINARY]: the digest writer is a generated file, so
 * nothing looks this annotation up at runtime.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class AgentState(
    val name: String = "",
)
