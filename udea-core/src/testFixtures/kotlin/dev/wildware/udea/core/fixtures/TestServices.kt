package dev.wildware.udea.core.fixtures

import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueSink
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.NetRole
import dev.wildware.udea.core.PhysicsWorld
import dev.wildware.udea.core.RngService
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SceneManager
import dev.wildware.udea.core.gameContext

/**
 * Working implementations of the services `GameContext` names, for tests.
 *
 * The production implementations belong to their own issues. These are small, real and
 * observable — a test can assert what the simulation asked its physics world or its event bus
 * to do — which is what makes "two contexts in one JVM are independent" a checkable claim
 * rather than an assertion about construction.
 */

/**
 * splitmix64, one independent stream per [RngStream], all derived from one seed.
 *
 * Not the production generator (that is xoshiro256** with explicit state in the snapshot),
 * but it has the property the tests need: same seed, same sequence; different seed, different
 * sequence; and drawing from one stream never shifts another.
 */
public class DeterministicRngService(override val seed: Long) : RngService {

    private val state = LongArray(RngStream.entries.size) { index ->
        seed + GOLDEN_GAMMA * (index + 1)
    }

    override fun nextInt(stream: RngStream, bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }
        return ((next(stream) ushr 1) % bound).toInt()
    }

    override fun nextFloat(stream: RngStream): Float =
        ((next(stream) ushr 40).toFloat()) / (1 shl 24).toFloat()

    override fun nextLong(stream: RngStream): Long = next(stream)

    override fun nextBoolean(stream: RngStream): Boolean = next(stream) < 0L

    private fun next(stream: RngStream): Long {
        val index = stream.ordinal
        state[index] += GOLDEN_GAMMA
        var z = state[index]
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private companion object {
        const val GOLDEN_GAMMA: Long = -0x61c8864680b583ebL
    }
}

/** Counts what the simulation asked of physics. Never simulates anything. */
public class RecordingPhysicsWorld : PhysicsWorld {
    public var stepCount: Int = 0
        private set

    public var rebuildCount: Int = 0
        private set

    override fun stepOneTick() {
        stepCount++
    }

    override fun rebuildFromComponents() {
        rebuildCount++
    }
}

/** Holds a requested scene until [applyPending], which is what the SimBarrier will do. */
public class QueueingSceneManager(initialScene: SceneId? = null) : SceneManager {

    override var activeSceneId: SceneId? = initialScene
        private set

    public var pendingSceneId: SceneId? = null
        private set

    override fun requestScene(sceneId: SceneId) {
        pendingSceneId = sceneId
    }

    /** Applies the queued swap, as the between-tick barrier would. */
    public fun applyPending(): Boolean {
        val pending = pendingSceneId ?: return false
        activeSceneId = pending
        pendingSceneId = null
        return true
    }
}

/** Keeps every cue the simulation emitted, in order. */
public class RecordingCueSink : CueSink {
    private val recorded = ArrayList<Cue>()

    public val cues: List<Cue> get() = recorded

    override fun emit(cue: Cue) {
        recorded += cue
    }
}

/**
 * A fully wired [GameContext] backed by the fixtures above.
 *
 * Two calls with different [seed]s produce two independent simulations — separate clocks,
 * separate random streams, separate services — in the same JVM, which is the thing the old
 * file-level globals made impossible.
 */
public fun testGameContext(
    seed: Long = 0L,
    role: NetRole = NetRole.Standalone,
    config: EngineConfig = EngineConfig(seed = seed),
    configure: GameContextBuilder.() -> Unit = {},
): GameContext = gameContext {
    this.config = config
    this.role = role
    rng = DeterministicRngService(config.seed)
    physics = RecordingPhysicsWorld()
    scenes = QueueingSceneManager()
    cues = RecordingCueSink()
    configure()
}
