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
 * ## Two gates on rebuilding
 *
 * A rebuild happens only when **both** are true: at least [DigestBudgets.REBUILD_INTERVAL_TICKS]
 * ticks have passed, *and* something has read the document since the last one was published.
 * The read flag lives on [AgentBridge] so that the HTTP handler and an in-process harness set
 * it the same way. A game nobody is watching therefore builds one digest and then stops, and
 * the agent surface costs a shipped game nothing.
 *
 * A rewind is the exception that proves the interval: the tick going *backwards* forces a
 * rebuild, because the document describes a world that has been replaced.
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
    private val eventVisitor = AgentEventVisitor { json.value(it, DigestBudgets.EVENT_CHARS) }

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

    /** Whether a build is currently due. See the class KDoc for the two gates. */
    public fun isDue(): Boolean {
        if (builds == 0L) return true
        if (!bridge.readSinceLastPublish()) return false
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
    }

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
        json.put("tick", bridge.tick)
        json.put("paused", sources.loop.paused)
        json.put("timeScale", sources.loop.timeScale)
        json.put("fps", sources.loop.fps)
        json.put("completedCommandId", bridge.completedCommandId())
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

        bridge.renderCommandResults(json, "commandResults", DigestBudgets.RESULT_LIMIT)

        json.arr("events") { bridge.events.forEachRecent(DigestBudgets.EVENT_LIMIT, eventVisitor) }

        json.obj("game") { scalarSink.renderInto(sources.game) }

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

    fun renderInto(screen: ScreenStatus) {
        written = 0
        json.arr("elements") { screen.forEachLabel(this@LabelSink) }
        if (written > DigestBudgets.LABEL_LIMIT) json.put("labelsTruncated", true)
    }

    override fun visit(label: String, visible: Boolean) {
        written++
        if (written > DigestBudgets.LABEL_LIMIT) return
        json.element {
            put("label", label)
            put("visible", visible)
        }
    }
}

/**
 * The `game` block: the sink a [GameStateSource] writes its scalars through.
 *
 * Scalars only, structurally - this is what implements the `game-bridge-mcp` rule that nested
 * objects and arrays do not belong in a digest. Past the cap, writes are dropped and the block
 * says so: a game that published fifty scalars would otherwise quietly push the whole document
 * over its size budget, and a silent truncation is the failure mode that costs an agent the
 * most time.
 */
private class ScalarSink(private val json: Json) : GameStateSink {

    private var written = 0

    fun renderInto(source: GameStateSource) {
        written = 0
        source.publish(this)
        if (written > DigestBudgets.GAME_SCALAR_LIMIT) json.put("truncated", true)
    }

    override fun put(name: String, value: Int) {
        if (accept()) json.put(name, value)
    }

    override fun put(name: String, value: Long) {
        if (accept()) json.put(name, value)
    }

    override fun put(name: String, value: Float) {
        if (accept()) json.put(name, value)
    }

    override fun put(name: String, value: Boolean) {
        if (accept()) json.put(name, value)
    }

    override fun put(name: String, value: String?) {
        if (accept()) json.put(name, value)
    }

    private fun accept(): Boolean {
        written++
        return written <= DigestBudgets.GAME_SCALAR_LIMIT
    }
}
