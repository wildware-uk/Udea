package dev.wildware.udea.net.rpc

import dev.wildware.udea.annotations.Authority

/**
 * Marks a function as a **remote procedure call**, from which `udea-codegen` emits the codec,
 * the send helper and — the point of the whole exercise — the **authority guard** (issue #109).
 *
 * ## The defect this exists to make unreachable
 *
 * `common/.../network/PacketUtil.kt:148` carried a literal `// TODO validate the sender!`.
 * `AbilityPacket` named an arbitrary entity id and an ability, the server read both and fired
 * it, and nothing anywhere asked whether the connection that sent the packet had any
 * relationship to the entity named in it. Any client could fire any ability on any entity,
 * including the enemy team's ultimates, and the fix — "remember to check the sender" — is the
 * kind of fix that holds until the next packet type is added.
 *
 * So the check is **generated**. [authority] is not documentation: `udea-codegen` reads it and
 * emits the guard into `RpcDescriptor.receive` before the call reaches the function body, and
 * there is no path from a datagram to that body which does not pass through it. A developer
 * who forgets to validate the sender has not written a validation-free RPC; they have written
 * an RPC whose declared authority is [Authority.Server], which refuses every client outright.
 *
 * ## Why this annotation is in `udea-net` and not `udea-annotations`
 *
 * `udea-annotations` is the frozen `@Net`/`@Sim`/`@Q`/`@Replicated` vocabulary of spec 5 — the
 * component field space, which four consumers read and none may extend casually. An RPC is not
 * a field: it has no mask bit, no `FieldStore` column and no snapshot presence. It also cannot
 * be declared by a module that does not have `udea-net` on its classpath, because the function
 * it marks is only reachable over a `Transport`. Declaring it here makes that dependency the
 * compiler's problem rather than a convention.
 *
 * The **authority vocabulary itself** is still the one in `udea-annotations` (spec 5,
 * "Authority vocabulary": "RPC guards, mask stripping, GAS prediction eligibility and
 * `set_component_field` all read the same declarations"). This annotation names
 * [Authority] rather than minting a second three-valued enum that means nearly the same thing.
 *
 * @param direction who may send it. See [RpcDirection].
 * @param authority who may *invoke* it, in the same vocabulary that says who may write a
 *   `@Net` field:
 *   - [Authority.Server] — no client may call it. On a [RpcDirection.ClientToServer] function
 *     that is a contradiction and `udea-codegen` reports it as one, because such an RPC could
 *     never be invoked by anybody.
 *   - [Authority.OwnerPredicted] — the sending connection must own the entity named by the
 *     RPC's `NetId` parameter. The owning client may also run the call locally and be
 *     reconciled. This is what an ability activation is.
 *   - [Authority.OwnerWritable] — same ownership check; the server relays rather than
 *     simulating. Reserved for cosmetic state, exactly as on a field.
 * @param reliability whether a dropped call is re-sent. Declared per RPC because the answer
 *   differs per call and a single channel-wide setting forces the worst case on everything.
 * @param relevancy who a server-to-client or multicast call reaches.
 * @param ratePerSecond calls per second this RPC accepts from one connection, `0` for
 *   unlimited. Enforced in **ticks**, never wall time, so a soak test reproduces.
 * @param burst how many calls may arrive back to back before the rate applies. Defaults to one
 *   second's worth.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Rpc(
    val direction: RpcDirection,
    val authority: Authority = Authority.Server,
    val reliability: RpcReliability = RpcReliability.Reliable,
    val relevancy: RpcRelevancy = RpcRelevancy.Owner,
    val ratePerSecond: Int = 0,
    val burst: Int = 0,
)

/**
 * Which way a call travels, and therefore which endpoint refuses it.
 *
 * Not derivable from [Authority]: an [Authority.OwnerPredicted] call is client-to-server, but a
 * server-to-client cue and a multicast both carry [Authority.Server] and are two different
 * things — one reaches the owner, one reaches everybody who can see the entity.
 */
public enum class RpcDirection {

    /** Client sends, server executes. The only direction a client may originate. */
    ClientToServer,

    /** Server sends to one connection — normally the owner of the entity named. */
    ServerToClient,

    /** Server sends to every connection [RpcRelevancy] admits. */
    Multicast,
}

/** Whether a dropped call is re-sent. */
public enum class RpcReliability {

    /** Re-sent until acked. An ability activation: dropping it loses a player's input. */
    Reliable,

    /**
     * Sent once. For a call whose next copy supersedes it — a cosmetic cue, a ping — where a
     * retransmit arrives after it stopped being true.
     */
    Unreliable,
}

/** Who a server-originated call reaches. */
public enum class RpcRelevancy {

    /** The connection owning the entity named by the call, and nobody else. */
    Owner,

    /** Every connection whose relevancy set currently contains the entity. */
    Relevant,

    /** Every connected client, relevancy ignored. Match-wide announcements only. */
    All,
}
