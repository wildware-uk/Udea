package dev.wildware.udea.net.rpc

import dev.wildware.udea.annotations.Authority
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.transport.PeerId

/**
 * Why the server did not run an incoming RPC — **typed**, and naming the rule that refused it.
 *
 * A boolean would have been enough to stop the attack and useless for everything else. The old
 * stack refused nothing at all (`PacketUtil.kt:148`, `// TODO validate the sender!`), so there
 * is no prior art here to be compatible with; what there is, is a lesson from
 * `ActivationResult` next door in `udea-gas`, where "the ability did not fire" being a typed
 * reason rather than a `false` is what let the HUD say *why* and the agent surface report it.
 *
 * A refusal is a value and not an exception. An RPC arriving from a hostile or merely buggy
 * client is an ordinary event on the server's hot path — one per datagram, potentially per
 * connection per tick — and unwinding the stack for it would let a flood of them cost more
 * than serving the honest traffic.
 */
public sealed class RpcRefusal {

    /** The RPC's fully-qualified name, as [RpcDescriptor.name] reports it. */
    public abstract val rpc: String

    /**
     * The rule that refused, in the words of the declaration that carries it —
     * `@Rpc(authority = OwnerPredicted)`, `@Rpc(direction = ServerToClient)`,
     * `@Rpc(ratePerSecond = 20)`.
     *
     * Named rather than described so that a log line, an agent tool's answer and a test
     * assertion all point at the same source text.
     */
    public abstract val rule: String

    /** One line, safe to log: what was refused, by which rule, and what the sender asked for. */
    public abstract val message: String

    override fun toString(): String = message

    /**
     * The sender does not own the entity it named. **This is the old engine's hole.**
     *
     * @param owner who actually owns [target]; [PeerId.SERVER] when no client does, which is
     *   the answer for every AI unit on the field — so "fire the enemy team's ultimate" and
     *   "fire a creep's ability" are both this refusal rather than one of them slipping past.
     */
    public data class NotOwner(
        override val rpc: String,
        public val authority: Authority,
        public val sender: PeerId,
        public val target: NetId,
        public val owner: PeerId,
    ) : RpcRefusal() {
        override val rule: String get() = "@Rpc(authority = $authority)"
        override val message: String
            get() = "$sender may not invoke $rpc on $target: $rule requires the sender to own " +
                "the entity, and $target is owned by $owner."
    }

    /**
     * The RPC is declared [Authority.Server], so no connection may invoke it.
     *
     * Unreachable from a well-formed build for a [RpcDirection.ClientToServer] function, which
     * `udea-codegen` refuses to emit — but a *server-originated* RPC replayed back at the
     * server by a client lands here, and that is the case this exists for.
     */
    public data class ServerOnly(
        override val rpc: String,
        public val authority: Authority,
        public val sender: PeerId,
    ) : RpcRefusal() {
        override val rule: String get() = "@Rpc(authority = $authority)"
        override val message: String
            get() = "$sender may not invoke $rpc: $rule means the server is the only caller."
    }

    /** A client sent a call only the server may originate. */
    public data class WrongDirection(
        override val rpc: String,
        public val direction: RpcDirection,
        public val sender: PeerId,
    ) : RpcRefusal() {
        override val rule: String get() = "@Rpc(direction = $direction)"
        override val message: String
            get() = "$sender sent $rpc, which is $rule and therefore never travels that way."
    }

    /**
     * The connection is calling this RPC faster than it declared it may be called.
     *
     * Raised **before** the arguments are decoded, so a flood costs one `varint` read per
     * datagram rather than a full decode — which is why this refusal cannot name a target.
     */
    public data class RateLimited(
        override val rpc: String,
        public val sender: PeerId,
        public val rate: RpcRate,
    ) : RpcRefusal() {
        override val rule: String get() = "@Rpc(ratePerSecond = ${rate.perSecond}, burst = ${rate.burst})"
        override val message: String
            get() = "$sender exceeded the rate declared for $rpc: $rule."
    }

    /**
     * The datagram named an RPC index this build does not have.
     *
     * Distinct from a malformed stream: the index decoded cleanly and simply is not in the
     * registry, which means either a peer built from different sources — `protoHash` should
     * already have refused the connection — or a hand-crafted packet.
     */
    public data class UnknownRpc(public val index: Int, public val known: Int) : RpcRefusal() {
        override val rpc: String get() = "rpc#$index"
        override val rule: String get() = "the connection's shared RPC registry"
        override val message: String
            get() = "no RPC is registered at index $index; this build knows $known."
    }
}
