package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.RewindFailure
import dev.wildware.udea.core.loop.RewindResult
import dev.wildware.udea.core.loop.SnapshotKind
import dev.wildware.udea.core.loop.TimeControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The headline feature: pause, step, snapshot, rewind, fast-forward — all in ticks.
 *
 * Spec 1 promises an agent can "pause, single-step, rewind sixty seconds and fast-forward"
 * with no debug code in the game, and the Phase 1 demo steps 200 ticks, rewinds 100 and
 * screenshots again. The old engine could not express any of it: `UdeaGameManager.kt:235` ran
 * `world.update(delta)` with the raw frame delta, so there was no addressable tick at all.
 */
class TimeControlRewindTest {

    @Test
    fun `rewind lands on exactly the tick asked for, at every distance in one to six hundred`() {
        // Every n, not a sample: the interesting ones are the unaligned targets outside the
        // dense window, where the restore comes from a keyframe and the remainder is closed by
        // bare steps. An off-by-one there would be invisible at keyframe-aligned distances.
        val sim = SnapshotWorld()
        sim.spawn(ENTITIES)
        rollForwardTo(sim, Tick(TOTAL.toLong()))

        for (distance in 1..MAX_REWIND) {
            val result = sim.time.rewind(distance)

            val rewound = assertIs<RewindResult.Rewound>(result, "rewind($distance) failed: $result")
            assertEquals(
                Tick(TOTAL - distance.toLong()),
                sim.tick,
                "rewind($distance) landed on the wrong tick",
            )
            assertEquals(sim.tick, rewound.tick)
            assertTrue(rewound.restoredFromTick <= rewound.tick, "restored from the future")
            assertEquals(
                rewound.tick.ticksSince(rewound.restoredFromTick).toInt(),
                rewound.steppedForward,
                "steppedForward must close the gap exactly",
            )
            assertTrue(
                rewound.steppedForward < maxOf(sim.ring.sparseInterval, 1),
                "landing took ${rewound.steppedForward} steps, more than one keyframe spacing",
            )
            assertTrue(sim.time.paused, "a rewind must leave the loop paused")

            rollForwardTo(sim, Tick(TOTAL.toLong()))
        }
    }

    @Test
    fun `the hash after rewinding a hundred ticks and stepping them again matches the original`() {
        val sim = SnapshotWorld()
        sim.spawn(ENTITIES)
        val scratch = sim.service.newSnapshot()
        rollForwardTo(sim, Tick(400))

        val original = sim.hashNow(scratch)

        val result = sim.time.rewind(100)
        assertIs<RewindResult.Rewound>(result)
        assertEquals(Tick(300), sim.tick)
        val atRewind = sim.hashNow(scratch)

        sim.time.step(100)

        assertEquals(Tick(400), sim.tick)
        assertEquals(original, sim.hashNow(scratch), "re-running the same 100 ticks must agree")
        assertTrue(original != atRewind, "the simulation must actually change over 100 ticks")
    }

    @Test
    fun `step advances exactly n ticks and leaves the loop paused`() {
        val sim = SnapshotWorld()
        sim.spawn(4)
        sim.time.resume()

        sim.time.step(7)

        assertEquals(Tick(7), sim.ctx.clock.tick)
        assertTrue(sim.time.paused)

        sim.time.step()
        assertEquals(Tick(8), sim.ctx.clock.tick)
    }

    @Test
    fun `fast-forward issues no render call at all, however many ticks it runs`() {
        // Headless, RenderMode.Headless's defining property: no Presentation is invoked, so no
        // GL context is needed and no frame is drawn. A raised timeScale could not do this — it
        // runs through GameLoop.frame, which renders once per frame and is capped by maxCatchUp.
        val sim = SnapshotWorld()
        sim.spawn(4)
        val view = dev.wildware.udea.core.loop.RecordingPresentation()
        val loop = GameLoop(sim.simulation, view)
        val time = TimeControl(loop, sim.travel)

        time.fastForward(600)

        assertEquals(0, view.renderCount, "fast-forward must bypass Presentation entirely")
        assertEquals(Tick(600), sim.ctx.clock.tick)
        assertTrue(time.paused, "fast-forward leaves the loop paused")
        assertEquals(1f, time.timeScale, "fast-forward must not touch timeScale")
    }

    @Test
    fun `rewinding past the ring returns a typed result and leaves the world untouched`() {
        val sim = SnapshotWorld()
        sim.spawn(6)
        rollForwardTo(sim, Tick(30))
        val scratch = sim.service.newSnapshot()
        val before = sim.hashNow(scratch)

        val result = sim.time.rewind(1_000)

        val failed = assertIs<RewindResult.Failed>(result, "expected a typed failure, got $result")
        assertEquals(RewindFailure.TickOutOfRing, failed.failure)
        assertEquals("tick_out_of_ring", failed.failure.code)
        assertEquals(Tick(30), sim.tick, "the clock must not have moved")
        assertEquals(before, sim.hashNow(scratch), "the world must not have changed")
    }

