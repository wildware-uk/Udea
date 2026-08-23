package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentEventVisitor
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.dispatch.DigestPublisher

/**
 * The Tier-0 state document: a cheap, capped, always-on summary of the whole simulation.
 *
 * ## What is deliberately not here
 *
 * **No entity list, at any world size.** Not a truncated one, not a paged one, not one behind a
 * flag. See [DigestBudgets] for the token arithmetic; the short version is that inlining
 * entities costs an agent about twenty thousand tokens per poll and it polls in a loop. Entity
 * detail is Tier 1 and Tier 2 - `query_entities` and `describe_entity` - where the agent says
 * what it wants and pays for that.
 *
 * That is also why [DigestSources] hands this class an [EntityCensus] rather than a `World`:
 * with no world to walk, "just inline the entities" is not an edit somebody can make here.
 *
 * ## The gates on rebuilding
 *
 * Nothing is rebuilt unless something has read the document since the last one was published.
 * The read flag lives on [AgentBridge] so that the HTTP handler and an in-process harness set
 * it the same way. A game nobody is watching therefore builds one digest and then stops, and
 * the agent surface costs a shipped game nothing.
 *
 * Given a reader, a rebuild happens when any of three things is true: at least
 * [DigestBudgets.REBUILD_INTERVAL_TICKS] ticks have passed; the tick went *backwards*, which
 * is a rewind and means the document describes a world that has been replaced; or a command
 * completed since the last document, which is [lastPublishedCommandId].
 *
 * The third is not a refinement. Two of the six steps in the Phase 1 workflow happen with the
 * simulation paused, and a paused simulation's tick does not move - so on the interval alone
 * the digest would never be due again and every command issued to a paused game would report
 * as never completing.
 *
 * ## Cost
 *
 * One reused [Json] buffer, hoisted visitors, no per-entity work of any kind, and the build
 * records its own cost into [AgentTimings] under [DigestBudgets.TIMING_NAME] - which is how
 * the `diag` toolset can show an agent what the agent surface itself costs. The recorded
 * number lands in the *next* document, since this one is already written by the time it exists.
 */
