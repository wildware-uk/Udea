package dev.wildware.udea.net.transport

/**
 * Every tunable of a [UdpTransport], in ticks and bytes.
 *
 * Durations are [dev.wildware.udea.core.Tick] counts rather than milliseconds for the same
 * reason [NetConditions] is: the transport reads a [ManualClock] and never the wall, so a
 * timeout expressed in seconds would mean different things in a test that steps the clock and
 * in a game that steps it at 60Hz. Defaults below are quoted at 60Hz.
 */
public data class UdpConfig(

    /**
     * Largest datagram sent or accepted.
     *
     * Defaults to the same 1200 bytes [LoopbackNetwork] uses, so a payload that fits the
     * in-memory transport fits the socket. A datagram larger than this is refused rather than
     * quietly truncated — see [UdpTransport.OVERSIZE_MARGIN_BYTES].
     */
    public val mtu: Int = LoopbackNetwork.DEFAULT_MTU,

    /** Connection slots. Peers are `client(1)` through `client(maxClients)`. */
    public val maxClients: Int = DEFAULT_MAX_CLIENTS,

    /** Ticks with nothing from a peer before the connection is declared dead. */
    public val timeoutTicks: Long = DEFAULT_TIMEOUT_TICKS,

    /** Ticks a client keeps retrying the handshake before giving up. */
    public val connectTimeoutTicks: Long = DEFAULT_CONNECT_TIMEOUT_TICKS,

    /** Ticks of silence after which an empty payload goes out to keep acks flowing. */
    public val keepAliveIntervalTicks: Long = DEFAULT_KEEP_ALIVE_INTERVAL_TICKS,

    /**
     * How long a challenge token stays valid.
     *
     * The window in which a token captured off the wire could be replayed — from the address
     * it was minted for, since the address is inside the MAC. Short enough to be a narrow
     * window, long enough that a client on a bad link can still complete a handshake that took
     * several retries.
     */
    public val tokenLifetimeTicks: Long = DEFAULT_TOKEN_LIFETIME_TICKS,

    /** Floor on the retransmit timeout, so a fast link does not retry into its own round trip. */
    public val minRtoTicks: Long = DEFAULT_MIN_RTO_TICKS,

    /** Ceiling on the retransmit timeout. */
    public val maxRtoTicks: Long = DEFAULT_MAX_RTO_TICKS,

    /**
     * Datagrams one [UdpTransport.poll] will take before returning.
     *
     * A poll that drained an unbounded queue would let a flood hold the simulation thread for
     * as long as the flood lasts. This bounds a tick's receive cost; anything beyond it waits
     * in the socket buffer for the next tick or is dropped by the kernel, which is the correct
     * back pressure.
     */
    public val maxReceivesPerPoll: Int = DEFAULT_MAX_RECEIVES_PER_POLL,

    /** Kernel socket buffer, both directions. */
    public val socketBufferBytes: Int = DEFAULT_SOCKET_BUFFER_BYTES,

    /**
     * Copies of the disconnect notice sent on close.
     *
     * It is unreliable and never retransmitted, so sending it once means a single lost
     * datagram costs the peer a full [timeoutTicks] of waiting. Three is cheap insurance.
     */
    public val disconnectSends: Int = DEFAULT_DISCONNECT_SENDS,
) {

    init {
        require(mtu >= MIN_MTU) { "mtu must be at least $MIN_MTU bytes, was $mtu" }
        require(maxClients >= 1) { "maxClients must be >= 1, was $maxClients" }
        require(timeoutTicks >= 1L) { "timeoutTicks must be >= 1, was $timeoutTicks" }
        require(connectTimeoutTicks >= 1L) { "connectTimeoutTicks must be >= 1, was $connectTimeoutTicks" }
        require(keepAliveIntervalTicks >= 1L) {
            "keepAliveIntervalTicks must be >= 1, was $keepAliveIntervalTicks"
        }
        require(keepAliveIntervalTicks < timeoutTicks) {
            "a keep-alive every $keepAliveIntervalTicks ticks cannot hold open a connection " +
                "that times out after $timeoutTicks"
        }
        require(tokenLifetimeTicks >= 1L) { "tokenLifetimeTicks must be >= 1, was $tokenLifetimeTicks" }
        require(minRtoTicks >= 1L) { "minRtoTicks must be >= 1, was $minRtoTicks" }
        require(maxRtoTicks >= minRtoTicks) { "maxRtoTicks must be >= minRtoTicks" }
        require(maxReceivesPerPoll >= 1) { "maxReceivesPerPoll must be >= 1, was $maxReceivesPerPoll" }
        require(socketBufferBytes >= mtu) { "socketBufferBytes must hold at least one datagram" }
        require(disconnectSends >= 1) { "disconnectSends must be >= 1, was $disconnectSends" }
    }

    public companion object {

        /**
         * The smallest MTU that still leaves room for a fragment header plus a byte of body.
         *
         * A configuration below this cannot carry a message at all, and failing at construction
         * is far better than discovering it on the first send.
         */
        public const val MIN_MTU: Int = UdpLayout.FRAGMENT_HEADER_BYTES + 1

        /** 32 slots: two full 5v5 lobbies with room for spectators. */
        public const val DEFAULT_MAX_CLIENTS: Int = 32

        /** 600 ticks, ten seconds at 60Hz: long enough to ride out a phone changing cell. */
        public const val DEFAULT_TIMEOUT_TICKS: Long = 600L

        /** 300 ticks, five seconds at 60Hz. */
        public const val DEFAULT_CONNECT_TIMEOUT_TICKS: Long = 300L

        /** 6 ticks, a tenth of a second at 60Hz. */
        public const val DEFAULT_KEEP_ALIVE_INTERVAL_TICKS: Long = 6L

        /** 600 ticks, ten seconds at 60Hz. */
        public const val DEFAULT_TOKEN_LIFETIME_TICKS: Long = 600L

        /** 6 ticks, a tenth of a second: above any loopback or LAN round trip. */
        public const val DEFAULT_MIN_RTO_TICKS: Long = 6L

        /** 240 ticks, four seconds: past this a link is not playable and should time out. */
        public const val DEFAULT_MAX_RTO_TICKS: Long = 240L

        /** 1024 datagrams: two orders of magnitude more than a healthy 32-client tick. */
        public const val DEFAULT_MAX_RECEIVES_PER_POLL: Int = 1024

        /** 1MB, roughly 870 MTU-sized datagrams: a tick of burst from every client at once. */
        public const val DEFAULT_SOCKET_BUFFER_BYTES: Int = 1 shl 20

        /** Three copies of the goodbye. */
        public const val DEFAULT_DISCONNECT_SENDS: Int = 3
    }
}

