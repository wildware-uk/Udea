package dev.wildware.moba.ability

import dev.wildware.udea.annotations.Authority
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.net.rpc.Rpc
import dev.wildware.udea.net.rpc.RpcDescriptor
import dev.wildware.udea.net.rpc.RpcDirection
import dev.wildware.udea.net.rpc.RpcOwnership
import dev.wildware.udea.net.rpc.RpcRegistry
import dev.wildware.udea.net.transport.PeerId

/**
 * The one thing a client of this game is allowed to ask the server to do (issue #109).
 *
 * ## The attack this closes, in this game
 *
 * The old example really did run two machines over KryoNet, and the way it fired an ability was
 * `AbilityPacket(entityId, abilityIndex)`: the client named an entity, the server looked it up
 * and fired, and `common/.../network/PacketUtil.kt:148` carried the literal comment
 * `// TODO validate the sender!`. Nothing anywhere asked whether the connection that sent the
 * packet had any relationship to the entity named in it. A client could fire the enemy team's
 * ultimate, or an orc's spin, or a heal on a unit it had never seen.
 *
 * [activateAbility] is the replacement, and the check is not in this file. `udea-codegen` reads
 * `@Rpc(authority = OwnerPredicted)` and emits `ActivateAbilityRpc.receive` with the ownership
 * comparison in it, ahead of the call; there is no route from a datagram to the body below that
 * skips it, and a declaration with nothing to check fails the build rather than generating a
 * guard-shaped no-op.
 *
 * ## Why the body reaches the world through a bound sink
 *
 * An `@Rpc` function is top-level — a datagram carries arguments and no receiver, so there is
 * no instance for the server to call it on — and this game's activation path lives on a
 * `GameHost`. [bind] is the seam, and it is deliberately the narrowest one that works: a
 * `NetId`, a slot, and an [ActivationResult] back. It is **mutable process state**, which is
 * the honest cost of a top-level RPC body, and it is scoped as tightly as this design allows:
 * [UNBOUND] refuses every activation, so an unbound process is a server that answers
 * [ActivationResult.NoAuthority] rather than one that silently does nothing.
 */
public object AbilityRpc {

    /**
     * What an unbound process does with an activation: refuses it, by name.
     *
     * Not a no-op lambda. "Nothing happened" and "the server is not wired up" are the two
     * things that look identical from outside a process, and the whole reason
     * [ActivationResult] is a typed refusal rather than a boolean.
     */
    public val UNBOUND: AbilityActivationSink = AbilityActivationSink { _, _ -> ActivationResult.NoAuthority }

    /** Where an accepted activation goes. [UNBOUND] until a server binds one. */
    public var sink: AbilityActivationSink = UNBOUND
        private set

    /** The result of the most recent accepted call, for a HUD, a log line or a test. */
    public var lastResult: ActivationResult = ActivationResult.NoAuthority
        private set

    /** Points accepted activations at [sink]. A server calls this once, when its world exists. */
    public fun bind(sink: AbilityActivationSink) {
        this.sink = sink
    }

    /** Restores [UNBOUND]. A dedicated server calls this on shutdown; a test calls it in teardown. */
    public fun unbind() {
        sink = UNBOUND
        lastResult = ActivationResult.NoAuthority
    }

    /** Runs an activation the guard has already accepted. Called only by the generated descriptor. */
    internal fun accept(self: NetId, slot: Int) {
        lastResult = sink.activate(self, slot)
    }

    /**
     * Every RPC this game speaks.
     *
     * Listed here rather than discovered through `ServiceLoader`, and that is a stated gap
     * rather than a design: `NetModule` discovery exists for replicators and nothing analogous
     * has been built for RPCs yet, so a second module adding an `@Rpc` would have to be added
     * to this list by hand. `RpcRegistry` sorts by name, so the wire index is still a pure
     * function of the set and not of the order written here.
     */
    public val descriptors: List<RpcDescriptor> = listOf(ActivateAbilityRpc)

    /** The registry both ends of a session share. */
    public fun registry(): RpcRegistry = RpcRegistry(descriptors)
}

/**
 * How an accepted [activateAbility] call reaches this game's activation path.
 *
 * A `fun interface` over exactly the arguments the RPC carries, so the server side of the wire
 * cannot reach anything the RPC did not name.
 */
public fun interface AbilityActivationSink {

    /** Activates [slot] on [self] and says what happened. */
    public fun activate(self: NetId, slot: Int): ActivationResult
}

/**
 * How a client asks for an activation. Non-null only in a process that is actually connected.
 *
 * Nullable at the call site rather than a no-op default, because "this game is not networked"
 * is the configuration nearly every test and the whole of single-player runs in, and a channel
 * that silently swallowed calls would make a wiring mistake invisible.
 */
public fun interface AbilityRpcChannel {

    /** Sends one [activateAbility] call for [self]'s [slot]. */
    public fun activateAbility(self: NetId, slot: Int)
}

/**
 * Which connection owns which champion, as the generated guard asks it.
 *
 * Every entity this does not know about is [PeerId.SERVER] — which is every AI unit on the
 * field, every arrow in flight and every id that does not exist. The guard compares the sender
 * against that and never matches, so "fire the enemy's ultimate" and "fire a creep's ability"
 * are refused identically instead of one of them falling through a special case.
 */
public class ChampionOwnership : RpcOwnership {

    private val owners = HashMap<Int, PeerId>()

    /** Records that [peer] drives [champion]. */
    public fun assign(champion: NetId, peer: PeerId) {
        owners[champion.raw] = peer
    }

    /** Forgets [peer]'s champions. Called when a connection drops. */
    public fun release(peer: PeerId) {
        owners.values.removeAll { it == peer }
    }

    override fun ownerOf(entity: NetId): PeerId = owners[entity.raw] ?: PeerId.SERVER
}

/**
 * The client asking the server to start one of **its own** champion's ability slots.
 *
 * `authority = OwnerPredicted` is the whole declaration: the sending connection must own
 * [self], and — being predicted — the owning client also runs the activation locally so the
 * swing appears on the tick the key went down rather than a round trip later.
 * `PlayerControlSystem` does both when it has a channel.
 *
 * `ratePerSecond = 20` against a 60Hz simulation is three times what mashing a key can produce
 * and far below what a script can. It is enforced in ticks by `RpcRateLimiter`, so a seeded
 * soak that trips it trips it again.
 *
 * The body is three lines and none of them is a permission check, because the permission check
 * is generated into `ActivateAbilityRpc.receive` and runs before this is reached.
 */
@Rpc(
    direction = RpcDirection.ClientToServer,
    authority = Authority.OwnerPredicted,
    ratePerSecond = 20,
    burst = 8,
)
public fun activateAbility(self: NetId, slot: Int) {
    AbilityRpc.accept(self, slot)
}
