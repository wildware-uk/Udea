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
 *
 * ## When populate throws
 *
 * The old scene is gone by then, and a `Scene` is a program rather than a value, so it cannot be
 * put back. The guarantee is the other one available: **the whole new scene, or no scene at
 * all.** A throwing [Scene.populate] re-empties the world, leaves [activeSceneId] `null`,
 * increments [failedSwapCount] and rethrows so the barrier logs it with the action's label and
 * the tick.
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

    /**
     * The scene the world currently holds, or `null` when it holds no scene at all.
     *
     * `null` before the first load, and `null` again after a swap whose [Scene.populate] threw:
     * see [failedSwapCount]. It is cleared *before* the teardown and set only once populate has
     * returned, so it never names a scene the world is not actually holding — the id is what
     * `SnapshotTimeTravel` gates a restore on, and a stale one would let a snapshot taken in the
     * old scene be applied to a world that no longer contains it.
     */
    override var activeSceneId: SceneId? = null
        private set

    /**
     * The scene a queued swap will install, or `null` when none is queued.
     *
     * Stays set after a failed swap, so the pair (`activeSceneId == null`,
     * `requestedSceneId == x`) says exactly what happened: the world is empty and x is what it
     * was trying to become.
     */
    public var requestedSceneId: SceneId? = null
        private set

    /**
     * The tick the most recent swap landed on. `null` before the first one.
     *
     * `internal`: the tick a swap landed on is asserted by this module's own tests and read by
     * nothing outside it. A consumer that needs it — a swap already tells a listener the tick —
     * widens it in the commit that adds the consumer.
     */
    internal var swappedAtTick: Tick? = null
        private set

    /** Swaps applied since construction. What a test or an agent polls to see one has landed. */
    public var swapCount: Long = 0L
        private set

    /**
     * Swaps whose [Scene.populate] threw, leaving the world empty rather than half built.
     *
     * Non-zero means a scene load failed and nothing is loaded: the barrier logs the cause and
     * carries on ticking, so this counter plus a `null` [activeSceneId] is what a host or an
     * agent reads to find out that the world is deliberately empty rather than accidentally so.
     */
    public var failedSwapCount: Long = 0L
        private set

    /**
     * Every scene this manager can resolve by id, in registration order.
     *
     * `internal` until something outside `udea-core` lists scenes — an agent `list_scenes` tool
     * is the obvious first caller, and it does not exist yet.
     */
    internal val registeredSceneIds: Set<SceneId> get() = known.keys

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

    /** Every teardown step, in the order a swap runs them. `internal`: only tests read it. */
    internal val teardownStepNames: List<String>
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
            // Cleared before anything is torn down, and set again only once populate has
            // returned. Between those two points there is genuinely no scene, and saying so is
            // the difference between an empty world and a world that lies about what it holds:
            // `SnapshotTimeTravel` refuses a restore whose recorded scene does not match this,
            // so leaving the old id in place while the old scene is being destroyed would let
            // an arena snapshot be applied to a jungle world.
            activeSceneId = null

            try {
                for (step in beforeClear) step.tearDown(world, ctx)

                ctx.physics.destroyAllBodies()
                // `clearRecycled = true` so a scene loaded twice mints the same entity ids
                // both times: without it the second load draws from the recycled queue the
                // first one filled, and two runs of the same scene stop agreeing on entity
                // identity.
                world.removeAll(clearRecycled = true)
                netIds.reset()

                for (step in afterClear) step.tearDown(world, ctx)

                scene.populate(SceneScope(world, ctx, netIds, scene.seed))
            } catch (failure: Throwable) {
                empty(world, ctx)
                failedSwapCount++
                throw failure
            }

            activeSceneId = scene.id
            requestedSceneId = null
            swappedAtTick = ctx.clock.tick
            swapCount++
            for (listener in listeners) listener(scene.id, ctx.clock.tick)
        }

        /**
         * Empties the world after any part of a swap threw, so that what is left is
         * byte-for-byte a freshly cleared world: no bodies, no entities, no recycled ids, no
         * NetIds — with [activeSceneId] still `null`, which makes every snapshot restore
         * refuse instead of binding a snapshot to the wrong scene.
         *
         * A swap cannot be all-or-nothing in the sense of putting the old scene back — by the
         * time most of these failures are possible the old scene is gone, and a `Scene` is a
         * program rather than a value that can be re-materialised. So the guarantee is the
         * other one available: **either the whole new scene, or no scene at all.** Half a
         * scene is the state every system would have to tolerate and no system is written to,
         * and it persists indefinitely, because the barrier logs a failing action and keeps
         * ticking.
         *
         * The whole swap is covered and not only [Scene.populate]. A `SceneTeardownStep` that
         * throws before the world is cleared is the case that used to escape: the exception
         * left the *outgoing* scene's entities in the world with [activeSceneId] already
         * `null` and [failedSwapCount] not incremented — a world that both lies about what it
         * holds and reports no failure, which is precisely the state the class KDoc claims is
         * impossible.
         *
         * The cause is rethrown by the caller: the barrier logs it with this action's [label]
         * and the tick, which is the only place a stack trace can usefully surface from.
         */
        private fun empty(world: World, ctx: GameContext) {
            ctx.physics.destroyAllBodies()
            world.removeAll(clearRecycled = true)
            netIds.reset()
        }
    }
}
