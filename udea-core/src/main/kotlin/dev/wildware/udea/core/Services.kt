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

// `PhysicsWorld` was declared here while it was two methods. It now carries body handles,
// shapes, sensor and raycast queries and contact registration, so it lives in
// `dev.wildware.udea.core.physics` beside the components it rebuilds from — this file is an
// index of what `GameContext` names, not a home for a subsystem's whole surface.

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
 * A [CueSink] that observes cues on the way to another one.
 *
 * ## Why the engine has to know decorators exist
 *
 * Because the sink on `GameContext` is written and the queue behind it is **drained**, and those
 * are two different jobs done by two different callers. The simulation only ever writes, so a
 * decorator is invisible to it; a mixer has to reach the concrete `CueQueue` to drain it, and a
 * decorated sink is not one. This was measured rather than reasoned about: wrapping the context's
 * sink so the agent event ring could see the fight made `MobaAudio.of` refuse to build at all,
 * with a message correctly saying that a sink which is not a queue has nothing to drain.
 *
 * So a drainer walks [delegate] to the end of the chain instead of casting once, and the refusal
 * is kept for a chain that genuinely has no queue at the bottom of it - which is a real wiring
 * mistake and must not be silently absorbed.
 *
 * Implementations must not loop: [delegate] is the sink this one was constructed with, and a
 * decorator that returned itself would hang every walk.
 */
public interface CueSinkDecorator : CueSink {

    /** The sink each cue is passed to after this one has seen it. Never this object. */
    public val delegate: CueSink
}

/**
 * Walks a [CueSink] chain to the sink at the bottom of it.
 *
 * Returns the receiver unchanged when it is not a [CueSinkDecorator], which is the common case
 * and the shipped one: only a debug build ever decorates.
 */
public tailrec fun CueSink.innermost(): CueSink =
    if (this is CueSinkDecorator) delegate.innermost() else this

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