/**
 * Told when a connection opens and when it ends.
 *
 * Both methods have no-op defaults, so an embedder that only cares about one implements one.
 * Called from inside [UdpTransport.poll] and [UdpTransport.flush] on the calling thread, never
 * from a thread this transport owns — it owns none.
 */
public interface UdpConnectionListener {

    /** [peer] completed the handshake and may now be sent to. */
    public fun onConnected(peer: PeerId) {}

    /** [peer] is gone. No further datagram from it will be delivered. */
    public fun onDisconnected(peer: PeerId, reason: DisconnectReason) {}

    public companion object {

        /** Ignores everything. */
        public val NONE: UdpConnectionListener = object : UdpConnectionListener {}
    }
}

/**
 * Every datagram this transport refused, by why.
 *
 * Public and live, for the same reason [TransportStats] is: a refusal that is only visible in a
 * log cannot be asserted on, and "a malformed packet is refused without taking the server down"
 * is a claim about a counter moving and a process still running. `net.*` agent tools read these
 * too — "why is my client not connecting" is answerable from this object alone.
 */
public class UdpCounters internal constructor() {

    /** Datagrams whose type tag, length or header this build could not make sense of. */
    public var malformed: Long = 0L
        internal set

    /** Datagrams larger than the configured MTU. */
    public var oversized: Long = 0L
        internal set

    /** Payload datagrams whose salt matched no live connection, or came from the wrong address. */
    public var unknownConnection: Long = 0L
        internal set

    /** Payload datagrams whose sequence had already been seen, or was too old to vouch for. */
    public var replayed: Long = 0L
        internal set

    /** Handshake datagrams refused by the rate limiter. */
    public var rateLimited: Long = 0L
        internal set

    /** Replies withheld because they would have been larger than what arrived. */
    public var amplificationBlocked: Long = 0L
        internal set

    /** Connect responses carrying a token this key did not mint for that address. */
    public var tokenRejected: Long = 0L
        internal set

    /** Connect responses carrying a token whose lifetime had run out. */
    public var tokenExpired: Long = 0L
        internal set

    /** Handshakes that reached a live connection. */
    public var handshakesCompleted: Long = 0L
        internal set

    /** Handshakes refused outright, with a reason sent back. */
    public var handshakesDenied: Long = 0L
        internal set

    /** Part-built fragmented messages abandoned on their deadline. */
    public var fragmentsTimedOut: Long = 0L
        internal set

    /** Fragments refused for contradicting their own header. */
    public var fragmentsRefused: Long = 0L
        internal set

    /** Sends addressed to a peer with no live connection. */
    public var sendsToUnknownPeer: Long = 0L
        internal set

    /** Receives that failed at the socket, most often a Windows ICMP port-unreachable. */
    public var receiveErrors: Long = 0L
        internal set

    override fun toString(): String = buildString {
        append("UdpCounters(malformed=").append(malformed)
        append(", oversized=").append(oversized)
        append(", unknownConnection=").append(unknownConnection)
        append(", replayed=").append(replayed)
        append(", rateLimited=").append(rateLimited)
        append(", amplificationBlocked=").append(amplificationBlocked)
        append(", tokenRejected=").append(tokenRejected)
        append(", tokenExpired=").append(tokenExpired)
        append(", completed=").append(handshakesCompleted)
        append(", denied=").append(handshakesDenied)
        append(", fragmentsTimedOut=").append(fragmentsTimedOut)
        append(", fragmentsRefused=").append(fragmentsRefused)
        append(", sendsToUnknownPeer=").append(sendsToUnknownPeer)
        append(", receiveErrors=").append(receiveErrors).append(')')
    }
}
