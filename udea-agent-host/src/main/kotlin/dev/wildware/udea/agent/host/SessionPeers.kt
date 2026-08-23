package dev.wildware.udea.agent.host

import java.util.concurrent.CopyOnWriteArrayList

/**
 * One peer this instance launched: what it is, and where to reach it.
 *
 * `pid` so a stale row can be told from a live one without a request, and `port` because that is
 * the whole point - an agent that has this row does not have to guess which of the ports it can
 * see is the client it just started.
 */
public class SessionPeer(
    /** What the peer is. */
    public val role: InstanceRole,
    /** The loopback port it bound. Recorded only after it answered, so it is a port that exists. */
    public val port: Int,
    /** The peer's process id, or `0` when the launcher did not learn one. */
    public val pid: Long = 0L,
) {
    init {
        require(port in 1..MAX_PORT) { "a peer's port must be 1..$MAX_PORT, was $port" }
        require(pid >= 0L) { "a pid is never negative, was $pid" }
    }

    override fun toString(): String = "SessionPeer($role on $port, pid=$pid)"

    private companion object {
        const val MAX_PORT: Int = 65_535
    }
}

/**
 * The peers this instance launched, for the `agent_session` tool to report.
 *
 * ## What it is not
 *
 * It is **not** a directory of the session. It holds only what *this* process started, so a
 * client that was launched by hand appears in nobody's list, and that is correct: the registry in
 * `~/.game-bridge/instances` is the directory, and it is the bridge's job to group it by
 * `sessionId`. This exists for the one thing the registry cannot answer - "which of those did I
 * just start, and did it come up?" - which is what an agent needs immediately after
 * `net.start_client` and before the entry has appeared.
 *
 * ## The seam with `:udea-net`
 *
 * `net.start_host` and `net.start_client` own the spawning; this owns the record of it. A
 * launcher spawns a JVM with [SessionIdentity.jvmArguments], waits for that port to answer
 * `/health`, and then calls [record]. Recording *after* the port answers is the same rule
 * [AgentRegistry] follows and for the same reason: a row naming a port that was never claimed is
 * worse than no row, because a reader that trusts it reports a peer that does not exist.
 *
 * If the transport is not there yet, this class still works and still reports an empty list -
 * which is exactly what an instance that launched nothing should say.
 *
 * ## Bounded, and thread-safe
 *
 * Written from whichever thread a tool ran on and read from an HTTP thread, so the list is
 * copy-on-write - writes are a handful per session, reads are one per `agent_session` call.
 * [capacity] bounds it for the same reason `AgentSessions` bounds its intern table: a caller
 * decides how many times `start_client` is invoked, and an unbounded list fed by a caller is the
 * defect the command queue's cap already fixed. Past the cap a record is refused loudly rather
 * than dropped, because an agent that launched a peer and cannot see it has to know which.
 */
public class SessionPeers(
    /** How many peers one instance may launch. Eight is more clients than a test drives. */
    public val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(capacity > 0) { "SessionPeers holds at least one peer, was $capacity" }
    }

    private val recorded = CopyOnWriteArrayList<SessionPeer>()

    /** The peers launched so far, in launch order. A snapshot; safe to iterate. */
    public val peers: List<SessionPeer> get() = recorded.toList()

    /** How many peers have been recorded. */
    public val size: Int get() = recorded.size

    /**
     * Records a peer that has already bound [port] and answered.
     *
     * @throws IllegalStateException past [capacity]. Loud, not dropped: see the class KDoc.
     */
    public fun record(role: InstanceRole, port: Int, pid: Long = 0L): SessionPeer {
        val peer = SessionPeer(role, port, pid)
        check(recorded.size < capacity) {
            "this instance has already launched $capacity peers, which is the cap; a session " +
                "needing more is not the shape this was built for"
        }
        recorded += peer
        return peer
    }

    override fun toString(): String = "SessionPeers(${recorded.size}/$capacity)"

    public companion object {
        private const val DEFAULT_CAPACITY: Int = 8
    }
}
