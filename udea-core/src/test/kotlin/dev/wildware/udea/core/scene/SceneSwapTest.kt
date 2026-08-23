package dev.wildware.udea.core.scene

import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scene swap is atomic: it happens between two ticks, whole, or not at all.
 *
 * The property under test is negative, which is why it needs a probe rather than an assertion
 * at the end: **no tick ever observes a partially built world**. The old arrangement could not
 * say that — `GameScreen` spawned `level.entities` from a `Gdx.app.postRunnable`
 * (`common/UdeaGameManager.kt:191-220`) and guarded the gap with a `started` flag, so "the
 * world is empty right now" was a legitimate state every system had to tolerate.
 *
 * Everything here runs with no `Gdx.app`, no `postRunnable` and no window.
 */
class SceneSwapTest {

    private val arena = SceneId("arena")
    private val jungle = SceneId("jungle")

    private fun host(swapAtTick: Long? = null, sceneA: MarkerScene, sceneB: MarkerScene? = null): GameHost {
        val def = UdeaGameDef(listOf(SceneProbeModule(swapAtTick ?: -1L, sceneB?.id)))
        def.core.scenes.register(sceneA)
        sceneB?.let(def.core.scenes::register)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(sceneA.id)
        return host
    }

    private fun GameHost.probe(): SceneProbeSystem = world.system<SceneProbeSystem>()

    @Test
    fun `a swap requested during tick N lands at the top of tick N+1, fully populated`() {
        val a = MarkerScene(arena, seed = 11L, entityCount = 5)
        val b = MarkerScene(jungle, seed = 22L, entityCount = 9)
        val host = host(swapAtTick = 3L, sceneA = a, sceneB = b)

        host.run(6)

        val samples = host.probe().samples
        // Tick 0 loads arena at the top of the tick, so every tick sees a populated world.
        assertEquals(5, samples.first { it.tick.value == 0L }.entityCount)
        assertEquals(
            5,
            samples.single { it.tick.value == 3L }.entityCount,
            "the tick that asked for the swap finishes on the old scene",
        )
        assertEquals(arena, samples.single { it.tick.value == 3L }.scene)
        assertEquals(
            9,
            samples.single { it.tick.value == 4L }.entityCount,
            "and the very next tick sees the new scene, whole",
        )
        assertEquals(jungle, samples.single { it.tick.value == 4L }.scene)
    }

    @Test
    fun `no tick ever observes a partially populated world`() {
        val a = MarkerScene(arena, seed = 11L, entityCount = 5)
        val b = MarkerScene(jungle, seed = 22L, entityCount = 9)
        val host = host(swapAtTick = 3L, sceneA = a, sceneB = b)

        host.run(10)

        val counts = host.probe().samples.map { it.entityCount }.distinct()
        assertEquals(
            listOf(5, 9),
            counts,
            "entity counts seen across the run were $counts; only the old and new totals are legal",
        )
    }

    @Test
    fun `two runs of the same scene with the same seed produce identical ids and state`() {
        fun run(): List<String> {
            val scene = MarkerScene(arena, seed = 4242L, entityCount = 12)
            val host = host(sceneA = scene)
            host.run(1)
            val netIds = host.ctx[CoreModule.NET_IDS]
            return scene.spawned.map { id ->
                val marker = with(host.world) { checkNotNull(netIds.resolveOrNull(id))[Marker] }
                "$id value=${marker.value} band=${marker.band}"
            }
        }

        val first = run()
        val second = run()

        assertTrue(first.size == 12, "the scene populated: $first")
        assertEquals(first, second, "same scene, same seed, same ids carrying the same fields")
    }

