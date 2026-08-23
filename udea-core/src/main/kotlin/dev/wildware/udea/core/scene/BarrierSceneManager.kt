package dev.wildware.udea.core.scene

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SceneManager
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier

/**
 * Scene swaps, applied at a tick boundary and never inside one.
 *
 * ## The guarantee
 *
 * [load] and [requestScene] **queue**. The swap runs as a single [BarrierAction] at the top of
 * the next `Simulation.step()`, before any system, and teardown *and* populate happen inside
 * that one action. So a tick sees either the whole old scene or the whole new one, and never a
 * world that is half torn down or half built.
 *
 * That is the property the old code could not state. `GameScreen` spawned `level.entities`
 * inside a `Gdx.app.postRunnable` (`common/UdeaGameManager.kt:191-220`) on an unspecified later
 * frame, guarded by a `started` flag that made `render()` return early (`:224`) — so "the world
 * is empty right now" was a state the engine had, and every system had to be written not to
 * mind.
 *
 * ## Order
 *
 * One fixed sequence, and the parts the kernel owns cannot be omitted by a wiring mistake:
 *
 * 1. every [SceneTeardownStage.BeforeWorldCleared] step, in list order;
 * 2. destroy every physics body — before the entities, so a body's owner still resolves;
 * 3. `world.removeAll()`, recycled ids cleared, so the same scene loaded twice mints the same
 *    entity ids whatever ran in between;
 * 4. reset the [NetIdIndex], recycling history and all, so the same scene loaded twice lays
 *    out identically;
 * 5. every [SceneTeardownStage.AfterWorldCleared] step, in list order;
 * 6. [Scene.populate], synchronously.
 *
 * Nothing here touches `Gdx`, and nothing here is asynchronous.
 */
public class BarrierSceneManager(
    private val barrier: SimBarrier,
    private val netIds: NetIdIndex,
    /**
     * Subsystem teardown the kernel does not own — replication pause, scene-scoped asset
     * release — each running on the side of the world clear its [SceneTeardownStep.stage] names.
     */
    teardown: List<SceneTeardownStep> = emptyList(),
) : SceneManager {

    private val beforeClear =
        teardown.filterTo(ArrayList()) { it.stage == SceneTeardownStage.BeforeWorldCleared }

    private val afterClear =
        teardown.filterTo(ArrayList()) { it.stage == SceneTeardownStage.AfterWorldCleared }

    /** True while a swap action is running. Guards [addTeardown] against a torn teardown. */
    private var swapping = false

    private val known = LinkedHashMap<SceneId, Scene>()

    private val listeners = ArrayList<(SceneId, Tick) -> Unit>()

    override var activeSceneId: SceneId? = null
        private set

    /** The scene a queued swap will install, or `null` when none is queued. */
    public var requestedSceneId: SceneId? = null
        private set

    /** The tick the most recent swap landed on. `null` before the first one. */
    public var swappedAtTick: Tick? = null
        private set

    /** Swaps applied since construction. What a test or an agent polls to see one has landed. */
    public var swapCount: Long = 0L
        private set

    /** Every scene this manager can resolve by id, in registration order. */
    public val registeredSceneIds: Set<SceneId> get() = known.keys

    /**
     * Makes [scene] resolvable by [requestScene].
     *
     * @throws IllegalArgumentException if a different scene is already registered under that
     *   id. Two scenes with one id would make `requestScene` depend on registration order, and
     *   a snapshot's recorded scene would no longer identify what it was taken in.
     */
    public fun register(scene: Scene) {
        val existing = known[scene.id]
        require(existing == null || existing === scene) {
            "scene id ${scene.id} is already registered to a different Scene"
        }
        known[scene.id] = scene
    }

    /**
     * Adds [step] to the teardown, on the side its [SceneTeardownStep.stage] names.
     *
     * Registration after construction rather than a constructor argument, because the
     * subsystems that own teardown state are built *after* the kernel's: the snapshot ring
     * needs a generated `ComponentRegistry`, which does not exist when this manager does.
     *
     * @throws IllegalStateException if a swap is in flight. Adding a step half way through a
     *   teardown would run some of the sequence and not the rest.
     */
    public fun addTeardown(step: SceneTeardownStep) {
        check(!swapping) { "cannot add teardown step '${step.name}' while a scene swap is running" }
        when (step.stage) {
            SceneTeardownStage.BeforeWorldCleared -> beforeClear += step
            SceneTeardownStage.AfterWorldCleared -> afterClear += step
        }
    }

    /** Every teardown step, in the order a swap runs them. */
    public val teardownStepNames: List<String>
        get() = (beforeClear + afterClear).map { it.name }

    /** Registers [scene] if it is new, then queues a swap to it. */
    public fun load(scene: Scene) {
        register(scene)
        requestScene(scene.id)
    }

    /**
     * Queues a swap to [sceneId]. It lands at the top of the next `Simulation.step()`.
     *
     * @throws IllegalArgumentException if no scene is registered under [sceneId]. Failing here
     *   rather than inside the barrier is deliberate: a barrier action that throws is logged
     *   and the drain continues, so the caller would get no answer and the world would simply
     *   never change.
     */
    override fun requestScene(sceneId: SceneId) {
        val scene = requireNotNull(known[sceneId]) {
            "no scene registered as $sceneId; known scenes are ${known.keys}"
        }
        requestedSceneId = sceneId
        barrier.submit(SwapAction(scene))
    }

    /**
     * Calls [listener] with the scene and the tick, each time a swap lands.
     *
     * Runs inside the barrier action, so a listener observing the world sees it fully
     * populated — which is the point of being told at all.
     */
    public fun onSwapped(listener: (SceneId, Tick) -> Unit) {
        listeners += listener
    }

    override fun toString(): String =
        "BarrierSceneManager(active=$activeSceneId, requested=$requestedSceneId, swaps=$swapCount)"

    /** The named mutation [requestScene] queues. One action: teardown and populate together. */
    private inner class SwapAction(private val scene: Scene) : BarrierAction {

        override val label: String get() = "load scene ${scene.id}"

        override fun apply(world: World, ctx: GameContext) {
            swapping = true
            try {
                swap(world, ctx)
            } finally {
                swapping = false
            }
        }

        private fun swap(world: World, ctx: GameContext) {
            for (step in beforeClear) step.tearDown(world, ctx)

            ctx.physics.destroyAllBodies()
            // `clearRecycled = true` so a scene loaded twice mints the same entity ids both
            // times: without it the second load draws from the recycled queue the first one
            // filled, and two runs of the same scene stop agreeing on entity identity.
            world.removeAll(clearRecycled = true)
            netIds.reset()

            for (step in afterClear) step.tearDown(world, ctx)

            scene.populate(SceneScope(world, ctx, netIds, scene.seed))

            activeSceneId = scene.id
            requestedSceneId = null
            swappedAtTick = ctx.clock.tick
            swapCount++
            for (listener in listeners) listener(scene.id, ctx.clock.tick)
        }
    }
}
