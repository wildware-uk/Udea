package dev.wildware.udea.net.transport

/**
 * The kind tag in byte zero of every datagram [UdpTransport] sends.
 *
 * An enum with a fixed id rather than a sealed hierarchy, and that is a deliberate departure
 * from the project's usual preference: a sealed packet type means one object per datagram
 * received, which at 60Hz times ten clients is 600 allocations a second in the one place this
 * module exists to keep allocation-free. The tag selects a branch that reads the datagram in
 * place instead.
 *
 * Ids are frozen. Spec section 7 is explicit that hardening added after a wire format is live
 * is a wire-format break, which is why the handshake kinds are here in the first version
 * rather than bolted on later.
 */
internal enum class UdpPacketType(val id: Int) {

    /** Client to server, padded to the MTU. Opens a handshake and costs the server no state. */
    ConnectionRequest(1),

    /** Server to client. Carries a token the server did not have to remember. */
    ConnectionChallenge(2),

    /** Client to server, padded to the MTU. Returns the token from the address it was minted for. */
    ConnectionResponse(3),

    /** Server to client. The connection exists; here is its salt and your peer id. */
    ConnectionAccepted(4),

    /** Server to client. The connection will not be opened, and why. */
    ConnectionDenied(5),

    /** Either direction. Opaque bytes from the layer above, plus this link's sequencing. */
    Payload(6),

    /** Either direction. A courtesy: the peer is going away now rather than in a timeout. */
    Disconnect(7),
    ;

    companion object {

        private val BY_ID: Array<UdpPacketType?> = arrayOfNulls<UdpPacketType>(MAX_ID + 1).also {
            for (type in entries) it[type.id] = type
        }

        /**
         * The type with [id], or null.
         *
         * Null rather than an exception: an unknown id is the single cheapest thing a hostile
         * peer can send, and turning each one into a thrown, filled-in stack trace would make
         * the parser itself the denial of service. The caller counts it and drops the datagram.
         */
        fun of(id: Int): UdpPacketType? = if (id in 0..MAX_ID) BY_ID[id] else null

        private const val MAX_ID: Int = 7
    }
}

/**
 * Why a connection ended, or was never opened.
 *
 * Public because a game has to tell a player the difference between "the server is full" and
 * "your build does not match", and because the old stack could tell them neither.
 */
public enum class DisconnectReason(internal val id: Int) {

    /** Every client slot is taken. */
    ServerFull(1),

    /** The peer advertised a different [dev.wildware.udea.net.wire.ProtocolDescriptor.protoHash]. */
    ProtocolMismatch(2),

    /** Nothing arrived from the peer within the configured timeout. */
    Timeout(3),

    /** The peer said it was leaving. */
    RemoteClosed(4),

    /** This end closed the transport. */
    LocalClosed(5),

    /** The handshake never completed within the connect timeout. Client side only. */
    HandshakeTimeout(6),
    ;

    internal companion object {

        private val BY_ID: Map<Int, DisconnectReason> = entries.associateBy(DisconnectReason::id)

        /** The reason with [id], or [Timeout] for an id this build does not know. */
        fun of(id: Int): DisconnectReason = BY_ID[id] ?: Timeout
    }
}

/**
 * Byte offsets of every field in every datagram this transport writes.
 *
 * Written out as named offsets rather than a stream of `put` calls so that the layout is
 * readable in one place and so that a reader and a writer cannot drift: both index the same
 * constants. Everything is byte-aligned and big-endian — the bit-level packing that saves real
 * bandwidth belongs to the snapshot payload inside, not to a per-datagram header that is
 * eighteen bytes either way.
 *
 * The header this describes sits *underneath*
 * [dev.wildware.udea.net.wire.PacketHeader], and the duplication of `seq`/`ack` between the
 * two is intentional rather than an oversight. They sequence different things: the wire
 * header sequences *snapshots*, so its ack is what selects a baseline out of the snapshot
 * ring, and it exists only on packets the replication layer sends. This one sequences
 * *datagrams*, including the keep-alives and fragments the replication layer never sees, and
 * is what measures the link. Collapsing them would either make the replication layer aware of
 * fragments or make the transport aware of snapshots.
 */
internal object UdpLayout {

    /** Byte zero of every datagram, in every direction. */
    const val TYPE: Int = 0

    // --- Payload ---

    /** The 64-bit value that proves the sender was in on this connection's handshake. */
    const val PAYLOAD_SALT: Int = 1

    const val PAYLOAD_SEQ: Int = 9
    const val PAYLOAD_ACK: Int = 11
    const val PAYLOAD_ACK_BITS: Int = 13

    /** Zero for a whole message, one for a fragment of one. */
    const val PAYLOAD_FRAGMENT_FLAG: Int = 17

    /** Bytes before the body of an unfragmented payload. */
    const val PAYLOAD_HEADER_BYTES: Int = 18

    const val FRAGMENT_MESSAGE_ID: Int = 18
    const val FRAGMENT_INDEX: Int = 20
    const val FRAGMENT_COUNT: Int = 21

    /** Bytes before the body of a fragment. */
    const val FRAGMENT_HEADER_BYTES: Int = 22

    /** Value of [PAYLOAD_FRAGMENT_FLAG] on a fragment. */
    const val FRAGMENT_FLAG_SET: Int = 1

    // --- Handshake ---

    const val REQUEST_PROTO_HASH: Int = 1
    const val REQUEST_CLIENT_SALT: Int = 3

    /** Meaningful bytes of a connection request. The rest of the datagram is padding. */
    const val REQUEST_BODY_BYTES: Int = 11

    const val CHALLENGE_CLIENT_SALT: Int = 1
    const val CHALLENGE_TOKEN: Int = 9
    const val CHALLENGE_EXPIRY: Int = 17
    const val CHALLENGE_BYTES: Int = 25

    const val RESPONSE_PROTO_HASH: Int = 1
    const val RESPONSE_CLIENT_SALT: Int = 3
    const val RESPONSE_TOKEN: Int = 11
    const val RESPONSE_EXPIRY: Int = 19

    /** Meaningful bytes of a connection response. The rest of the datagram is padding. */
    const val RESPONSE_BODY_BYTES: Int = 27

    const val ACCEPTED_SALT: Int = 1
    const val ACCEPTED_PEER: Int = 9
    const val ACCEPTED_BYTES: Int = 11

    const val DENIED_REASON: Int = 1
    const val DENIED_BYTES: Int = 2

    const val DISCONNECT_SALT: Int = 1
    const val DISCONNECT_BYTES: Int = 9
}