    @Test
    fun `a scene reloaded in the same process mints the same id indices`() {
        val a = MarkerScene(arena, seed = 7L, entityCount = 6)
        val b = MarkerScene(jungle, seed = 8L, entityCount = 3)
        val def = UdeaGameDef(emptyList())
        def.core.scenes.register(a)
        def.core.scenes.register(b)
        val host = GameHost(RenderMode.Headless, def)

        host.ctx.scenes.requestScene(arena)
        host.run(1)
        val firstVisit = a.spawned.toList()

        host.ctx.scenes.requestScene(jungle)
        host.run(1)
        host.ctx.scenes.requestScene(arena)
        host.run(1)

        assertEquals(
            firstVisit.map { it.index },
            a.spawned.map { it.index },
            "recycled ids leaked between scenes; a reloaded scene must lay out the same",
        )
        assertTrue(
            firstVisit.zip(a.spawned).all { (before, after) -> before != after },
            "the generation still moves, so a reference held across the swap reads stale " +
                "rather than silently aliasing the new scene's entity",
        )
    }

    @Test
    fun `the swap is observable - the manager records the scene and the tick it landed on`() {
        val a = MarkerScene(arena, seed = 1L, entityCount = 2)
        val b = MarkerScene(jungle, seed = 2L, entityCount = 2)
        val host = host(swapAtTick = 2L, sceneA = a, sceneB = b)
        val landed = ArrayList<String>()
        def(host).onSwapped { id, tick -> landed += "$id@${tick.value}" }

        host.run(5)

        assertEquals(setOf(arena, jungle), def(host).registeredSceneIds)
        assertEquals(2L, def(host).swapCount, "the initial load and the swap")
        assertEquals(2, a.scopeSpawnCount, "the scope counted what populate created")
        assertEquals(jungle, def(host).activeSceneId)
        assertEquals(3L, def(host).swappedAtTick?.value, "the swap landed at the top of tick 3")
        assertNull(def(host).requestedSceneId, "and the request is no longer pending")
        assertEquals(
            listOf("arena@0", "jungle@3"),
            landed,
            "the callback fires on every landed swap, with the tick it landed on",
        )
    }

