package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.core.NetRole

/**
 * A digest wired to sources a test controls.
 *
 * The population is a *census*, not a world, which is the point of the design rather than a
 * shortcut in the test: the digest is handed counts that the game maintains incrementally at
 * spawn and despawn, so its cost cannot depend on how many entities exist. A fixture that had
 * to build a 5000-entity world to test the digest would be evidence that it walks one.
 */
internal class DigestFixture(
    entityCount: Int = 0,
    val bridge: AgentBridge = AgentBridge(),
    val clock: SteppingClock = SteppingClock(),
    rebuildIntervalTicks: Int = DigestBudgets.REBUILD_INTERVAL_TICKS,
) {
    val census: ArchetypeCensus = ArchetypeCensus(listOf("champion", "minion", "projectile", "ward"))

    val loop: MutableLoopStatus = MutableLoopStatus()

    val ui: MutableScreenStatus = MutableScreenStatus()

    val game: MutableGameState = MutableGameState()

    val timings: AgentTimings = AgentTimings()

    val digest: StateDigest = StateDigest(
        bridge = bridge,
        sources = DigestSources(
            entities = census,
            loop = loop,
            net = TestNetStatus,
            ui = ui,
            game = game,
        ),
        timings = timings,
        clock = clock,
        rebuildIntervalTicks = rebuildIntervalTicks,
    )

    init {
        populate(entityCount)
    }

    /** Spreads [count] entities across the archetypes, the way a MOBA does. */
    fun populate(count: Int) {
        val champion = census.slotOf("champion")
        val minion = census.slotOf("minion")
        val projectile = census.slotOf("projectile")
        for (index in 0 until count) {
            when (index % 10) {
                0 -> census.spawned(champion)
                in 1..7 -> census.spawned(minion)
                else -> census.spawned(projectile)
            }
        }
    }

    /** Builds, publishes and returns the document. */
    fun build(): String {
        digest.publish()
        return bridge.snapshot()
    }
}

/** Wall time a test controls, advancing a fixed amount per reading. */
internal class SteppingClock(var advancePerCall: Long = 0L) : AgentClock {

    var nanos: Long = 0L
        private set

    override fun nowNanos(): Long {
        val now = nanos
        nanos += advancePerCall
        return now
    }
}

internal class MutableLoopStatus(
    override var paused: Boolean = false,
    override var timeScale: Float = 1f,
    override var fps: Float = 60f,
) : LoopStatus

internal object TestNetStatus : NetStatus {
    override val role: NetRole get() = NetRole.Server
    override val clients: Int get() = 2
    override val inKbps: Float get() = 12.5f
    override val outKbps: Float get() = 48.25f
}

internal class MutableScreenStatus(
    override var screen: String = "ArenaScreen",
) : ScreenStatus {

    val labels: MutableList<Pair<String, Boolean>> = ArrayList()

    override fun forEachLabel(visitor: UiLabelVisitor) {
        for (index in labels.indices) visitor.visit(labels[index].first, labels[index].second)
    }
}

/** The `@AgentState` block, hand-written where generated code will be. */
internal class MutableGameState : GameStateSource {

    var score: Int = 1280

    var phase: String = "RUNNING"

    var deferredRan: Boolean = false

    /** Extra scalars, so the cap can be exercised. */
    var extraScalars: Int = 0

    /** Publishes a scalar literally called `truncated`, which the digest's own flag once collided with. */
    var publishTruncatedScalar: Boolean = false

    /** The stem of an extra scalar's name. Realistic names are what break the byte ceiling. */
    var extraScalarNamePrefix: String = "extra"

    override fun publish(sink: GameStateSink) {
        if (publishTruncatedScalar) sink.put("truncated", false)
        sink.put("score", score)
        sink.put("phase", phase)
        sink.put("deferredRan", deferredRan)
        for (index in 0 until extraScalars) sink.put("$extraScalarNamePrefix$index", index)
    }
}
