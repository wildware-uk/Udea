package dev.wildware.udea.agent.state

import dev.wildware.udea.core.NetRole

/**
 * Everything outside `udea-agent` that the Tier-0 digest reports on.
 *
 * ## Why these are interfaces and not fields on the digest
 *
 * The digest names the loop, the network and the screen, and this module may see none of them:
 * `udea-render` owns the screen, `udea-net` owns the connection, and both are downstream. The
 * digest asks four narrow questions instead, and whoever can answer each one implements the
 * matching interface. A host with no renderer keeps [ScreenStatus.None] and its `ui` block
 * says so honestly rather than being absent.
 *
 * Grouping them in one type rather than five constructor parameters is the standards' "more
 * than about five parameters suggests a missing type" - this is the missing type. It also
 * gives a host one place to see what the digest can report, which is the list an engine
 * integration has to satisfy.
 */
public class DigestSources(
    /** How many entities there are, broken down by archetype. */
    public val entities: EntityCensus = EntityCensus.Empty,
    /** Pause state, time scale and frame rate. */
    public val loop: LoopStatus = LoopStatus.Unknown,
    /** Network role and throughput. */
    public val net: NetStatus = NetStatus.Offline,
    /** Current screen and its visible labels. */
    public val ui: ScreenStatus = ScreenStatus.None,
    /** The game's own `@AgentState` scalars. */
    public val game: GameStateSource = GameStateSource { },
)

/**
 * How many entities exist, by archetype.
 *
 * **Counts are maintained incrementally, never by walking the world.** At 500 entities a walk
 * is most of the 0.3ms budget and at 5000 it is all of it, and the digest is rebuilt on the
 * simulation thread - so the cost would be paid by the game, every tick, whether an agent was
 * looking or not. [ArchetypeCensus] is the implementation that does the bookkeeping at
 * spawn/despawn instead.
 */
public interface EntityCensus {

    /** Live entities, across every archetype. */
    public val entityCount: Int

    /** Visits each archetype and its count, in registration order. */
    public fun forEachArchetype(visitor: ArchetypeVisitor)

    public companion object {
        /** No entities and no archetypes. What a world that has not registered any reports. */
        public val Empty: EntityCensus = object : EntityCensus {
            override val entityCount: Int get() = 0

            override fun forEachArchetype(visitor: ArchetypeVisitor) = Unit

            override fun toString(): String = "EntityCensus.Empty"
        }
    }
}

/** Callback for [EntityCensus.forEachArchetype]. A `fun interface` so a digest build allocates nothing. */
public fun interface ArchetypeVisitor {
    /** One archetype and how many of it are live. */
    public fun visit(archetype: String, count: Int)
}

/**
 * An [EntityCensus] the game keeps up to date as entities come and go.
 *
 * Slot-based for the same reason [dev.wildware.udea.agent.AgentTimings] is: the recording call
 * sites are spawn and despawn, which are on the simulation thread and inside the tick budget,
 * so they get an array increment rather than a map lookup.
 *
 * Not thread-safe, and deliberately: it belongs to the simulation, like the world it counts.
 * The digest reads it on the same thread that writes it.
 */
public class ArchetypeCensus(
    /** The archetype names, in the order they should be reported. */
    archetypes: List<String>,
) : EntityCensus {

    private val names: Array<String> = archetypes.toTypedArray()
    private val counts: IntArray = IntArray(names.size)

    init {
        require(names.isNotEmpty()) { "a census with no archetypes cannot count anything" }
        require(names.distinct().size == names.size) {
            "archetype names must be distinct, were ${archetypes.joinToString()}"
        }
    }

    override var entityCount: Int = 0
        private set

    /**
     * The slot for [archetype]. Look it up once at registration and keep the index.
     *
     * @throws IllegalArgumentException if the archetype was not declared at construction. The
     *   set is fixed on purpose: an archetype list that grew at runtime would make the digest
     *   a different shape from tick to tick.
     */
    public fun slotOf(archetype: String): Int {
        val index = names.indexOf(archetype)
        require(index >= 0) { "unknown archetype $archetype; declared: ${names.joinToString()}" }
        return index
    }

    /** Records one entity of [slot] appearing. */
    public fun spawned(slot: Int) {
        require(slot in counts.indices) { "no archetype at slot $slot" }
        counts[slot]++
        entityCount++
    }

    /**
     * Records one entity of [slot] going away.
     *
     * @throws IllegalStateException when the count is already zero. A census that drifted
     *   negative would report a plausible-looking number that is simply wrong, and the digest
     *   is the number an agent trusts first.
     */
    public fun despawned(slot: Int) {
        require(slot in counts.indices) { "no archetype at slot $slot" }
        check(counts[slot] > 0) { "despawned a ${names[slot]} when none were live" }
        counts[slot]--
        entityCount--
    }

    /** The live count for [slot]. */
    public fun countAt(slot: Int): Int = counts[slot]

    override fun forEachArchetype(visitor: ArchetypeVisitor) {
        for (index in names.indices) visitor.visit(names[index], counts[index])
    }

    override fun toString(): String = "ArchetypeCensus($entityCount across ${names.size} archetypes)"
}

