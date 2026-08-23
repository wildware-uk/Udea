package dev.wildware.udea.agent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The lock-free hand-off between whatever is talking to the agent and the simulation thread.
 *
 * ## The shape, and why it is a port rather than an invention
 *
 * `FruitGameKTX`'s `DebugBridge` has this shape and it has survived a lot of automated
 * sessions, because the invariant is structural rather than a rule anyone has to remember:
 * **the off-thread side only ever calls [snapshot] and [submit].** It cannot reach Fleks,
 * Box2D or scene2d, because they are not here. The simulation thread is the only writer of
 * world state and the only consumer of commands, so no lock is needed and none is taken.
 *
 * Three things are carried forward from that implementation unchanged: the published document
 * is an immutable `String` in an [AtomicReference], swapped once per build; the command queue
 * is a [ConcurrentLinkedQueue] drained by the simulation thread; and [completedCommandId] is
 * the strong confirmation the bridge waits on (`game-bridge-mcp`, `GET /command`).
 *
 * Two are changed. The queue is **capped** rather than unbounded, so a client in a retry loop
 * against a stalled game cannot exhaust the heap. And a command answer is a typed
 * [AgentResult] in [commandResults] rather than a formatted string in the event ring - see
 * [AgentResult] for the defect that fixes.
 *
 * ## An instance, not an object
 *
 * `DebugBridge` was a global mutable `object`, so two worlds in one process shared one queue.
 * A headless world beside a rendered one, or a server and a client in one JVM, are both things
 * this engine has to do, so the bridge is constructed and injected like everything else.
 */
