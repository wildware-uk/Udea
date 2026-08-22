package dev.wildware.udea.core

import dev.wildware.udea.core.identity.NetId

/**
 * The services [GameContext] names.
 *
 * Every one of these is **declared here and implemented elsewhere**, by the issue that owns
 * the subsystem. They live in `udea-core` for one reason: `GameContext` has to name their
 * types, and a context that named concrete classes would drag the whole engine onto the
 * kernel's compile classpath and make the module graph a cycle.
 *
 * A module that needs a service `GameContext` does not name must not add a field here. It
 * registers a [ServiceKey] instead — see [GameContextBuilder.service].
 */

/**
 * The named random streams simulation code may draw from (spec 5, "Randomness").
 *
 * Streams are separate so that drawing a loot roll cannot shift the combat sequence, which
 * is what makes a replay survive an unrelated gameplay change. Presentation does not appear
 * here: it gets a separately typed, wall-seeded generator in a module simulation cannot see.
 */
public enum class RngStream {
    Combat,
    Loot,
    AI,
    Spawn,
    Wave,
}

/**
 * Seeded randomness for simulation code.
 *
 * The build fails if simulation code reads `kotlin.random.Random` or a wall clock, so this
 * is the only source of randomness a system may use. Implementation (xoshiro256** with
 * explicit state in the snapshot) is owned by its own issue.
 */
public interface RngService {
    /** The seed every stream was derived from. Equal to [EngineConfig.seed]. */
    public val seed: Long

    /** Uniform in `0 until bound`. */
    public fun nextInt(stream: RngStream, bound: Int): Int

    /** Uniform in `[0, 1)`. */
    public fun nextFloat(stream: RngStream): Float

    public fun nextLong(stream: RngStream): Long

    public fun nextBoolean(stream: RngStream): Boolean
}

/**
 * Everything physical that is *not* authoritative movement (spec 3.4).
 *
 * `CharacterMover` — an allocation-free capsule sweep, replayable 60x per frame — owns
 * movement for every predicted or replicated entity. Box2D survives behind this interface
 * for sensor queries, debris and server-only projectiles, and **is never snapshot state**:
 * after a restore, bodies are rebuilt from components in one deterministic pass, which is
 * what [rebuildFromComponents] is for.
 *
 * There is no `step(seconds)`. The physics world advances a whole tick at a time like
 * everything else.
 */
public interface PhysicsWorld {
    /** Advances the non-authoritative physics world by exactly one simulation tick. */
    public fun stepOneTick()

    /** Rebuilds every body from component state. Called after a snapshot restore. */
    public fun rebuildFromComponents()
}

/**
 * Scene lifecycle. A scene swap is a between-tick mutation, so a request made mid-tick takes
 * effect at the top of the next `step()` through the `SimBarrier` (spec 5) — never in the
 * middle of a system's iteration.
 */
public interface SceneManager {
    /** The scene currently simulating, or `null` before the first scene is active. */
    public val activeSceneId: SceneId?

    /** Queues a swap to [sceneId]. Takes effect at the top of the next tick. */
    public fun requestScene(sceneId: SceneId)
}

/**
 * Names a scene to [SceneManager].
 *
 * A value class rather than a `String` so scene identity has exactly one declaration to
 * narrow to `AssetId` when the asset pipeline lands, instead of a `String` to chase through
 * every module then. It is *not* typo detection: `SceneId("levle_1")` still compiles, and the
 * did-you-mean diagnostic the spec mandates comes from build-time asset validation, not from
 * this type. What it buys today is that a scene name cannot be passed where any other string
 * is meant, and vice versa.
 */
@JvmInline
public value class SceneId(public val value: String) {
    init {
        require(value.isNotEmpty()) { "a scene id must not be empty" }
    }

    override fun toString(): String = value
}

/**
 * A one-shot presentation event the simulation emits and never reads back.
 *
 * Cues are how a deterministic simulation tells a non-deterministic presentation layer that
 * something happened — a hit landed, an ability fired — without presentation code running
 * inside `world.update`. A cue is not simulation state and never enters a snapshot.
 */
public data class Cue(
    /** Which presentation effect this cue asks for. */
    public val id: CueId,
    /** The tick the simulation emitted it on. */
    public val tick: Tick,
    /** The entity it came from, or [NetId.NONE] for a world-level cue. */
    public val source: NetId = NetId.NONE,
)

/**
 * Identifies which presentation effect a [Cue] asks for.
 *
 * Distinct at compile time from every other `Int` in the engine — an entity count, a field
 * index, a component type id — so `Cue(id = entityCount, ...)` stops compiling. `udea-render`
 * and audio switch on this to decide what to play, which makes it a domain identity rather
 * than a number.
 */
@JvmInline
public value class CueId(public val raw: Int) {
    override fun toString(): String = "CueId($raw)"
}

/** Where [Cue]s go. `udea-render` and audio drain it; the simulation only writes. */
public interface CueSink {
    public fun emit(cue: Cue)
}

/**
 * Engine logging. An interface rather than a static logger so two simulations in one JVM can
 * be told apart in the output, which is the whole reason the globals had to go.
 */
public interface Log {
    public fun debug(message: String)

    public fun info(message: String)

    public fun warn(message: String)

    public fun error(message: String, cause: Throwable? = null)

    /** Discards everything. The default for a context that was not given a logger. */
    public object NoOp : Log {
        override fun debug(message: String): Unit = Unit

        override fun info(message: String): Unit = Unit

        override fun warn(message: String): Unit = Unit

        override fun error(message: String, cause: Throwable?): Unit = Unit
    }
}
