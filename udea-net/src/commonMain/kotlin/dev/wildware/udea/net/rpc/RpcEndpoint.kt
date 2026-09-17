package dev.wildware.udea.net.rpc

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.bits.writeVarInt
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.FrameWriter
import dev.wildware.udea.net.wire.MessageType

/**
 * Writes one RPC call into a datagram: `u8 type | u16 length | varint rpcIndex | arguments`.
 *
 * One call per frame, deliberately. Several calls could share a frame and save two bytes each;
 * they would also share a length prefix, so a call whose arguments a hostile peer truncated
 * would take the calls after it down with it. The frame *is* the containment boundary
 * (`FrameReader`), and the thing being contained here is a decode driven by a remote integer.
 *
 * Note what this cannot express: there is no way to put a replicated component field in it.
 * The claim that a client-to-server datagram carries an ack and an input and nothing else
 * survives RPCs, because an RPC payload is *arguments the generator wrote a codec for* and
 * never a `FieldStore` row.
 */
public class RpcOutbox(

    /** The shared registry. Supplies the index every call is written under. */
    private val registry: RpcRegistry,
) {

    private var open: RpcDescriptor? = null

    /**
     * Opens a frame for [rpc] and returns the writer its arguments go into.
     *
     * Generated send functions call this, write their arguments and call [close]. Nothing else
     * should: the pairing is what keeps the frame length prefix correct.
     */
    public fun open(frames: FrameWriter, rpc: RpcDescriptor): BitWriter {
        check(open == null) { "an RPC frame for ${open?.name} is still open" }
        open = rpc
        val out = frames.beginMessage(MessageType.Rpc)
        out.writeVarInt(registry.indexOf(rpc))
        return out
    }

    /** Closes the frame opened by [open]. */
    public fun close(frames: FrameWriter) {
        checkNotNull(open) { "no RPC frame is open" }
        open = null
        frames.endMessage()
    }
}

/**
 * The server receive path: decode which RPC, refuse it or run it.
 *
 * ## The order of the checks is the design
 *
 * 1. **Index** - one `varint`. An unknown one is refused before anything else is read.
 * 2. **Direction** - a client sending a server-originated call is refused before the bucket is
 *    touched, because it is a protocol error rather than a load event.
 * 3. **Rate** - before the arguments are decoded. A flood must cost the server a `varint` read
 *    and a subtraction, not a full argument decode; that is the whole point of the limiter, and
 *    checking it afterwards would let a flood pay for itself.
 * 4. **Authority** - inside the generated [RpcDescriptor.receive], after the arguments, because
 *    the entity the guard is about is one of them.
 *
 * Steps 1-3 are here because they are the same for every RPC, and duplicating them into every
 * generated object would be forty copies of a check that has to stay identical. Step 4 is
 * generated, because it is the one that differs per declaration and the one the old engine left
 * to memory (`PacketUtil.kt:148`, `// TODO validate the sender!`).
 */
public class RpcServer(

    /** The RPCs this build speaks. */
    public val registry: RpcRegistry,

    /** Who owns which entity. Consulted by the generated guard, never by this class. */
    private val ownership: RpcOwnership,

    /** Per-connection, per-RPC buckets. */
    private val limiter: RpcRateLimiter,
) {

    /** Calls that ran. */
    public var accepted: Long = 0L
        private set

    /** Calls refused, for any reason. Both are counters and not logs: a flood must not write. */
    public var refused: Long = 0L
        private set

    /**
     * Reads one RPC frame from [src] and runs it if [sender] is allowed to.
     *
     * @param now the server current simulation tick; the only clock the limiter has.
     * @return `null` when the call ran, otherwise the typed reason it did not.
     */
    public fun receive(sender: PeerId, src: BitReader, now: Tick): RpcRefusal? {
        val index = src.readVarInt()
        val rpc = registry.at(index) ?: return refuse(RpcRefusal.UnknownRpc(index, registry.size))
        if (rpc.direction != RpcDirection.ClientToServer) {
            return refuse(RpcRefusal.WrongDirection(rpc.name, rpc.direction, sender))
        }
        if (!limiter.allow(sender, index, rpc.rate, now)) {
            return refuse(RpcRefusal.RateLimited(rpc.name, sender, rpc.rate))
        }
        val refusal = rpc.receive(sender, ownership, src)
        if (refusal != null) return refuse(refusal)
        accepted++
        return null
    }

    private fun refuse(refusal: RpcRefusal): RpcRefusal {
        refused++
        return refusal
    }
}