public class AgentBridge(
    /** How many commands may be queued before [submit] starts refusing. */
    public val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    /** Recent game events, bounded and non-destructive to read. */
    public val events: AgentEventRing = AgentEventRing(),
    /** How many command answers are kept for the digest to render. */
    resultCapacity: Int = DEFAULT_RESULT_CAPACITY,
) {
    init {
        require(queueCapacity > 0) { "queueCapacity must be positive, was $queueCapacity" }
    }

    private val latest = AtomicReference(NOT_READY)

    private val commands = ConcurrentLinkedQueue<AgentCommand>()

    /**
     * [commands] is a `ConcurrentLinkedQueue`, whose `size` is an O(n) walk, so the cap is
     * enforced against a counter rather than by counting the queue on every submission.
     */
    private val queued = AtomicInteger(0)

    private val completed = AtomicLong(0)

    private val results = CommandResultRing(resultCapacity)

    /**
     * Whether anything has read [snapshot] since the last [publish].
     *
     * The digest's cadence gate (`StateDigest`): a document nobody has read is a document
     * there is no point rebuilding, and rebuilding it anyway is the difference between the
     * agent surface costing 0.3ms per read and 0.3ms per tick forever. It starts `true` so the
     * first build happens without waiting for a reader - `{"ready":false}` is not an answer.
     */
    private val readSincePublish = AtomicBoolean(true)

    /**
     * Host loop iterations. **Increases for the life of the process.**
     *
     * The bridge reads a *decreasing* frame as "a different process is answering on this port"
     * and drops its cached tool manifest, which is what makes rebuild-and-run invisible to an
     * agent. That is only sound if the counter never goes backwards for an honest reason, so
     * the only mutator is [advanceFrame] and it only increments. In `RenderMode.Headless`
     * there is no render frame and the simulation loop drives it instead; the counter means
     * "the host went round again", not "a picture was drawn".
     */
    public val frame: Long get() = frames.get()

    private val frames = AtomicLong(0)

    /**
     * The simulation tick the published document describes.
     *
     * Distinct from [frame] and **not** monotonic: a rewind moves it backwards, which is the
     * whole point of a rewind. That is exactly why a harness must confirm a command against
     * [completedCommandId] and not against this. `DebugBridge.kt:29-40` records the cost of
     * confusing the two - a harness waiting on render frames made `step(n)` approximate, and
     * every determinism measurement silently compared runs of different lengths.
     */
    public val tick: Long get() = ticks.get()

    private val ticks = AtomicLong(0)

    /**
     * How many commands are queued and not yet drained. Never above [queueCapacity].
     *
     * [submit] reserves a slot before it checks, so between the increment and the compensating
     * decrement on an overflow the raw counter genuinely sits one above the cap - and a reader
     * sampling it in that window would see a depth that is not a state the queue can be in. The
     * clamp is not cosmetic: this number is what a client backs off on, and a health surface
     * reporting `257/256` is reporting a bug it does not have.
     */
    public val pendingCommands: Int get() = queued.get().coerceAtMost(queueCapacity)

    // --- the off-thread half -------------------------------------------------------------

    /** The most recently published document. Never `null`; `{"ready":false}` until the first build. */
    public fun snapshot(): String {
        readSincePublish.set(true)
        return latest.get()
    }

    /**
     * Queues [command] for the simulation thread.
     *
     * Returns a value rather than throwing, and rejects rather than growing: a client that has
     * lost contact with a stalled game retries, and an unbounded queue turns one wedged
     * simulation into an `OutOfMemoryError` that destroys the evidence. A rejection tells the
     * caller to back off, and the answer is the same shape as any other failed command.
     */
    public fun submit(command: AgentCommand): AgentSubmission {
        // Reserve first, add second: the reverse order would let two submitters both observe
        // room for the last slot and both enqueue.
        val depth = queued.incrementAndGet()
        if (depth > queueCapacity) {
            queued.decrementAndGet()
            val error = AgentError(
                AgentErrorKind.QUEUE_FULL,
                "the agent command queue holds its maximum of $queueCapacity commands; " +
                    "the simulation has not drained them - is it stalled?",
            )
            return AgentSubmission.Rejected(command.id, error)
        }
        commands.add(command)
        return AgentSubmission.Accepted(command.id)
    }

    /** The highest command id that has been completed. The strong confirmation. */
    public fun completedCommandId(): Long = completed.get()

    /** The most recent command answers, oldest first. Allocating; for tools and tests. */
    public fun commandResults(): List<CommandResult> = results.toList()

    /**
     * Renders the recent command answers into [json] as a named array member.
     *
     * @param ceiling the document length this section may not push past. A tool answer is a
     *   document the *tool* rendered, and [AgentResult] puts no bound on its size at all - so a
     *   game whose tool replies with a thousand-entity list would otherwise carry that list into
     *   every `/state` poll for as long as the answer stayed in the ring.
     * @return whether an answer was dropped for want of room.
     */
    public fun renderCommandResults(json: Json, name: String, limit: Int, ceiling: Int): Boolean =
        results.renderInto(json, name, limit, ceiling)

    // --- the simulation-thread half ------------------------------------------------------

    /**
     * Moves every queued command into [into] and returns how many.
     *
     * Takes the destination rather than returning a list so a caller that drains every tick
     * can reuse one buffer; the reference implementation allocated an `ArrayList` per frame.
     * Draining is bounded by the queue depth at entry, not by what arrives during the drain.
     */
    public fun drain(into: MutableList<AgentCommand>): Int {
        var drained = 0
        var remaining = queued.get()
        while (remaining > 0) {
            val command = commands.poll() ?: break
            queued.decrementAndGet()
            into.add(command)
            drained++
            remaining--
        }
        return drained
    }

    /**
     * Records [result] for [id] and advances [completedCommandId].
     *
     * The advance happens for a failure exactly as for a success. That is the guarantee the
     * whole surface rests on: a caller polling for its answer is released by the command
     * *finishing*, not by it succeeding, so a tool that threw costs one round trip rather than
     * a timeout - and a bridge that times out reports a healthy game as frozen.
     */
    public fun complete(id: Long, result: AgentResult) {
        results.record(id, result)
        // A high-water mark, not a store: commands may complete out of order if a tool ever
        // defers, and a plain set would let an older id retract a newer confirmation.
        while (true) {
            val previous = completed.get()
            if (id <= previous || completed.compareAndSet(previous, id)) return
        }
    }

    /** Publishes [json] as the current state document. */
    public fun publish(json: String) {
        latest.set(json)
        readSincePublish.set(false)
    }

    /**
     * Whether anything has called [snapshot] since the last [publish].
     *
     * Read by `StateDigest` to decide whether a rebuild is worth doing at all.
     */
    public fun readSinceLastPublish(): Boolean = readSincePublish.get()

    /** Advances [frame] by one and returns the new value. */
    public fun advanceFrame(): Long = frames.incrementAndGet()

    /** Records the simulation tick the next published document will describe. */
    public fun publishTick(tick: Long) {
        ticks.set(tick)
    }

    /**
     * Appends a game event, stamped with [tick]. Shorthand for `events.record`.
     *
     * The tick is passed rather than read from [tick] because the two differ by one exactly
     * where it matters: `publishTick` runs at the end of a host iteration, so during the
     * barrier drain at the top of tick N this bridge still reports N-1. An audit line that
     * dated a mutation to the tick before the one it happened on would be worse than an
     * undated one, because it looks right.
     */
    @JvmOverloads
    public fun event(message: String, tick: Long = AgentEventRing.UNSTAMPED) {
        events.record(message, tick)
    }

    override fun toString(): String =
        "AgentBridge(frame=$frame, tick=$tick, pending=$pendingCommands, completed=${completed.get()})"

    public companion object {
        /** What [snapshot] answers before the first digest is built. */
        public const val NOT_READY: String = "{\"ready\":false}"

        /**
         * 256 queued commands.
         *
         * Far more than a coherent agent has in flight - it waits for `completedCommandId`
         * between calls - and small enough that a runaway client is refused in milliseconds
         * rather than filling a heap.
         */
        public const val DEFAULT_QUEUE_CAPACITY: Int = 256

        /**
         * 32 kept answers. The digest renders a handful; the rest are there so a caller that
         * fell behind by a few commands can still find its own.
         */
        public const val DEFAULT_RESULT_CAPACITY: Int = 32
    }
}