public class StateDigest(
    private val bridge: AgentBridge,
    private val sources: DigestSources = DigestSources(),
    /** Where the build reports its own cost. Shared with the rest of the engine's timings. */
    public val timings: AgentTimings = AgentTimings(),
    private val clock: AgentClock = AgentClock.System,
    /** Ticks between rebuilds. See [DigestBudgets.REBUILD_INTERVAL_TICKS]. */
    private val rebuildIntervalTicks: Int = DigestBudgets.REBUILD_INTERVAL_TICKS,
) : DigestPublisher {

    init {
        require(rebuildIntervalTicks >= 1) {
            "rebuildIntervalTicks must be at least 1, was $rebuildIntervalTicks"
        }
    }

    private val json = Json()

    private val timingSlot = timings.slot(DigestBudgets.TIMING_NAME)

    /**
     * Hoisted so a build allocates nothing.
     *
     * A lambda written inline at the call site captures `this` and is a fresh object on every
     * build - four of them per build, sixty times a second, on the simulation thread.
     */
    private val eventSink = EventSink(json)

    private val archetypeVisitor = ArchetypeVisitor { archetype, count ->
        // Zero-count archetypes are omitted: they are the majority in a real game and each one
        // costs the agent tokens to read past.
        if (count > 0) json.put(archetype, count)
    }

    private val labelSink = LabelSink(json)

    private val scalarSink = ScalarSink(json)

    /** Documents built since construction. */
    public var builds: Long = 0L
        private set

    /** The tick the last build described. */
    public var lastBuildTick: Long = 0L
        private set

    /** Characters in the last document. Asserted against [DigestBudgets.MAX_BYTES]. */
    public var lastLength: Int = 0
        private set

    /** Nanoseconds the last build took. Also recorded into [timings]. */
    public var lastBuildNanos: Long = 0L
        private set

    /**
     * The `completedCommandId` the last published document carried.
     *
     * The third due-condition, and the one without which the headline workflow does not work.
     * The tick interval assumes time is passing; a *paused* game is the spec's own Phase 1
     * demo (pause, spawn, step, screenshot, rewind, inspect) and its tick does not move. With
     * only the interval gate, a command issued to a paused game runs, completes, advances
     * `AgentBridge.completedCommandId`, and never reaches a document - so the caller polls
     * `/state` until it times out and a healthy game is reported as frozen. Both confirmation
     * paths fail together, because the frames-advanced fallback reads `frame` out of the same
     * frozen document.
     */
    public var lastPublishedCommandId: Long = 0L
        private set

    /** Whether a build is currently due. See the class KDoc for the gates. */
    public fun isDue(): Boolean {
        if (builds == 0L) return true
        if (!bridge.readSinceLastPublish()) return false
        // A completed command is by definition news, whatever the tick is doing. Bounded by
        // the command rate, which the queue cap already bounds, and not by the tick rate.
        if (bridge.completedCommandId() != lastPublishedCommandId) return true
        val tick = bridge.tick
        // A rewind moves the tick backwards; the document it described no longer exists.
        return tick < lastBuildTick || tick - lastBuildTick >= rebuildIntervalTicks
    }

    override fun publishIfDue() {
        if (isDue()) publish()
    }

    /** Builds and publishes unconditionally. [publishIfDue] is the one the loop calls. */
    public fun publish() {
        val startedAt = clock.nowNanos()
        lastLength = renderInto()
        val document = json.toString()
        lastBuildNanos = clock.nowNanos() - startedAt
        timings.record(timingSlot, lastBuildNanos)
        bridge.publish(document)
        builds++
        lastBuildTick = bridge.tick
        // The id the document *carries*, read during the render rather than now: a second read
        // here could pick up a command completed in between and mark it published when it is
        // not in any document.
        lastPublishedCommandId = renderedCommandId
    }

    /** The `completedCommandId` written by the most recent [renderInto]. */
    private var renderedCommandId: Long = 0L

    /**
     * Renders the document into the reusable buffer and returns its length.
     *
     * Split out from [publish] so the allocation gate can measure the rendering on its own: the
     * `String` [publish] produces is a real and necessary allocation, and a test that could not
     * separate the two could not tell it apart from a regression.
     */
    internal fun renderInto(): Int {
        json.reset()
        json.beginObject()

        // `ready` first: it is the field the bridge looks at to tell a live game from the
        // `{"ready":false}` husk a crashed one leaves behind.
        json.put("ready", true)
        json.put("frame", bridge.frame)
        // Both names, and neither is redundant. `simFrame` is the key `game-bridge-mcp`'s
        // summarise() copies - it has no branch for `tick`, so a document carrying only `tick`
        // drops the simulation tick out of every command result the agent sees and forces a
        // full `/state` on every step, which is exactly the token cost the tiering exists to
        // avoid. `tick` stays because it is this engine's name for it everywhere else.
        json.put("simFrame", bridge.tick)
        json.put("tick", bridge.tick)
        json.put("paused", sources.loop.paused)
        json.put("timeScale", sources.loop.timeScale)
        json.put("fps", sources.loop.fps)
        renderedCommandId = bridge.completedCommandId()
        json.put("completedCommandId", renderedCommandId)
        json.put("entityCount", sources.entities.entityCount)

        json.obj("counts") { sources.entities.forEachArchetype(archetypeVisitor) }

        json.obj("net") {
            put("role", sources.net.role.name)
            put("clients", sources.net.clients)
            put("inKbps", sources.net.inKbps)
            put("outKbps", sources.net.outKbps)
        }

        json.obj("ui") {
            put("screen", sources.ui.screen)
            labelSink.renderInto(sources.ui)
        }

        val resultsTruncated = bridge.renderCommandResults(
            json,
            "commandResults",
            DigestBudgets.RESULT_LIMIT,
            DigestBudgets.RESULT_CEILING,
        )
        if (resultsTruncated) json.put("commandResultsTruncated", true)

        json.arr("events") { bridge.events.forEachRecent(DigestBudgets.EVENT_LIMIT, eventSink) }
        if (eventSink.truncated) json.put("eventsTruncated", true)

        json.obj("game") { scalarSink.renderInto(sources.game) }
        // Outside the `game` object, and that placement is the whole point: a game whose own
        // `@AgentState` declares a scalar called `truncated` would otherwise produce
        // `{"truncated":false,...,"truncated":true}` - two keys in one object, which is the
        // malformed document `AgentStateIndex.build()` refuses across modules, reintroduced by
        // this writer. `JSON.parse` keeps the last one silently, so the agent reads a value the
        // game never published. `labelsTruncated` already sits outside `elements` for the same
        // reason.
        if (scalarSink.truncated) json.put("gameTruncated", true)

        json.endObject()
        return json.length
    }

    override fun toString(): String =
        "StateDigest(builds=$builds, tick=$lastBuildTick, ${lastLength}B, ${lastBuildNanos}ns)"
}

