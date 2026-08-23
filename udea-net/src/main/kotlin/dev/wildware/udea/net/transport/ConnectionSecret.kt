package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import java.net.InetSocketAddress
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The server-side key that mints and verifies connect challenge tokens (spec D10).
 *
 * ## Why a keyed MAC and not a table of issued tokens
 *
 * The point of the challenge step is to prove a peer can *receive* at the address it claims to
 * send from, before the server spends a byte of memory on it. A server that remembered every
 * token it issued would be trivially exhausted by a spoofed-source flood: the flood costs the
 * attacker one datagram per entry and costs the server a table row that must be held for the
 * token's whole lifetime.
 *
 * So nothing is remembered. The token *is* `HMAC(key, address ‖ clientSalt ‖ expiry)`, and
 * verifying it is recomputing it. The server holds exactly one key and zero per-peer state
 * until a token comes back from the address it was minted for.
 *
 * ## Why the address is inside the MAC
 *
 * It is what makes a captured token useless anywhere else. A token replayed from a different
 * source address recomputes to a different MAC and is refused, which is the difference between
 * "an attacker who can sniff one handshake can open connections forever" and "an attacker who
 * can sniff one handshake has a token that only works from the victim's own address until it
 * expires". The expiry closes the remaining window.
 *
 * ## Where the key comes from
 *
 * The caller supplies it, and this class deliberately offers no `random()` factory. Two
 * reasons, and neither is squeamishness: a generator here would be the one unseeded random
 * source in a package whose entire value is that a failure reproduces from its seed
 * (`NoWallClockInTransportTest`), and a real deployment wants the key to come from its own
 * secret store so that a restarted server does not invalidate every token in flight. A single
 * process that genuinely wants a throwaway key can pass one it generated itself.
 */
public class ConnectionSecret(key: ByteArray) {

    init {
        require(key.size >= MIN_KEY_BYTES) {
            "a connection secret needs at least $MIN_KEY_BYTES bytes of key, was ${key.size}"
        }
    }

    private val mac: Mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }

    /** Reused across every mint and verify: this path runs under a rate limiter, on one thread. */
    private val message = ByteArray(MESSAGE_BYTES)

    /** Reused MAC output. `Mac.doFinal(byte[], int)` writes into it rather than allocating. */
    private val digest = ByteArray(DIGEST_BYTES)

    /**
     * The token for [address] at [expiry], as the low 64 bits of an HMAC-SHA-256.
     *
     * Truncated to 64 bits because the whole token rides two datagrams that are already
     * padded to the MTU, and 64 bits is 2^-64 per forgery attempt against a peer that the
     * rate limiter caps at a few attempts per second. A full 256-bit tag would buy no
     * reachable security and cost 24 bytes of every handshake.
     */
    internal fun token(address: InetSocketAddress, clientSalt: Long, expiry: Tick): Long {
        val length = encode(address, clientSalt, expiry)
        mac.reset()
        mac.update(message, 0, length)
        mac.doFinal(digest, 0)
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) value = (value shl 8) or (digest[index].toLong() and 0xFF)
        return value
    }

    /**
     * Whether [presented] is the token this key would have minted.
     *
     * The comparison is a single `Long` equality rather than a loop over bytes, so there is no
     * data-dependent early exit for a timing attack to walk a token out of, byte by byte.
     */
    internal fun verifies(
        address: InetSocketAddress,
        clientSalt: Long,
        expiry: Tick,
        presented: Long,
    ): Boolean = (token(address, clientSalt, expiry) xor presented) == 0L

    /**
     * Packs the MAC input, returning its length.
     *
     * Every variable-length part is preceded by its length. Without that, a 4-byte IPv4
     * address followed by port `0x0102` and a 6-byte host string could produce the same byte
     * run as a different address entirely, and two distinct peers would share a token.
     */
    private fun encode(address: InetSocketAddress, clientSalt: Long, expiry: Tick): Int {
        var cursor = 0
        val host = if (address.isUnresolved) {
            address.hostString.toByteArray(Charsets.UTF_8)
        } else {
            address.address.address
        }
        val hostBytes = minOf(host.size, MAX_HOST_BYTES)
        message[cursor++] = hostBytes.toByte()
        System.arraycopy(host, 0, message, cursor, hostBytes)
        cursor += hostBytes
        cursor = putLong(cursor, address.port.toLong())
        cursor = putLong(cursor, clientSalt)
        cursor = putLong(cursor, expiry.value)
        return cursor
    }

    private fun putLong(offset: Int, value: Long): Int {
        for (index in 0 until Long.SIZE_BYTES) {
            message[offset + index] = (value ushr ((Long.SIZE_BYTES - 1 - index) * 8)).toByte()
        }
        return offset + Long.SIZE_BYTES
    }

    public companion object {

        /**
         * 32 bytes, the block size of the hash underneath, because a key shorter than the
         * digest is the one length HMAC gains nothing from.
         */
        public const val MIN_KEY_BYTES: Int = 32

        private const val ALGORITHM: String = "HmacSHA256"

        private const val DIGEST_BYTES: Int = 32

        /** Longest host part the MAC input reserves room for: an IPv6 address is 16 bytes. */
        private const val MAX_HOST_BYTES: Int = 16

        /** One length byte, the host, then port, client salt and expiry as 64-bit values. */
        private const val MESSAGE_BYTES: Int = 1 + MAX_HOST_BYTES + 3 * Long.SIZE_BYTES
    }
}