/**
 * The answer to [AgentBridge.submit], before the command has run.
 *
 * Sealed because the two cases need different handling and a boolean would let a caller ignore
 * the difference: an accepted command has an id to wait on, a rejected one never existed.
 */
public sealed interface AgentSubmission {

    /** The id the caller polls [AgentBridge.completedCommandId] for. */
    public val commandId: Long

    /** Queued. It will be applied at the top of a coming tick. */
    public class Accepted(override val commandId: Long) : AgentSubmission

    /** Refused, and never queued. [error] says why; today the only reason is a full queue. */
    public class Rejected(
        override val commandId: Long,
        public val error: AgentError,
    ) : AgentSubmission
}

/** One completed command: its id and what it produced. */
public class CommandResult(
    /** The [AgentCommand.id] this answers. */
    public val id: Long,
    /** What the tool produced. */
    public val result: AgentResult,
) {
    override fun toString(): String = "CommandResult($id, $result)"
}

/**
 * The bounded ring of command answers, and the only thing that knows how they render.
 *
 * Internal because the wire shape - `{"id":18,"ok":true,"result":{...}}` /
 * `{"id":19,"ok":false,"error":{"kind":"no_such_entity","message":"..."}}` - is the bridge's
 * contract with the agent, and one renderer is what stops the digest and a tool disagreeing
 * about it.
 */
