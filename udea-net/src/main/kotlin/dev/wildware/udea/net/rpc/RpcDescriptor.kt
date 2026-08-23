package dev.wildware.udea.net.rpc

import dev.wildware.udea.annotations.Authority
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.net.transport.PeerId

/**
 * One generated RPC, as everything above the generator sees it.
 *
 * **Deliberately not generic in the argument type.** A dispatcher holds a heterogeneous list of
 * these and has to decode one from a datagram whose type it learns from an integer, which no
 * amount of variance makes typed; making this `RpcDescriptor<A>` would put an unchecked cast in
 * the one place the whole design exists to keep honest. The typed surface is the *generated
 * send function*, which is a normal Kotlin call with normal Kotlin parameters, and
 * [receive] is where the types stop mattering because the bytes are what is left.
 *
 * `udea-codegen` emits one `object` per `@Rpc` function implementing this. Nothing else may.
 */
public interface RpcDescriptor {

    /**
     * The fully-qualified name of the annotated function — `dev.wildware.moba.fireAbility`.
     *
     * The identity the registry sorts by and the wire index is assigned from, and the string
     * every [RpcRefusal] names. Not on the wire itself: a name per call would cost more than
     * the call.
     */
    public val name: String

    /** [Rpc.direction]. */
    public val direction: RpcDirection

    /** [Rpc.authority], baked in by the generator and read by the guard inside [receive]. */
    public val authority: Authority

    /** [Rpc.reliability]. Read by the send path, not by [receive]. */
    public val reliability: RpcReliability

    /** [Rpc.relevancy]. Read by the server when fanning a call out. */
    public val relevancy: RpcRelevancy

    /** [Rpc.ratePerSecond] and [Rpc.burst], resolved. */
    public val rate: RpcRate

    /**
     * Decodes one call from [src] and runs it **if the sender is allowed to**.
     *
     * The generated body is, in order: read the arguments; run the authority guard emitted from
     * [authority]; call the annotated function. Returning a non-null [RpcRefusal] means nothing
     * was called and nothing was mutated — the guard runs before the invocation and there is no
     * partial path between them, exactly as `AbilityActivation.canActivate` gates
     * `AbilityActivation.activate`.
     *
     * @param sender the connection the datagram arrived from.
     * @param ownership who owns which entity, this tick.
     */
    public fun receive(sender: PeerId, ownership: RpcOwnership, src: BitReader): RpcRefusal?
}

/**
 * Who owns which entity, as the RPC guard asks it.
 *
 * A `fun interface` over `NetId` and nothing else, because ownership is the *only* question the
 * guard has and giving it a world would let a generated guard grow opinions. The old code
 * asked the process-wide `gameScreen.isServer` and answered for every entity at once
 * (`common/.../Abilities.kt:76`), which is how a client ended up with no abilities at all and
 * simultaneously how the server ended up trusting every packet.
 *
 * [PeerId.SERVER] means **no client owns this entity**, which is the answer for every AI unit,
 * every projectile and every entity id that does not exist. A guard comparing the sender to
 * that never matches, so "an entity nobody owns" and "an entity somebody else owns" refuse
 * identically rather than one of them falling through.
 */
public fun interface RpcOwnership {

    /** The connection that owns [entity], or [PeerId.SERVER] if none does. */
    public fun ownerOf(entity: NetId): PeerId

    public companion object {

        /** Nothing is client-owned. Every ownership-gated RPC refuses. The safe default. */
        public val NONE: RpcOwnership = RpcOwnership { PeerId.SERVER }
    }
}

/**
 * How often one connection may call one RPC, in **ticks**.
 *
 * Per second in the declaration and per tick in the arithmetic: the declaration is what a
 * designer can reason about ("twenty activations a second is more than a human"), and the
 * enforcement has to be tick-denominated or the limiter is a wall clock in the simulation and a
 * seeded soak stops reproducing (spec 5, Time; `NoWallClockInTransportTest`).
 *
 * @param perSecond calls per second, `0` for unlimited.
 * @param burst how many may arrive back to back. Zero resolves to one second's worth, which
 *   means a limit stated alone never refuses traffic that is on average within it.
 */
public data class RpcRate(public val perSecond: Int, public val burst: Int) {

    init {
        require(perSecond >= 0) { "ratePerSecond must not be negative, was $perSecond" }
        require(burst >= 0) { "burst must not be negative, was $burst" }
    }

    /** True when this RPC is not rate limited at all. */
    public val isUnlimited: Boolean get() = perSecond == 0

    /** [burst], or one second's worth when it was left at zero. */
    public val effectiveBurst: Int get() = if (burst > 0) burst else perSecond

    public companion object {

        /** No limit. What an RPC declaring no `ratePerSecond` gets. */
        public val UNLIMITED: RpcRate = RpcRate(perSecond = 0, burst = 0)
    }
}
