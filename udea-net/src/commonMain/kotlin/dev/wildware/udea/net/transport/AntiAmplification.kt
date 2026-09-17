package dev.wildware.udea.net.transport

/**
 * The rule that stops this server being someone else's DDoS cannon (spec D10).
 *
 * ## The attack
 *
 * UDP has no handshake before the first byte of application data, so an attacker sends a small
 * datagram with a *forged* source address — the victim's — and the server's reply goes to the
 * victim. If the reply is bigger than the request, the attacker has bought amplification: one
 * byte spent, several bytes delivered to a victim, from a source the victim cannot block
 * because it is a legitimate game server. Open DNS and NTP resolvers were turned into
 * hundred-gigabit weapons on exactly this.
 *
 * ## The rule
 *
 * Never send an unverified peer more bytes than it just sent us. Verified means the peer has
 * returned a [ConnectionSecret] token minted for its own address, which is proof it can
 * *receive* there and therefore is not forging.
 *
 * ## Why this is stateless, and why that is enough
 *
 * The obvious implementation keeps a per-address ledger of bytes in and bytes out. That
 * ledger is itself state an unverified peer can force the server to allocate — the very thing
 * the challenge token exists to avoid — and it has to be capped and evicted, at which point
 * the cap becomes the hole.
 *
 * So the check is per datagram: a reply is permitted only if it is no larger than the datagram
 * that prompted it. The cumulative property follows without any bookkeeping, because
 * [UdpTransport] sends an unverified peer at most one reply per datagram received from it: if
 * every reply is no larger than its own prompt, the running total sent can never exceed the
 * running total received. That is a proof about the code's shape rather than a number that has
 * to be tracked, and it cannot be exhausted.
 *
 * The cost is that both client-to-server handshake datagrams are padded to the MTU. Two
 * padded datagrams per connect, once, is the entire price of not being an amplifier.
 */
internal class AmplificationGuard {

    /** Replies withheld by [permits]. */
    var blocked: Long = 0L
        private set

    /**
     * Whether a [replyBytes]-byte reply may go to an unverified peer that just sent
     * [receivedBytes].
     *
     * @return false when the reply would amplify. The caller drops the reply; it must not
     *   shrink it and try again, because a reply the peer cannot act on is worse than silence.
     */
    fun permits(replyBytes: Int, receivedBytes: Int): Boolean {
        if (replyBytes <= receivedBytes) return true
        blocked++
        return false
    }
}
