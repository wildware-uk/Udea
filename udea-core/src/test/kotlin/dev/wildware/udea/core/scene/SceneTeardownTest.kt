package dev.wildware.udea.core.scene

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Log
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.RingConfig
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.TestComponents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every piece of state keyed to the outgoing scene is brought to a defined state, in one
 * barrier action, in a stated order.
 *
 * Each effect is asserted **independently**, because a teardown that happens to clear three
 * things because one call cascades is a teardown that stops clearing two of them the moment
 * that call changes.
 */
class SceneTeardownTest {

    private val arena = SceneId("arena")
    private val jungle = SceneId("jungle")

    /** Records when it ran relative to the world being emptied. */
    private class OrderProbe(
        override val name: String,
        override val stage: SceneTeardownStage,
        private val log: MutableList<String>,
    ) : SceneTeardownStep {
        override fun tearDown(world: World, ctx: GameContext) {
            log += "$name(entities=${world.numEntities})"
        }
    }

    private class Setup(vararg scenes: MarkerScene) {
        val def = UdeaGameDef(emptyList())
        val host: GameHost

        init {
            scenes.forEach(def.core.scenes::register)
            host = GameHost(RenderMode.Headless, def)
        }

        fun load(id: SceneId) {
            host.ctx.scenes.requestScene(id)
            host.run(1)
        }
    }

    @Test
    fun `a swap destroys the outgoing scene's physics bodies`() {
        val setup = Setup(
            MarkerScene(arena, seed = 1L, entityCount = 5, withBodies = true),
            MarkerScene(jungle, seed = 2L, entityCount = 2, withBodies = false),
        )

        setup.load(arena)
        assertEquals(5, setup.host.ctx.physics.bodyCount, "the scene built its bodies")
        setup.host.ctx.createStaleBody()
        assertEquals(6, setup.host.ctx.physics.bodyCount)

        setup.load(jungle)

        assertEquals(
            0,
            setup.host.ctx.physics.bodyCount,
            "bodies from the old scene survived the swap; nothing would ever destroy them",
        )
    }

    @Test
    fun `a swap empties the cue queue`() {
        val setup = Setup(
            MarkerScene(arena, seed = 1L, entityCount = 2),
            MarkerScene(jungle, seed = 2L, entityCount = 2),
        )
        val cues = setup.def.core.cues

        setup.load(arena)
        repeat(7) { cues.emit(Cue(CueId(it), setup.host.tick)) }
        assertEquals(7, cues.size, "the outgoing scene left cues behind")

        setup.load(jungle)

        assertEquals(0, cues.size, "the previous level's cues would have played over the new one")
    }

    @Test
    fun `a swap clears the snapshot ring`() {
        val setup = Setup(
            MarkerScene(arena, seed = 1L, entityCount = 2),
            MarkerScene(jungle, seed = 2L, entityCount = 2),
        )
        // The ring's teardown is contributed by whoever owns the ring: the kernel has no
        // ComponentRegistry to build one with, which is exactly why addTeardown exists.
        val ring = SnapshotRing(TestComponents.registry(), RingConfig(), Log.NoOp)
        setup.def.core.scenes.addTeardown(ClearSnapshotRing(ring))

        setup.load(arena)
        for (tick in 1..3) {
            ring.commit(ring.acquire().also { it.tick = Tick(tick.toLong()); it.isFilled = true })
        }
        assertEquals(3, ring.size, "the ring holds snapshots of the outgoing scene")

        setup.load(jungle)

        assertEquals(
            0,
            ring.size,
            "every held snapshot belongs to a scene that no longer exists; a restore from one " +
                "would be refused, so keeping them only offers an agent impossible rewinds",
        )
    }

    @Test
    fun `teardown steps run on the side of the world clear their stage names`() {
        val log = ArrayList<String>()
        val setup = Setup(
            MarkerScene(arena, seed = 1L, entityCount = 4),
            MarkerScene(jungle, seed = 2L, entityCount = 6),
        )
        // Added in the wrong order on purpose: the stage decides when each runs, not this list.
        setup.def.core.scenes.addTeardown(OrderProbe("after", SceneTeardownStage.AfterWorldCleared, log))
        setup.def.core.scenes.addTeardown(OrderProbe("before", SceneTeardownStage.BeforeWorldCleared, log))

        assertEquals(
            listOf("drain cue queue", "before", "after"),
            setup.def.core.scenes.teardownStepNames,
            "the reported order is the order they run: BeforeWorldCleared first",
        )

        setup.load(arena)
        log.clear()
        setup.load(jungle)

        assertEquals(
            listOf("before(entities=4)", "after(entities=0)"),
            log,
            "a BeforeWorldCleared step must still see the outgoing entities and an " +
                "AfterWorldCleared step must not; the order they were added must not decide it",
        )
    }

    @Test
    fun `a teardown step may not be added while a swap is running`() {
        val setup = Setup(MarkerScene(arena, seed = 1L, entityCount = 1))
        val scenes = setup.def.core.scenes
        var failure: Throwable? = null

        scenes.onSwapped { _, _ ->
            failure = runCatching {
                scenes.addTeardown(OrderProbe("late", SceneTeardownStage.AfterWorldCleared, ArrayList()))
            }.exceptionOrNull()
        }

        setup.load(arena)

        assertTrue(
            failure is IllegalStateException,
            "half a teardown sequence is exactly the torn state the barrier exists to prevent, " +
                "so this must fail loudly; got $failure",
        )
    }

    @Test
    fun `the whole teardown is one barrier action, so no tick sees a torn world`() {
        val setup = Setup(
            MarkerScene(arena, seed = 1L, entityCount = 4),
            MarkerScene(jungle, seed = 2L, entityCount = 6),
        )
        val barrier = setup.host.ctx[SimBarrier.KEY]

        setup.load(arena)
        setup.load(jungle)

        assertEquals(1, barrier.drainedThisTick, "teardown and populate are one action, not several")
        assertEquals(0, barrier.pendingCount())
        assertEquals(0L, barrier.failedActions, "nothing in the swap threw and got swallowed")
    }

    @Test
    fun `registering two different scenes under one id fails`() {
        val setup = Setup(MarkerScene(arena, seed = 1L, entityCount = 1))
        val clash = MarkerScene(arena, seed = 99L, entityCount = 9)

        val failure = assertFailsWith<IllegalArgumentException> {
            setup.def.core.scenes.register(clash)
        }
        assertTrue("arena" in failure.message.orEmpty(), "${failure.message}")
    }
}
