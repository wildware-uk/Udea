package dev.wildware.udea.net.transport

/** The first byte of every WebSocket message this transport sends. */
internal enum class WebSocketMessageType(val id: Int) {

    /** Client to server, first: the protocol hash this client was built with. */
    Hello(1),

    /** Server to client: the peer id the client now answers to. */
    Accepted(2),

    /** Server to client: why it will not serve this client. The server then closes. */
    Denied(3),

    /** Either way, once accepted: the bytes a [Transport.send] was handed. */
    Payload(4),
    ;

    companion object {

        private val BY_ID: Map<Int, WebSocketMessageType> = entries.associateBy(WebSocketMessageType::id)

        /** The type with [id], or null for one this build does not know. */
        fun of(id: Int): WebSocketMessageType? = BY_ID[id]
    }
}

/**
 * The layout of a WebSocket message (issue #209).
 *
 * A byte of [WebSocketMessageType] and a body. Much less than [UdpLayout], and deliberately: the
 * connection is TCP, so there is no salt to prove an address, no sequence to refuse a replay and no
 * fragments to rebuild - the WebSocket already delivers each message whole, once and in order. What
 * is left is the one thing the UDP handshake does that TCP does not: agree the protocol hash and hand
 * out a peer id.
 */
internal object WebSocketLayout {

    const val TYPE: Int = 0

    const val HELLO_PROTO_HASH: Int = 1
    const val HELLO_BYTES: Int = 3

    const val ACCEPTED_PEER: Int = 1
    const val ACCEPTED_BYTES: Int = 3

    const val DENIED_REASON: Int = 1
    const val DENIED_BYTES: Int = 2

    const val PAYLOAD_BODY: Int = 1

    fun hello(protoHash: Int): ByteArray = ByteArray(HELLO_BYTES).also {
        it[TYPE] = WebSocketMessageType.Hello.id.toByte()
        BigEndian.putShort(it, HELLO_PROTO_HASH, protoHash.toShort())
    }

    fun accepted(peer: PeerId): ByteArray = ByteArray(ACCEPTED_BYTES).also {
        it[TYPE] = WebSocketMessageType.Accepted.id.toByte()
        BigEndian.putShort(it, ACCEPTED_PEER, peer.raw.toShort())
    }

    fun denied(reason: DisconnectReason): ByteArray = ByteArray(DENIED_BYTES).also {
        it[TYPE] = WebSocketMessageType.Denied.id.toByte()
        it[DENIED_REASON] = reason.id.toByte()
    }

    /** A copy of `bytes[offset, offset + length)` behind a payload type byte. */
    fun payload(bytes: ByteArray, offset: Int, length: Int): ByteArray = ByteArray(PAYLOAD_BODY + length).also {
        it[TYPE] = WebSocketMessageType.Payload.id.toByte()
        bytes.copyInto(it, PAYLOAD_BODY, offset, offset + length)
    }

    /** The type of [message], or null when it is empty or of a type this build does not know. */
    fun typeOf(message: ByteArray): WebSocketMessageType? =
        if (message.isEmpty()) null else WebSocketMessageType.of(message[TYPE].toInt() and 0xFF)

    fun unsignedShort(message: ByteArray, offset: Int): Int = BigEndian.getShort(message, offset).toInt() and 0xFFFF
}