    @Test
    fun `rewinding into a scene the snapshot does not belong to is a typed scene mismatch`() {
        val sim = SnapshotWorld(scene = dev.wildware.udea.core.SceneId("arena"))
        sim.spawn(4)
        rollForwardTo(sim, Tick(20))
        val scratch = sim.service.newSnapshot()
        val before = sim.hashNow(scratch)

        sim.ctx.scenes.requestScene(dev.wildware.udea.core.SceneId("jungle"))
        (sim.ctx.scenes as dev.wildware.udea.core.fixtures.QueueingSceneManager).applyPending()

        val result = sim.time.rewind(5)

        val failed = assertIs<RewindResult.Failed>(result)
        assertEquals(RewindFailure.SceneMismatch, failed.failure)
        assertEquals("scene_mismatch", failed.failure.code)
        assertTrue(failed.detail.contains("arena") && failed.detail.contains("jungle"), failed.detail)
        assertEquals(before, sim.hashNow(scratch), "a refused rewind must change nothing")
    }

    @Test
    fun `a TimeControl with no ring still pauses and steps, and refuses to time travel`() {
        // The headless loop test's case: TimeControl must be a complete object without a world.
        val sim = SnapshotWorld()
        val bare = TimeControl(GameLoop(sim.simulation))

        bare.step(3)
        assertEquals(Tick(3), sim.ctx.clock.tick)
        assertEquals(emptyList(), bare.listSnapshots())

        val result = bare.rewind(1)
        assertEquals(RewindFailure.NoSnapshotRing, assertIs<RewindResult.Failed>(result).failure)
        assertEquals("no_snapshot_ring", RewindFailure.NoSnapshotRing.code)
        assertFailsWith<IllegalStateException> { bare.snapshot() }
    }

    @Test
    fun `snapshot records the current tick and listSnapshots reports both cadences`() {
        val sim = SnapshotWorld()
        sim.spawn(4)
        rollForwardTo(sim, Tick(400))

        val info = sim.time.snapshot()
        assertEquals(sim.tick, info.tick)
        assertEquals(
            info,
            sim.time.snapshot(),
            "asking twice at one tick must report the same snapshot, not fail",
        )
        assertEquals(SnapshotKind.Dense, info.kind, "the newest snapshot is always dense")
        assertTrue(info.sizeBytes > 0)

        val listed = sim.time.listSnapshots()
        assertTrue(listed.any { it.kind == SnapshotKind.Sparse }, "the sparse window must be visible")
        assertTrue(listed.any { it.kind == SnapshotKind.Dense })
        assertEquals(listed.sortedBy { it.tick.value }, listed, "oldest first")
    }

    @Test
    fun `rewind reports whether the asset graph changed, from the injected history`() {
        val sim = SnapshotWorld()
        sim.spawn(4)
        rollForwardTo(sim, Tick(50))
        val changedAfter = Tick(20)
        val travel = SnapshotTimeTravel(
            sim.service, sim.ring, sim.world, sim.ctx, sim.barrier,
        ) { since -> since < changedAfter }
        val time = TimeControl(GameLoop(sim.simulation), travel)

        val far = assertIs<RewindResult.Rewound>(time.rewind(40))
        assertTrue(far.assetGraphChangedSince, "rewinding past a hot-reload must say so")

        rollForwardTo(sim, Tick(50))
        val near = assertIs<RewindResult.Rewound>(time.rewind(5))
        assertTrue(!near.assetGraphChangedSince)
    }

    @Test
    fun `a negative rewind or fast-forward is refused rather than silently clamped`() {
        val sim = SnapshotWorld()
        assertFailsWith<IllegalArgumentException> { sim.time.rewind(-1) }
        assertFailsWith<IllegalArgumentException> { sim.time.fastForward(-1) }
        assertFailsWith<IllegalArgumentException> { sim.time.step(-1) }
    }

    @Test
    fun `rewinding before the start of the simulation is out of the ring, not a negative tick`() {
        val sim = SnapshotWorld()
        sim.spawn(2)
        rollForwardTo(sim, Tick(10))

        val result = sim.time.rewind(50)

        assertEquals(RewindFailure.TickOutOfRing, assertIs<RewindResult.Failed>(result).failure)
        assertEquals(Tick(10), sim.tick)
    }

    /** Steps to [target], capturing every tick, so the ring holds a full history again. */
    private fun rollForwardTo(sim: SnapshotWorld, target: Tick) {
        val newest = sim.ring.newestTick()
        if (newest == null || newest < sim.tick) sim.travel.captureNow()
        while (sim.tick < target) {
            sim.time.step(1)
            sim.travel.captureNow()
        }
    }

    private companion object {
        const val ENTITIES: Int = 4
        const val TOTAL: Int = 700
        const val MAX_REWIND: Int = 600
    }
}