/**
 * Renders `ui.elements`, capped at [DigestBudgets.LABEL_LIMIT].
 *
 * A class rather than a lambda because the cap needs a counter, and a counter in a captured
 * local would be a boxed `Ref.IntRef` allocated per build.
 */
private class LabelSink(private val json: Json) : UiLabelVisitor {

    private var written = 0

    private var truncated = false

    fun renderInto(screen: ScreenStatus) {
        written = 0
        truncated = false
        json.arr("elements") { screen.forEachLabel(this@LabelSink) }
        if (truncated) json.put("labelsTruncated", true)
    }

    override fun visit(label: String, visible: Boolean) {
        written++
        if (written > DigestBudgets.LABEL_LIMIT) {
            truncated = true
            return
        }
        // The worst case for one element, where every character of the capped label escapes to
        // two: a count cap bounds how many labels there are and nothing about their size.
        //
        // [MARK_BYTES] is reserved as well, and that is the whole correction here.
        // `labelsTruncated` is written *after* this check, by `renderInto`, so its 23 characters
        // used to be spent past `LABEL_CEILING` - and `LABEL_CEILING` is defined as everything
        // the document may reach before the bytes `commandResults`, `events` and `game` are
        // each guaranteed. Overrunning it therefore does not merely make the document a little
        // longer: it takes those bytes out of `RESULT_MIN_BYTES`, which is the exact budget
        // `ResultPage` sizes a page against, so a page engineered to land was dropped by a
        // label section that had already reported itself as truncated. The mark is reserved
        // unconditionally because whether it will be needed is only known afterwards.
        val cost = minOf(label.length, DigestBudgets.LABEL_CHARS) * 2 + ELEMENT_OVERHEAD
        if (json.length + cost + MARK_BYTES > DigestBudgets.LABEL_CEILING) {
            truncated = true
            return
        }
        json.element {
            key("label")
            value(label, DigestBudgets.LABEL_CHARS)
            put("visible", visible)
        }
    }

    private companion object {
        /** The fixed part of an element: `{"label":"","visible":true}` and its comma. */
        const val ELEMENT_OVERHEAD: Int = 32

        /** `,"labelsTruncated":true` is 23 characters. Rounded up, and reserved before it is due. */
        const val MARK_BYTES: Int = 24
    }
}

/**
 * Renders `events`, capped at [DigestBudgets.EVENT_LIMIT] entries and
 * [DigestBudgets.EVENT_CEILING] bytes.
 *
 * A class rather than the hoisted lambda it replaces, for the same reason [LabelSink] is one:
 * the byte gate needs a flag, and a flag in a captured local is a boxed allocation per build on
 * the simulation thread.
 */
private class EventSink(private val json: Json) : AgentEventVisitor {

    /** Whether an event was dropped for want of room. Rendered as `eventsTruncated`. */
    var truncated: Boolean = false

    override fun visit(message: String) {
        val cost = minOf(message.length, DigestBudgets.EVENT_CHARS) * 2 + ENTRY_OVERHEAD
        if (json.length + cost > DigestBudgets.EVENT_CEILING) {
            truncated = true
            return
        }
        json.value(message, DigestBudgets.EVENT_CHARS)
    }

