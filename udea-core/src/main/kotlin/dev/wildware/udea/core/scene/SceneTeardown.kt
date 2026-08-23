package dev.wildware.udea.core.scene

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.snapshot.SnapshotRing

/**
 * When a teardown step runs, relative to the world being emptied.
 *
 * Two stages rather than a free-form priority number, because there are exactly two answers a
 * step can need and a number invites the third: "somewhere in the middle", which is where an
 * ordering bug hides.
 */
public enum class SceneTeardownStage {

    /**
     * Before the entities go.
     *
     * For anything that reads them, or that would react to them disappearing: pausing outbound
     * replication so a client is not sent a world mid-demolition, and draining the cue queue so
     * the previous level's explosions do not play over the new one.
     */
    BeforeWorldCleared,

    /**
     * After the world is empty.
     *
     * For anything keyed to a world that no longer exists: the snapshot ring, whose slots all
     * belong to the old scene, and scene-scoped assets, which nothing references any more.
     */
    AfterWorldCleared,
}

/**
 * One named step of a scene teardown, contributed by the subsystem that owns the state.
 *
 * Named rather than a lambda for the same reason `BarrierAction` is: when a swap goes wrong,
 * "clear snapshot ring" is what a log line or an agent's trace can say, and a `() -> Unit`
 * cannot.
 *
 * `udea-core` supplies the steps for the state it owns ([ClearCueQueue], [ClearSnapshotRing]).
 * Replication pause belongs to `udea-net` and scene-scoped asset release to `udea-assets`; both
 * implement this and are handed to the manager by whoever composes the game, which is why the
 * kernel does not name them.
 */
public interface SceneTeardownStep {

    /** Short, stable, human-readable. Appears in the swap log and in agent traces. */
    public val name: String

    /** Which side of the world being emptied this step runs on. */
    public val stage: SceneTeardownStage

    /** Brings this subsystem to a defined state for a world that is about to be replaced. */
    public fun tearDown(world: World, ctx: GameContext)
}

/**
 * Discards cues emitted by the outgoing scene.
 *
 * Before the world is cleared, because a cue names a source entity: draining after the
 * entities are gone would leave presentation holding ids that no longer resolve.
 */
public class ClearCueQueue(private val cues: CueQueue) : SceneTeardownStep {

    override val name: String get() = "drain cue queue"

    override val stage: SceneTeardownStage get() = SceneTeardownStage.BeforeWorldCleared

    override fun tearDown(world: World, ctx: GameContext) {
        cues.clear()
    }
}

/**
 * Empties the snapshot ring.
 *
 * Every held snapshot belongs to the outgoing scene — `WorldSnapshot.scene` records it, and
 * `SnapshotService.applyNow` refuses a restore across scenes — so keeping them would only
 * offer an agent rewind targets that are guaranteed to be refused.
 */
public class ClearSnapshotRing(private val ring: SnapshotRing) : SceneTeardownStep {

    override val name: String get() = "clear snapshot ring"

    override val stage: SceneTeardownStage get() = SceneTeardownStage.AfterWorldCleared

    override fun tearDown(world: World, ctx: GameContext) {
        ring.clear()
    }
}
