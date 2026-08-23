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

    @Test
    fun `a restore that throws is a typed failure, not a rewind that says it landed`() {
        // SimBarrier.drain catches, logs and carries on past a throwing BarrierAction by
        // design -- that is what stops one bad tool call stranding the rest of the queue. A
        // restore submitted through it therefore fails *silently* unless someone asks, and the
        // caller here is the one telling an agent which tick the world is at.
        val sim = SnapshotWorld()
        sim.spawn(4)
        val exploding = ExplodingSubsystem()
        val service = SnapshotService(sim.registry, sim.world, sim.ctx, sim.netIds, listOf(exploding))
        val travel = SnapshotTimeTravel(service, sim.ring, sim.world, sim.ctx, sim.barrier)
        val time = TimeControl(sim.loop, travel)
        repeat(10) { sim.time.step(1); travel.captureNow() }
        exploding.armed = true

        val result = time.rewind(5)

        val failed = assertIs<RewindResult.Failed>(
            result,
            "a restore whose apply threw was reported as $result",
        )
        assertEquals(RewindFailure.RestoreFailed, failed.failure)
        assertEquals("restore_failed", failed.failure.code)
        assertTrue(
            failed.detail.contains("undefined state"),
            "the detail must say the world is not at the tick that was asked for: ${failed.detail}",
        )
        assertEquals(1, exploding.attempts, "the restore must actually have been attempted")
        assertEquals(
            1L,
            sim.barrier.failedActions,
            "the barrier must still have logged and counted it; recording is not swallowing",
        )
    }

    @Test
    fun `a rewind is not derailed by an unrelated action failing in the same drain`() {
        // The other half of the same contract. `barrier.failedActions` counts every action,
        // so keying the refusal off it would turn a perfectly good rewind into a
        // `restore_failed` because some tool call queued behind it threw.
        val sim = SnapshotWorld()
        sim.spawn(4)
        repeat(10) { sim.time.step(1); sim.travel.captureNow() }
        sim.barrier.submit(ThrowingAction())

        val result = sim.time.rewind(5)

        assertIs<RewindResult.Rewound>(result, "an unrelated failure refused a good rewind: $result")
        assertEquals(Tick(5), sim.tick)
        assertEquals(1L, sim.barrier.failedActions)
    }

    @Test
    fun `the loop is paused before the restore is drained, not after`() {
        // `SnapshotTimeTravel` forces a `SimBarrier` drain from inside `rewind` rather than
        // waiting for the next `Simulation.step`, and its KDoc justifies that with "TimeControl
        // pauses the loop first, so the drain happens at a tick boundary with no system
        // running". Pausing on the way out -- which is what `loop.stepTicks` does at the *end*
        // of a rewind -- would leave that guarantee resting on the unrelated accident that tool
        // calls happen to arrive between frames.
        val sim = SnapshotWorld()
        sim.spawn(2)
        repeat(10) { sim.time.step(1); sim.travel.captureNow() }
        sim.time.resume()
        assertTrue(!sim.time.paused, "the loop must be running, or this test proves nothing")
        val probe = PauseProbe(sim.loop)
        sim.barrier.submit(probe)

        val result = sim.time.rewind(5)

        assertIs<RewindResult.Rewound>(result)
        assertEquals(
            true,
            probe.pausedWhenDrained,
            "the restore was drained with the loop still running; a drain from a running loop " +
                "can land in the middle of a tick and tear the world",
        )
    }

    @Test
    fun `a refused rewind answers the question without halting a running game`() {
        // The pause exists to make the forced drain safe, and a refused rewind never reaches
        // one. An agent probing the ring with a distance it does not cover would otherwise
        // stop the game as a side effect of asking, and nothing in the answer would say so.
        val sim = SnapshotWorld()
        sim.spawn(2)
        // Five ticks with no keyframe, then five with: the ring's oldest snapshot is t6, so a
        // target between t1 and t5 is refused by the ring itself rather than by the pre-check.
        repeat(5) { sim.time.step(1) }
        repeat(5) { sim.time.step(1); sim.travel.captureNow() }
        sim.time.resume()
        assertTrue(!sim.time.paused, "the loop must be running, or this test proves nothing")

        val tooFar = assertIs<RewindResult.Failed>(sim.time.rewind(500))

        assertEquals(RewindFailure.TickOutOfRing, tooFar.failure)
        assertTrue(
            !sim.time.paused,
            "a rewind that landed nowhere left the game paused; probing the ring must not be " +
                "a destructive act",
        )

        // The other refusal path: raised inside the ring, so it runs with the pause already
        // taken and has to hand it back.
        sim.time.resume()
        val insideTheRing = assertIs<RewindResult.Failed>(sim.time.rewind(8))
        assertEquals(RewindFailure.TickOutOfRing, insideTheRing.failure)
        assertTrue(!sim.time.paused, "the refusal from inside the ring left the loop paused")
    }

    @Test
    fun `a restore that threw leaves the loop paused, because the world is half applied`() {
        // The deliberate exception to the rule above: `RestoreFailed` means apply threw part
        // way through, so resuming would run systems over half-restored components.
        val sim = SnapshotWorld()
        sim.spawn(4)
        val exploding = ExplodingSubsystem()
        val service = SnapshotService(sim.registry, sim.world, sim.ctx, sim.netIds, listOf(exploding))
        val travel = SnapshotTimeTravel(service, sim.ring, sim.world, sim.ctx, sim.barrier)
        val time = TimeControl(sim.loop, travel)
        repeat(10) { sim.time.step(1); travel.captureNow() }
        time.resume()
        assertTrue(!time.paused, "the loop must be running, or this test proves nothing")
        exploding.armed = true

        val result = assertIs<RewindResult.Failed>(time.rewind(5))

        assertEquals(RewindFailure.RestoreFailed, result.failure)
        assertTrue(time.paused, "a half-applied world must not be left running")
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

    /**
     * An excluded subsystem that throws when [armed], standing in for the real ones.
     *
     * `udea-render`'s particle and camera subsystems, `PhysicsWorld.rebuildFrom` and
     * `NetIdIndex.bind`'s `require` are all reachable from inside a restore and all of them
     * can throw; this is the one of those a headless core test can inject.
     */
    private class ExplodingSubsystem : ExcludedSubsystem {
        override val exclusion: SnapshotExclusion get() = SnapshotExclusion.Particles

        var armed: Boolean = false
        var attempts: Int = 0
            private set

        override fun onRestored() {
            attempts++
            if (armed) error("the particle system could not be cleared")
        }
    }

    /** Records whether the loop was paused at the moment the barrier drained it. */
    private class PauseProbe(private val loop: GameLoop) : dev.wildware.udea.core.loop.BarrierAction {
        override val label: String get() = "read loop.paused"

        var pausedWhenDrained: Boolean? = null
            private set

        override fun apply(
            world: com.github.quillraven.fleks.World,
            ctx: dev.wildware.udea.core.GameContext,
        ) {
            pausedWhenDrained = loop.paused
        }
    }

    /** Some other queued mutation that throws, drained alongside a restore. */
    private class ThrowingAction : dev.wildware.udea.core.loop.BarrierAction {
        override val label: String get() = "an unrelated tool call"

        override fun apply(
            world: com.github.quillraven.fleks.World,
            ctx: dev.wildware.udea.core.GameContext,
        ): Unit = error("unrelated")
    }

    private companion object {
        const val ENTITIES: Int = 4
        const val TOTAL: Int = 700
        const val MAX_REWIND: Int = 600
    }
}