    private companion object {
        /** Quotes, comma and the truncation mark. */
        const val ENTRY_OVERHEAD: Int = 8
    }
}

/**
 * The `game` block: the sink a [GameStateSource] writes its scalars through.
 *
 * Scalars only, structurally - this is what implements the `game-bridge-mcp` rule that nested
 * objects and arrays do not belong in a digest. Past either cap, writes are dropped and
 * [truncated] says so: a game that published fifty scalars would otherwise quietly push the
 * whole document over its size budget, and a silent truncation is the failure mode that costs
 * an agent the most time.
 *
 * ## Two caps, because the count alone does not bound the bytes
 *
 * [DigestBudgets.GAME_SCALAR_LIMIT] bounds how *many* scalars a game may publish, and that is
 * all it bounds: a name is game-authored text of any length, so 24 scalars called
 * `objectiveRespawnTick` are several times the size of 24 called `a`. That is why
 * [DigestBudgets.MAX_BYTES] was a number asserted in one unsaturated test rather than a
 * ceiling anything enforced - saturating every other section and 24 realistically-named
 * scalars goes straight through it, and nothing noticed.
 *
 * So the second cap is the ceiling itself. The `game` block is rendered **last**, which makes
 * it the one section that can be measured against what the rest of the document has already
 * spent; a scalar whose rendered cost would not leave room for the closing brace and the
 * `gameTruncated` flag is refused, and once one is refused every later one is too. Latching is
 * deliberate: admitting a short scalar after refusing a long one would make the contents of the
 * block depend on publication order in a way nobody can predict from the game's source.
 */
private class ScalarSink(private val json: Json) : GameStateSink {

    private var written = 0

    /** Whether anything was dropped, by either cap. Rendered outside the block as `gameTruncated`. */
    var truncated: Boolean = false
        private set

    private var overBytes = false

    fun renderInto(source: GameStateSource) {
        written = 0
        truncated = false
        overBytes = false
        source.publish(this)
    }

    override fun put(name: String, value: Int) {
        if (accept(name, INT_CHARS)) json.put(name, value)
    }

    override fun put(name: String, value: Long) {
        if (accept(name, LONG_CHARS)) json.put(name, value)
    }

    override fun put(name: String, value: Float) {
        if (accept(name, FLOAT_CHARS)) json.put(name, value)
    }

    override fun put(name: String, value: Boolean) {
        if (accept(name, BOOLEAN_CHARS)) json.put(name, value)
    }

    override fun put(name: String, value: String?) {
        // Doubled because every character of a string may escape to two, and the budget has to
        // hold for the worst case rather than the typical one.
        if (accept(name, if (value == null) NULL_CHARS else value.length * 2 + 2)) {
            json.put(name, value)
        }
    }

    /** @param valueChars an upper bound on what rendering the value costs. */
    private fun accept(name: String, valueChars: Int): Boolean {
        written++
        if (written > DigestBudgets.GAME_SCALAR_LIMIT) {
            truncated = true
            return false
        }
        if (overBytes) return false
        // `,"name":value`
        val cost = name.length + valueChars + MEMBER_OVERHEAD
        if (json.length + cost > CEILING) {
            overBytes = true
            truncated = true
            return false
        }
        return true
    }

    private companion object {
        /** `,"":` around a member. */
        const val MEMBER_OVERHEAD: Int = 4

        const val INT_CHARS: Int = 11
        const val LONG_CHARS: Int = 20
        /** Sign, integer part at [Json] float precision, point and four decimals. */
        const val FLOAT_CHARS: Int = 24
        const val BOOLEAN_CHARS: Int = 5
        const val NULL_CHARS: Int = 4

        /**
         * What the document may reach before a scalar is refused.
         *
         * [DigestBudgets.GAME_CEILING]: the ceiling less the tail, which is the closing braces
         * and the truncation flags still to be written after the last scalar is admitted.
         */
        const val CEILING: Int = DigestBudgets.GAME_CEILING
    }
}
