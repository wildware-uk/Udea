package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.RewindFailure
import dev.wildware.udea.core.loop.RewindResult
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.scene.Scene
import dev.wildware.udea.core.scene.SceneScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Time travel in an **assembled game**, not in the snapshot package's own harness.
 *
 * This is the gap the whole time-travel spine had at the end of Phase 0. The ring, the store,
 * the degrade policy, `DivergenceReport` and `TimeControl`'s rewind arithmetic were all measured
 * and mutation-tested through `SnapshotWorld`, which hand-rolls its own world and calls
 * `captureNow` itself. Nothing a `GameHost` built ever captured, so in a real game the ring was
 * empty and every rewind answered `tick_out_of_ring` — the phase's demo was met as a test and
 * not as a demo.
 *
 * So everything here goes through the production path and nothing else: a [UdeaGameDef] with a
 * `timeTravel` factory, a [GameHost] over it, `host.time` for pause/step/rewind, and
 * `WorldSimulation.step` for the capture. `SnapshotWorld` does not appear.
 */
class LoopDrivenCaptureTest {

    @Test
    fun `a host with a ring captures on its own cadence while it runs`() {
        val host = arenaHost()
        host.run(TICKS)

        val listed = host.time.listSnapshots()
        assertTrue(
            listed.isNotEmpty(),
            "nothing captured over $TICKS ticks: the loop is not driving TimeTravel.captureIfDue",
        )
        assertTrue(
            listed.all { it.tick.value % CADENCE == 0L },
            "every captured tick must land on the configured cadence, got ${listed.map { it.tick.value }}",
        )
        assertEquals(listed.sortedBy { it.tick.value }, listed, "oldest first")
        assertEquals(
            Tick(TICKS.toLong()),
            listed.last().tick,
            "the newest snapshot must be the tick the host has just reached",
        )
    }

    @Test
    fun `rewinding a running host lands exactly and reproduces the hash stream`() {
        val host = arenaHost()
        host.run(TICKS)

        val hasher = hasherFor(host)
        val scratch = hasher.newSnapshot()

        // The forward run whose hashes the rewound run has to reproduce.
        val baseline = LongArray(REPLAY_TICKS) {
            host.run(1)
            hasher.captureInto(scratch)
            WorldHasher.hash(scratch.fields)
        }

        val rewound = assertIs<RewindResult.Rewound>(host.time.rewind(REPLAY_TICKS))
        assertEquals(Tick(TICKS.toLong()), rewound.tick, "a rewind must land on the tick it was asked for")
        assertEquals(
            rewound.steppedForward.toLong(),
            rewound.tick.ticksSince(rewound.restoredFromTick),
            "the keyframe plus the steps run must be the tick reported",
        )
        assertTrue(host.time.paused, "rewind leaves the loop paused")

        val replay = LongArray(REPLAY_TICKS) {
            host.run(1)
            hasher.captureInto(scratch)
            WorldHasher.hash(scratch.fields)
        }

        val diverged = DivergenceReport.firstDivergingTick(baseline, replay, Tick(TICKS + 1L))
        assertNull(diverged, "the re-run diverged at $diverged; that tick is where to look")
    }

    @Test
    fun `a rewind between keyframes steps forward through the loop to land exactly`() {
        val host = arenaHost()
        host.run(TICKS)

        // 29 ticks back from 600 is 571, which the cadence never captured: the ring's nearest
        // slot at or before it is 570, and the last tick has to be run forward again. That
        // step-forward goes through GameLoop.stepTicks -> WorldSimulation.step, so it captures
        // like any other tick, which is the part that only an assembled host exercises.
        val rewound = assertIs<RewindResult.Rewound>(host.time.rewind(29))
        assertEquals(Tick(571L), rewound.tick)
        assertEquals(Tick(570L), rewound.restoredFromTick, "570 is the newest captured tick at or before 571")
        assertEquals(1, rewound.steppedForward, "one bare step closes the gap to 571")
    }

    @Test
    fun `the rewound host keeps capturing, so a second rewind works too`() {
        val host = arenaHost()
        host.run(TICKS)
        host.time.rewind(30)
        host.time.resume()
        host.run(30)

        val second = assertIs<RewindResult.Rewound>(host.time.rewind(30))
        assertEquals(Tick((TICKS - 30).toLong()), second.tick)
        assertTrue(
            host.time.listSnapshots().isNotEmpty(),
            "the ring must have refilled while the host stepped forward out of the first rewind",
        )
    }

    @Test
    fun `tick_out_of_ring is answered only beyond the retention window`() {
        val host = arenaHost()
        host.run(TICKS)

        val oldest = host.time.listSnapshots().first().tick
        val insideWindow = (Tick(TICKS.toLong()).ticksSince(oldest)).toInt()
        assertIs<RewindResult.Rewound>(
            host.time.rewind(insideWindow),
            "a rewind to the oldest snapshot the ring still holds must succeed",
        )

        host.time.resume()
        host.run(TICKS)
        val tooFar = assertIs<RewindResult.Failed>(host.time.rewind(TICKS + insideWindow))
        assertEquals(RewindFailure.TickOutOfRing, tooFar.failure)
    }

