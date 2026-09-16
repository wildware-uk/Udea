package dev.wildware.udea.net.transport

/**
 * Every tunable of [WebSocketTransport] and its desktop server, in counts and bytes (issue #209).
 *
 * No durations. A WebSocket rides TCP, which retransmits, times out and tears connections down on
 * its own, so this layer has no keep-alive or timeout of its own to express in ticks: a connection
 * that TCP gives up on arrives here as a close.
 */
public data class WebSocketConfig(

    /**
     * Largest message [Transport.send] will take, in bytes.
     *
     * A WebSocket frames messages itself, so unlike [UdpConfig.mtu] this is not a datagram limit
     * but a ceiling on what one peer can make the other buffer. A larger send throws
     * [DatagramTooLargeException] at the sender, as the UDP transport does.
     */
    public val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,

    /** Connection slots on a server. Peers are `client(1)` through `client(maxClients)`. */
    public val maxClients: Int = UdpConfig.DEFAULT_MAX_CLIENTS,

    /**
     * Messages one [Transport.poll] hands over before returning, so a flood cannot hold the
     * simulation thread for as long as it lasts. The rest wait for the next poll.
     */
    public val maxReceivesPerPoll: Int = UdpConfig.DEFAULT_MAX_RECEIVES_PER_POLL,

    /**
     * Messages queued in each direction between the game loop and a connection's coroutine.
     *
     * Inbound, a full queue stops the connection's reader and TCP's own flow control pushes back on
     * the sender. Outbound, a send that finds the queue full is dropped and counted in
     * [TransportStats.packetsDropped], which [Transport.send] is always allowed to do.
     */
    public val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {

    init {
        require(maxMessageBytes >= 1) { "maxMessageBytes must be >= 1, was $maxMessageBytes" }
        require(maxClients >= 1) { "maxClients must be >= 1, was $maxClients" }
        require(maxReceivesPerPoll >= 1) { "maxReceivesPerPoll must be >= 1, was $maxReceivesPerPoll" }
        require(queueCapacity >= 1) { "queueCapacity must be >= 1, was $queueCapacity" }
    }

    public companion object {

        /** 64KB: fifty full 1200-byte snapshot datagrams in one message, far past any real one. */
        public const val DEFAULT_MAX_MESSAGE_BYTES: Int = 64 * 1024

        /** 256 messages: four seconds of 60Hz traffic to one peer before anything is dropped. */
        public const val DEFAULT_QUEUE_CAPACITY: Int = 256
    }
}

/** What a WebSocket end refused or dropped, by why. Live, like [TransportStats]. */
internal class WebSocketCounters {

    /** Messages this build could not make sense of. */
    var malformed: Long = 0L

    /** Sends addressed to a peer with no live connection. */
    var sendsToUnknownPeer: Long = 0L

    /** Handshakes that reached a live connection. */
    var handshakesCompleted: Long = 0L

    /** Handshakes refused outright, with a reason sent back. */
    var handshakesDenied: Long = 0L

    override fun toString(): String =
        "WebSocketCounters(malformed=$malformed, sendsToUnknownPeer=$sendsToUnknownPeer, " +
            "completed=$handshakesCompleted, denied=$handshakesDenied)"
}