internal class CommandResultRing(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val ids = LongArray(capacity)
    private val values = arrayOfNulls<AgentResult>(capacity)
    private var writeIndex = 0
    private var held = 0

    fun record(id: Long, result: AgentResult) {
        synchronized(this) {
            ids[writeIndex] = id
            values[writeIndex] = result
            writeIndex++
            if (writeIndex == capacity) writeIndex = 0
            if (held < capacity) held++
        }
    }

    fun toList(): List<CommandResult> = synchronized(this) {
        val out = ArrayList<CommandResult>(held)
        walk(held) { id, result -> out.add(CommandResult(id, result)) }
        out
    }

    /**
     * Renders the newest [limit] answers, oldest of those first, within [ceiling] characters.
     *
     * Allocation-free. Returns whether anything was dropped for want of room.
     *
     * ## The budget is spent newest-first, even though the document is written oldest-first
     *
     * Two passes, and the first one is the whole point. Writing straight through in document
     * order spends the budget on the **oldest** answers and drops the newest, which inverts what
     * a caller needs: the `game-bridge-mcp` confirmation loop polls `/state` until
     * `completedCommandId` covers the id it submitted and then reads that id out of
     * `commandResults`. One verbose earlier answer - a `field_not_writable` refusal carries a
     * paragraph of prose - is enough to starve the newest entry out, and the caller then reads a
     * document that confirms its command finished and does not say what it answered.
     *
     * That was not hypothetical: it is what a live `/state` did on the Phase 1 demo, confirming
     * `completedCommandId: 11` beside a `commandResults` holding 8, 9 and 10.
     *
     * So [fitting] counts backwards from the newest and returns how many fit; the render then
     * walks that many oldest-first. Dropping is still all-or-nothing per entry and the entries
     * that go are the oldest, which is the direction a reader can survive - an answer already
     * read once is worth less than the one being waited on.
     */
    fun renderInto(json: Json, name: String, limit: Int, ceiling: Int): Boolean {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        synchronized(this) {
            val considered = if (limit < held) limit else held
            // The array's own two characters are already owed before the first entry is costed.
            val fits = fitting(considered, ceiling - json.length - ARRAY_OVERHEAD)
            json.key(name)
            json.beginArray()
            walk(fits) { id, result ->
                json.beginObject()
                json.put("id", id)
                when (result) {
                    is AgentResult.Ok -> {
                        json.put("ok", true)
                        json.key("result")
                        json.raw(result.json)
                    }

                    is AgentResult.Failed -> {
                        json.put("ok", false)
                        json.obj("error") {
                            put("kind", result.error.kind.id)
                            put("message", result.error.message)
                        }
                    }
                }
                json.endObject()
            }
            json.endArray()
            return fits < considered
        }
    }

    /**
     * How many of the newest [considered] entries fit in [budget] characters.
     *
     * Counts backwards from the newest, so the answer is always a *suffix* of the ring: the
     * entries admitted are contiguous and end at the newest one. Stops at the first entry that
     * does not fit rather than skipping it and trying an older, smaller one - admitting an entry
     * past a refused one would leave a hole in the middle of the sequence, and a reader that saw
     * ids 8 and 11 with no 9 or 10 could not tell a dropped answer from one that never ran.
     *
     * Caller holds the monitor.
     */
    private fun fitting(considered: Int, budget: Int): Int {
        var spent = 0
        var admitted = 0
        var cursor = writeIndex
        while (admitted < considered) {
            cursor--
            if (cursor < 0) cursor += capacity
            val result = values[cursor] ?: break
            val cost = when (result) {
                is AgentResult.Ok -> result.json.length + ENTRY_OVERHEAD
                is AgentResult.Failed -> result.error.message.length * 2 +
                    result.error.kind.id.length + ENTRY_OVERHEAD
            }
            if (spent + cost > budget) break
            spent += cost
            admitted++
        }
        return admitted
    }

    private companion object {
        /** The fixed part of one rendered answer, plus slack. */
        const val ENTRY_OVERHEAD: Int = 40

        /** The `[]` and the key's own punctuation, owed before any entry is costed. */
        const val ARRAY_OVERHEAD: Int = 4
    }

    /** Oldest-first walk over the newest [limit] entries. Caller holds the monitor. */
    private inline fun walk(limit: Int, visit: (Long, AgentResult) -> Unit) {
        val visiting = if (limit < held) limit else held
        var cursor = writeIndex - visiting
        if (cursor < 0) cursor += capacity
        var visited = 0
        while (visited < visiting) {
            val result = values[cursor]
            if (result != null) visit(ids[cursor], result)
            cursor++
            if (cursor == capacity) cursor = 0
            visited++
        }
    }
}