/** Pause state, time scale and frame rate, as the host loop knows them. */
public interface LoopStatus {

    /** Whether the simulation is frozen. */
    public val paused: Boolean

    /** Simulated seconds per wall second. */
    public val timeScale: Float

    /** Host frames per second, or `0` where nothing measures it (a headless run). */
    public val fps: Float

    public companion object {
        /** Running, unscaled, unmeasured. What a harness driving `step()` by hand reports. */
        public val Unknown: LoopStatus = object : LoopStatus {
            override val paused: Boolean get() = false
            override val timeScale: Float get() = 1f
            override val fps: Float get() = 0f
            override fun toString(): String = "LoopStatus.Unknown"
        }
    }
}

/** The network summary: what this process is, and how much it is sending. */
public interface NetStatus {

    /** Authority role. Rendered by name, so an agent sees `Server`, not `1`. */
    public val role: NetRole

    /** Connected clients. Always `0` off the server. */
    public val clients: Int

    /** Inbound kilobits per second. */
    public val inKbps: Float

    /** Outbound kilobits per second. */
    public val outKbps: Float

    public companion object {
        /** A standalone process with no transport. */
        public val Offline: NetStatus = object : NetStatus {
            override val role: NetRole get() = NetRole.Standalone
            override val clients: Int get() = 0
            override val inKbps: Float get() = 0f
            override val outKbps: Float get() = 0f
            override fun toString(): String = "NetStatus.Offline"
        }
    }
}

/**
 * The current screen and the labels on it.
 *
 * `ui.screen` is what `list_instances` prints, so five running instances are tellable apart at
 * a glance, and `ui.elements[].label`/`.visible` is what the bridge folds into its compact
 * digest - both are named in the `game-bridge-mcp` contract, so the names here are not ours to
 * choose.
 */
public interface ScreenStatus {

    /** The screen name, e.g. `GameScreen`. */
    public val screen: String

    /** Visits each UI label and whether it is currently visible. */
    public fun forEachLabel(visitor: UiLabelVisitor)

    public companion object {
        /** A process with no renderer. */
        public val None: ScreenStatus = object : ScreenStatus {
            override val screen: String get() = "headless"

            override fun forEachLabel(visitor: UiLabelVisitor) = Unit

            override fun toString(): String = "ScreenStatus.None"
        }
    }
}

/** Callback for [ScreenStatus.forEachLabel]. */
public fun interface UiLabelVisitor {
    /** One label and whether it is on screen. */
    public fun visit(label: String, visible: Boolean)
}

/**
 * The game's own scalars, published into the digest's `game` block.
 *
 * The generated implementation of this is what `@AgentState` produces: one call per annotated
 * property, no reflection. A game may also implement it by hand.
 */
public fun interface GameStateSource {
    /** Writes this game's scalars. Called on the simulation thread during a digest build. */
    public fun publish(sink: GameStateSink)
}

/**
 * What a [GameStateSource] may write: scalars, and nothing else.
 *
 * The restriction is the `game-bridge-mcp` contract, verbatim - *"scalar fields are included in
 * the digest; nested objects and arrays are not, that is where the megabyte-sized entity lists
 * live"* - and it is enforced by this interface having no way to open an object or an array
 * rather than by asking anyone to remember it. A game that wants to publish a list has a
 * `query_entities`-shaped problem, and that is a tool.
 */
public interface GameStateSink {

    /** A named integer. */
    public fun put(name: String, value: Int)

    /** A named long. */
    public fun put(name: String, value: Long)

    /** A named float, rounded to four decimal places like every other float here. */
    public fun put(name: String, value: Float)

    /** A named boolean. */
    public fun put(name: String, value: Boolean)

    /** A named string, or `null`. */
    public fun put(name: String, value: String?)
}
