package dev.wildware.udea.agent.activity

import dev.wildware.udea.agent.AgentClock

/**
 * The agent's own one-line description of what it is doing, for the human watching the window
 * (spec 3.7, issue #158).
 *
 * ## Why the agent cannot read this back
 *
 * `agent_say` writes and never reads. There is no `agent_read_narration` tool, and nothing here
 * is rendered into the `/state` digest - the same exclusion, and the same reason, as
 * [AgentActivityRing]. An agent doing capture / act / capture / diff that could read its own
 * caption would see it change between two frames and conclude the *game* changed; an agent that
 * could read its own narration back would also pay tokens to be told something it just said.
 *
 * The write half still answers - `{"ok":true}` with the expiry - because a tool that succeeded
 * silently is indistinguishable from one that was never registered.
 *
 * ## Wall clock, and why that is safe here
 *
 * Expiry is in wall seconds through [AgentClock], never `SimClock`. Overlay state is
 * presentation state: it must not be snapshotted, must not move when the simulation is paused
 * (a human reading a caption on a paused game still wants it to fade), and must not survive a
 * rewind by being restored. Reading a wall clock is normally forbidden in this tree, and the
 * reconciliation is [AgentClock]'s: nothing a system reads depends on this value, so no
 * snapshot, hash or replay changes because of it.
 *
 * ## Newest replaces, and over-long is refused
 *
 * **Replaces.** A queue would mean the caption a human is reading is one the agent set four
 * calls ago, and an agent narrating each step would build a backlog that outlives the work it
 * describes. There is one current line, and setting it discards the previous one.
 *
 * **Refused, not truncated.** [MAX_LENGTH] characters is a hard limit and a longer line is a
 * `bad_argument` naming the length. Truncating would silently change what the agent said, and
 * the agent - which cannot read the caption back - would have no way to discover that the human
 * is looking at half a sentence ending mid-word.
 */
public class AgentNarration(
    private val clock: AgentClock = AgentClock.System,
) {

    private val lock = Any()

    private var text: String = ""
    private var session: Int = AgentSessionId.LOCAL.raw
    private var expiresAtNanos: Long = Long.MIN_VALUE
    private var revision: Long = 0L

    /**
     * Increments on every [say] and on every expiry observed by [isLive].
     *
     * The overlay's re-format gate, exactly as [AgentActivityRing.version] is: a panel that
     * re-rendered a `String` every frame to notice a caption had not changed would be doing the
     * per-frame work the pre-format exists to remove.
     */
    public val version: Long get() = synchronized(lock) { expireIfDue(); revision }

    /**
     * The current line, or `""` when there is none or it has expired.
     *
     * Allocation-free: it hands back the interned `String` the tool call already built.
     */
    public val current: String get() = synchronized(lock) { if (expireIfDue()) "" else text }

    /** Who said it. Meaningless when [current] is empty. */
    public val currentSession: AgentSessionId
        get() = synchronized(lock) { AgentSessionId(session) }

    /** Whether a line is live right now. */
    public fun isLive(): Boolean = synchronized(lock) { !expireIfDue() }

    /**
     * Wall-clock seconds until the current line expires, or `0` when nothing is live.
     *
     * For a fade: an overlay that dimmed a caption over its last second needs to know how much
     * of it is left, and computing that from an absolute nanosecond deadline at the call site
     * would put a second wall-clock reading in a second place.
     */
    public fun remainingSeconds(): Float = synchronized(lock) {
        if (expireIfDue()) return 0f
        val remaining = expiresAtNanos - clock.nowNanos()
        if (remaining <= 0L) 0f else remaining.toFloat() / NANOS_PER_SECOND
    }

    /**
     * Sets the line, replacing whatever was there.
     *
     * @param ttlSeconds how long it stays up, in wall seconds. Clamped to
     *   `(0, MAX_TTL_SECONDS]` by [SayRefusal.TTL_OUT_OF_RANGE] rather than silently: a caller
     *   asking for an hour has misunderstood what this is for, and one asking for zero has
     *   written a caption nobody will ever see.
     * @return `null` on success, or why it was refused.
     */
    public fun say(text: String, ttlSeconds: Float, session: AgentSessionId): SayRefusal? {
        if (text.isBlank()) return SayRefusal.EMPTY
        if (text.length > MAX_LENGTH) return SayRefusal.TOO_LONG
        if (!ttlSeconds.isFinite() || ttlSeconds <= 0f || ttlSeconds > MAX_TTL_SECONDS) {
            return SayRefusal.TTL_OUT_OF_RANGE
        }
        val now = clock.nowNanos()
        synchronized(lock) {
            this.text = text
            this.session = session.raw
            this.expiresAtNanos = now + (ttlSeconds.toDouble() * NANOS_PER_SECOND).toLong()
            revision++
        }
        return null
    }

    /** Clears the line immediately, as if it had expired. */
    public fun clear() {
        synchronized(lock) {
            if (text.isEmpty()) return
            text = ""
            expiresAtNanos = Long.MIN_VALUE
            revision++
        }
    }

    override fun toString(): String =
        "AgentNarration(${if (isLive()) "\"$current\"" else "idle"}, rev $version)"

    /**
     * Drops the line if its deadline has passed, and returns whether there is now nothing.
     *
     * Expiry is lazy - checked on read rather than driven by a timer - because there is no
     * thread here to drive one, and because a caption nobody is looking at does not need to
     * stop existing on time. The `revision` bump on expiry is what makes a fade-out reach the
     * overlay's re-format gate. Caller holds [lock].
     */
    private fun expireIfDue(): Boolean {
        if (text.isEmpty()) return true
        if (clock.nowNanos() < expiresAtNanos) return false
        text = ""
        expiresAtNanos = Long.MIN_VALUE
        revision++
        return true
    }

    public companion object {

        /**
         * 160 characters.
         *
         * Two comfortable lines in a corner panel at a readable size. `agent_say` is a caption,
         * not a log: an agent with more to record has [dev.wildware.udea.agent.AgentEventRing]
         * through `events`, and the activity ring records its calls without being asked.
         */
        public const val MAX_LENGTH: Int = 160

        /**
         * Five minutes.
         *
         * Longer than any single step of an agent's work and short enough that a caption left
         * behind by a crashed session clears itself rather than mislabelling the window for the
         * rest of the day.
         */
        public const val MAX_TTL_SECONDS: Float = 300f

        private const val NANOS_PER_SECOND: Long = 1_000_000_000L
    }
}

/**
 * Why an `agent_say` was refused.
 *
 * An enum rather than a message, so the tool renders one `bad_argument` per case with the limit
 * in it and the caller can branch without parsing prose.
 */
public enum class SayRefusal(
    /** One line, naming the limit. */
    public val message: String,
) {
    /** Nothing to show. */
    EMPTY("agent_say needs a non-blank line; an empty caption would clear the panel silently"),

    /**
     * Over [AgentNarration.MAX_LENGTH].
     *
     * Refused and never truncated - see [AgentNarration] for why a caption the agent cannot read
     * back must not be silently rewritten.
     */
    TOO_LONG(
        "agent_say takes at most ${AgentNarration.MAX_LENGTH} characters and this is longer. " +
            "It is refused rather than truncated: you cannot read the caption back, so a " +
            "silently shortened one would leave a human looking at half a sentence.",
    ),

    /** Outside `(0, MAX_TTL_SECONDS]`. */
    TTL_OUT_OF_RANGE(
        "agent_say needs ttlSeconds in (0, ${AgentNarration.MAX_TTL_SECONDS}]: zero is a caption " +
            "nobody sees, and longer than that outlives the work it describes",
    ),
}