    @Test
    fun `requesting an unregistered scene fails at the call, not silently inside the barrier`() {
        val a = MarkerScene(arena, seed = 1L, entityCount = 2)
        val host = host(sceneA = a)

        val failure = runCatching { host.ctx.scenes.requestScene(SceneId("nowhere")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a loud failure, got $failure")
        assertTrue("nowhere" in failure.message.orEmpty(), "${failure.message}")
    }

    @Test
    fun `a scene whose populate throws leaves no scene at all, not half of one`() {
        val a = MarkerScene(arena, seed = 11L, entityCount = 5, withBodies = true)
        val boom = ExplodingScene(jungle, spawnedBeforeFailure = 3)
        val def = UdeaGameDef(listOf(SceneProbeModule(-1L, null)))
        def.core.scenes.register(a)
        def.core.scenes.register(boom)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(arena)
        host.run(1)
        assertEquals(5, host.world.numEntities, "the good scene loaded")

        host.ctx.scenes.requestScene(jungle)
        host.run(1)

        assertEquals(1, boom.attempts, "populate really ran and really threw")
        assertEquals(
            0,
            host.world.numEntities,
            "the three entities the scene managed to spawn must not be left behind: a world " +
                "holding a fragment of a scene is the torn state this class exists to prevent, " +
                "and the barrier logs and keeps ticking, so it would persist for the whole run",
        )
        assertEquals(0, host.ctx.physics.bodyCount, "and the bodies it built went with them")
        assertEquals(0, host.ctx[CoreModule.NET_IDS].liveCount, "and the ids it minted")
        assertNull(
            host.ctx.scenes.activeSceneId,
            "and nothing may claim to be loaded: SnapshotTimeTravel gates a restore on this id, " +
                "so a stale 'arena' here would let an arena snapshot be applied to this world",
        )
        assertEquals(jungle, def(host).requestedSceneId, "what it was trying to become is still readable")
        assertEquals(1L, def(host).failedSwapCount)
        assertEquals(1L, def(host).swapCount, "a swap that did not land is not a swap")
        assertEquals(
            1L,
            host.ctx[SimBarrier.KEY].failedActions,
            "the cause was rethrown so the barrier logs it with the action's label and tick",
        )
    }

    @Test
    fun `a teardown step that throws before the world is cleared also leaves no scene`() {
        // The half of the guarantee that used to escape. `activeSceneId` is cleared before the
        // first teardown step runs, so a step that threw there left the OUTGOING scene's
        // entities in the world under a null scene id, with failedSwapCount still 0 - a world
        // that both lies about what it holds and reports no failure. Only populate was wrapped.
        val a = MarkerScene(arena, seed = 11L, entityCount = 5, withBodies = true)
        val b = MarkerScene(jungle, seed = 12L, entityCount = 4)
        val def = UdeaGameDef(listOf(SceneProbeModule(-1L, null)))
        def.core.scenes.register(a)
        def.core.scenes.register(b)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(arena)
        host.run(1)
        assertEquals(5, host.world.numEntities, "the good scene loaded")
        val exploding = ExplodingTeardownStep(SceneTeardownStage.BeforeWorldCleared)
        def(host).addTeardown(exploding)

        host.ctx.scenes.requestScene(jungle)
        host.run(1)

        assertEquals(1, exploding.attempts, "the step really ran and really threw")
        assertEquals(
            0,
            host.world.numEntities,
            "the outgoing scene's entities are still in the world; a teardown that throws is " +
                "the one path that could leave a populated world under a null scene id",
        )
        assertEquals(0, host.ctx.physics.bodyCount)
        assertEquals(0, host.ctx[CoreModule.NET_IDS].liveCount)
        assertNull(host.ctx.scenes.activeSceneId)
        assertEquals(
            1L,
            def(host).failedSwapCount,
            "a host reads this counter with a null activeSceneId to learn the world is " +
                "deliberately empty; not incrementing it makes the two disagree",
        )
        assertEquals(
            1L,
            host.ctx[SimBarrier.KEY].failedActions,
            "the cause was rethrown so the barrier logs it with the action's label and tick",
        )
    }

    /** A teardown step that throws, standing in for a physics or ring release that fails. */
    private class ExplodingTeardownStep(
        override val stage: SceneTeardownStage,
    ) : SceneTeardownStep {
        override val name: String get() = "exploding teardown"
        var attempts: Int = 0
            private set

        override fun tearDown(
            world: com.github.quillraven.fleks.World,
            ctx: dev.wildware.udea.core.GameContext,
        ) {
            attempts++
            error("the teardown step could not release its resource")
        }
    }

    @Test
    fun `the loop keeps ticking over the empty world and the next scene loads normally`() {
        val a = MarkerScene(arena, seed = 11L, entityCount = 5, withBodies = true)
        val boom = ExplodingScene(jungle, spawnedBeforeFailure = 3)
        val def = UdeaGameDef(listOf(SceneProbeModule(-1L, null)))
        def.core.scenes.register(a)
        def.core.scenes.register(boom)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(arena)
        host.run(1)
        val firstVisit = a.spawned.toList()

        host.ctx.scenes.requestScene(jungle)
        host.run(20)

        val afterFailure = host.world.system<SceneProbeSystem>().samples.filter { it.tick.value >= 1L }
        assertEquals(
            listOf(0),
            afterFailure.map { it.entityCount }.distinct(),
            "every tick after the failure saw an empty world, not a shrinking or growing one",
        )
        assertEquals(listOf(null), afterFailure.map { it.scene }.distinct())

        host.ctx.scenes.requestScene(arena)
        host.run(1)

        assertEquals(5, host.world.numEntities, "the world recovers: a failed load is not fatal")
        assertEquals(arena, def(host).activeSceneId)
        assertEquals(
            firstVisit.map { it.index },
            a.spawned.map { it.index },
            "and the failed swap left the id space clean enough to lay the scene out identically",
        )
    }

    private fun def(host: GameHost): BarrierSceneManager = host.ctx.scenes as BarrierSceneManager

}