    @Test
    fun `a host built with no time travel factory allocates no ring and says so`() {
        val host = GameHost(RenderMode.Headless, arenaDefinition(travel = false))
        host.run(TICKS)

        assertNull(
            host.game.simulation.travel,
            "a definition with no timeTravel factory must leave the simulation without a ring",
        )
        assertEquals(emptyList(), host.time.listSnapshots())
        val refused = assertIs<RewindResult.Failed>(host.time.rewind(10))
        assertEquals(
            RewindFailure.NoSnapshotRing,
            refused.failure,
            "a dedicated server with no observer must not pay for a ring, and must say why it " +
                "cannot rewind rather than reporting an empty one",
        )
    }

    @Test
    fun `a zero cadence stops the loop capturing without taking the ring away`() {
        val host = arenaHost(config = EngineConfig(seed = SEED, snapshotIntervalTicks = 0))
        host.run(TICKS)

        assertEquals(
            emptyList(),
            host.time.listSnapshots(),
            "snapshotIntervalTicks = 0 must turn the loop's cadence off",
        )
        // The ring is still there: an agent may place keyframes by hand.
        val placed = host.time.snapshot()
        assertEquals(Tick(TICKS.toLong()), placed.tick)
        assertEquals(1, host.time.listSnapshots().size)
    }

    @Test
    fun `an agent snapshot of a tick the loop already captured is idempotent`() {
        val host = arenaHost()
        host.run(TICKS)
        host.time.pause()

        // 600 is a multiple of the cadence, so the loop has already captured it. An agent has
        // no way to know that, and the ring refuses a commit that does not advance the history
        // — so without the held-already guard this is an exception for a reason the caller
        // could neither predict nor act on.
        val held = host.time.listSnapshots().size
        val info = host.time.snapshot()

        assertEquals(Tick(TICKS.toLong()), info.tick)
        assertEquals(held, host.time.listSnapshots().size, "a second capture of one tick adds no slot")
    }

    @Test
    fun `the cadence the loop runs at is the one EngineConfig names`() {
        val host = arenaHost(config = EngineConfig(seed = SEED, snapshotIntervalTicks = 10))
        host.run(TICKS)

        val ticks = host.time.listSnapshots().map { it.tick.value }
        assertTrue(ticks.isNotEmpty(), "a cadence of 10 must still capture over $TICKS ticks")
        assertTrue(
            ticks.all { it % 10L == 0L },
            "the loop captured off its configured cadence: $ticks",
        )
    }

    // --- fixtures --------------------------------------------------------------------------

    private fun arenaHost(config: EngineConfig = EngineConfig(seed = SEED)): GameHost =
        GameHost(RenderMode.Headless, arenaDefinition(travel = true, config = config))

    /**
     * The definition under test: two real systems, a real scene, and optionally a real ring.
     *
     * The scene is queued on `def.core`'s barrier before the host exists, which is the sequence
     * a real host uses — the kernel's services are built by `CoreModule`'s constructor precisely
     * so scenes can be registered before the world does.
     */
    private fun arenaDefinition(
        travel: Boolean,
        config: EngineConfig = EngineConfig(seed = SEED),
    ): UdeaGameDef {
        val definition = UdeaGameDef(
            modules = listOf(ArenaModule()),
            config = config,
            timeTravel = if (travel) {
                snapshotTimeTravel(TestComponents.registry(), RING)
            } else {
                null
            },
        )
        definition.core.scenes.load(ArenaScene)
        return definition
    }

    /** A second capture path, used only to hash. Reads the host's own world and id index. */
    private fun hasherFor(host: GameHost): SnapshotService {
        val netIds: NetIdIndex = host.ctx[CoreModule.NET_IDS]
        return SnapshotService(TestComponents.registry(), host.world, host.ctx, netIds)
    }

    private class ArenaModule : UdeaModule {
        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Movement, { MovementSystem() })
            registry.add(SimPhase.Gameplay, { VitalsSystem() })
        }
    }

    /** [ENTITIES] entities with well-spread state, so a hash has something to be sensitive to. */
    private object ArenaScene : Scene {
        override val id: SceneId = SceneId("arena")
        override val seed: Long = 9L

        override fun populate(scope: SceneScope) {
            repeat(ENTITIES) { index ->
                scope.spawn { entity ->
                    val movement = Movement()
                    movement.position.x = index * 0.5f
                    movement.position.y = index * -0.25f
                    movement.velocity.x = 1f + (index % 7) * 0.125f
                    movement.velocity.y = -1f + (index % 5) * 0.25f
                    entity += movement
                    entity += Vitals(
                        health = 100f - (index % 13),
                        shieldCharges = index % 4,
                        invulnerable = index % 11 == 0,
                    )
                    entity += Link()
                }
            }
        }
    }

    private companion object {
        const val SEED: Long = 20_260_823L
        const val ENTITIES: Int = 40

        /** Six hundred ticks, the figure the issue's acceptance names. */
        const val TICKS: Int = 600

        /**
         * Far enough back to cross out of the dense window into the sparse one, and still
         * inside [RING]'s retention so the rewind is expected to land.
         */
        const val REPLAY_TICKS: Int = 41

        val CADENCE: Long = EngineConfig.DEFAULT_SNAPSHOT_INTERVAL_TICKS.toLong()

        /**
         * A deliberately short retention, so "beyond the window" is reachable in 600 ticks
         * rather than in 4000. The windows themselves are `SnapshotRing`'s to test; what is
         * under test here is that a rewind past whatever they are answers `tick_out_of_ring`
         * and a rewind inside them does not.
         */
        val RING: RingConfig = RingConfig(
            denseTicks = 30,
            sparseWindowTicks = 90,
            sparseInterval = 6,
        )
    }
}
