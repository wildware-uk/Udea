package dev.wildware.udea.core.scene

import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
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

    private fun def(host: GameHost): BarrierSceneManager = host.ctx.scenes as BarrierSceneManager

}
