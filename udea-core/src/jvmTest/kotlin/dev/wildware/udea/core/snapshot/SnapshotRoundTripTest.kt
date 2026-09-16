package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.QueueingSceneManager
import dev.wildware.udea.core.fixtures.RecordingPhysicsWorld
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.replication.MaskOps
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.rng.DefaultRngService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Capture, mutate, restore — and prove the world came back to exactly what was captured.
 *
 * Everything here goes through the generated `Replicator` surface. There is no reflective
 * path to fall back to and spec 3.1 says there never will be, which is what makes this test
 * the whole story rather than one of two.
 */
class SnapshotRoundTripTest {

    @Test
    fun `restore returns every Net and Sim field to its captured value`() {
        val sim = SnapshotWorld()
        val ids = sim.spawn(ENTITIES)
        repeat(20) { sim.step() }

        val captured = sim.service.capture()

        // Mutate every entity, through both the @Net and the @Sim half.
        repeat(40) { sim.step() }
        for (id in ids) {
            val entity = checkNotNull(sim.netIds.resolveOrNull(id))
            with(sim.world) {
                entity[Movement].position.set(-1f, -2f)
                entity[Vitals].damageDealt = 999L
                entity[Link].squad = "squad.mutated"
            }
        }

        sim.service.applyNow(captured)

        val after = sim.service.capture()
        val diff = FieldDiff()
        assertTrue(
            captured.fields.diffInto(after.fields, diff),
            "restore left ${diff.size} field(s) differing: " +
                (0 until diff.size).joinToString {
                    "${diff.netIdAt(it)}/${diff.typeIdAt(it)}/${diff.fieldAt(it)}"
                },
        )
        assertEquals(captured.tick, sim.tick)
    }

    @Test
    fun `a Sim-only field rewinds even though it is outside netMask`() {
        // Jungle timers and bot blackboards: they must rewind and must never reach a client.
        val sim = SnapshotWorld()
        val ids = sim.spawn(4)
        val entity = checkNotNull(sim.netIds.resolveOrNull(ids[0]))
        with(sim.world) { entity[Vitals] }.damageDealt = 7L

        val captured = sim.service.capture()
        with(sim.world) { entity[Vitals] }.damageDealt = 4_242L
        sim.service.applyNow(captured)

        assertEquals(7L, with(sim.world) { entity[Vitals] }.damageDealt)
        assertTrue(
            !MaskOps.test(
                VitalsReplicator.netMask,
                VitalsReplicator.DAMAGE_DEALT,
            ),
            "damageDealt must be outside netMask, or this test proves nothing about @Sim",
        )
    }

    @Test
    fun `apply is in place, so a component reference survives a restore`() {
        val sim = SnapshotWorld()
        val ids = sim.spawn(4)
        val entity = checkNotNull(sim.netIds.resolveOrNull(ids[0]))
        val movement = with(sim.world) { entity[Movement] }
        val position = movement.position

        val captured = sim.service.capture()
        movement.position.set(500f, 500f)
        sim.service.applyNow(captured)

        // The same Vec2 instance, mutated back: this is what keeps rendering and physics
        // references valid across a rewind instead of re-pointing every one of them.
        assertSame(movement, with(sim.world) { entity[Movement] })
        assertSame(position, with(sim.world) { entity[Movement] }.position)
        assertNotEquals(500f, position.x)
    }

    @Test
    fun `restoring an older snapshot removes entities spawned since`() {
        val sim = SnapshotWorld()
        sim.spawn(3)
        val captured = sim.service.capture()

        val latecomer = sim.spawnMovementOnly(x = 12f)
        assertTrue(sim.netIds.resolveOrNull(latecomer) != null)

        sim.service.applyNow(captured)

        assertEquals(null, sim.netIds.resolveOrNull(latecomer), "the latecomer should be gone")
        assertEquals(3, sim.netIds.liveCount)
        assertEquals(3, sim.world.numEntities)
    }

    @Test
    fun `restoring re-creates a destroyed entity with the same NetId and the same generation`() {
        val sim = SnapshotWorld()
        val ids = sim.spawn(5)
        val doomed = ids[2]
        val captured = sim.service.capture()

        sim.destroy(doomed)
        assertEquals(null, sim.netIds.resolveOrNull(doomed))

        sim.service.applyNow(captured)

        val revived = sim.netIds.resolveOrNull(doomed)
        assertTrue(revived != null, "the destroyed entity must come back under its own id")
        assertEquals(doomed, sim.netIds.netIdOf(revived))
        assertEquals(doomed.generation, sim.netIds.netIdOf(revived).generation)
    }

    @Test
    fun `a reference to an entity that was already dead still reads stale after a restore`() {
        // The generation counter's whole job. A restore that reset generations to zero would
        // make this stale reference resolve to the entity now occupying the slot.
        val sim = SnapshotWorld()
        val ids = sim.spawn(4)
        val recycled = ids[1]
        sim.destroy(recycled)
        val replacement = sim.spawnMovementOnly(x = 3f)
        assertEquals(recycled.index, replacement.index, "the index must have been recycled")

        val captured = sim.service.capture()
        repeat(3) { sim.step() }
        sim.service.applyNow(captured)

        assertEquals(null, sim.netIds.resolveOrNull(recycled), "a stale id must not alias")
        assertTrue(sim.netIds.resolveOrNull(replacement) != null)
    }

    @Test
    fun `the next ids handed out after a restore are the ones the first run handed out`() {
        val sim = SnapshotWorld()
        sim.spawn(4)
        val captured = sim.service.capture()

        val firstRun = List(3) { sim.spawnMovementOnly(x = it.toFloat()) }
        sim.service.applyNow(captured)
        val secondRun = List(3) { sim.spawnMovementOnly(x = it.toFloat()) }

        assertEquals(
            firstRun,
            secondRun,
            "the id allocator must rewind, or a re-run diverges on identity alone",
        )
    }

    @Test
    fun `after a restore the next hundred draws from each stream match the original run`() {
        val sim = SnapshotWorld()
        sim.spawn(8)
        repeat(10) { sim.step() }

        val captured = sim.service.capture()
        val original = RngStream.entries.associateWith { stream ->
            List(100) { sim.ctx.rng.nextLong(stream) }
        }

        repeat(25) { sim.step() }
        sim.service.applyNow(captured)

        for (stream in RngStream.entries) {
            assertEquals(
                original.getValue(stream),
                List(100) { sim.ctx.rng.nextLong(stream) },
                "stream $stream diverged after restore",
            )
        }
    }

    @Test
    fun `a component added since the snapshot is removed by the restore`() {
        val sim = SnapshotWorld()
        val id = sim.spawnMovementOnly(x = 1f)
        val captured = sim.service.capture()

        val entity = checkNotNull(sim.netIds.resolveOrNull(id))
        with(sim.world) { entity.configure { it += Vitals(health = 10f) } }
        assertTrue(with(sim.world) { entity.getOrNull(Vitals) } != null)

        sim.service.applyNow(captured)

        assertEquals(
            null,
            with(sim.world) { entity.getOrNull(Vitals) },
            "a component the snapshot does not have must be removed, not left behind",
        )
    }

    @Test
    fun `a component removed since the snapshot is put back`() {
        val sim = SnapshotWorld()
        val ids = sim.spawn(3)
        val entity = checkNotNull(sim.netIds.resolveOrNull(ids[0]))
        with(sim.world) { entity[Vitals] }.shieldCharges = 3
        val captured = sim.service.capture()

        with(sim.world) { entity.configure { it -= Vitals } }
        sim.service.applyNow(captured)

        assertEquals(3, with(sim.world) { entity[Vitals] }.shieldCharges)
    }

    @Test
    fun `restore rebuilds physics from components rather than restoring solver state`() {
        // Spec 3.4: Box2D is never snapshot state. The seam is a single call, and this pins
        // that the restore makes it — the sibling physics issue then makes it do work.
        val sim = SnapshotWorld()
        sim.spawn(2)
        val captured = sim.service.capture()
        val physics = sim.ctx.physics as RecordingPhysicsWorld
        val before = physics.rebuildCount

        sim.service.applyNow(captured)

        assertEquals(before + 1, physics.rebuildCount)
    }

    @Test
    fun `restoring into a different scene is refused and the world is untouched`() {
        val sim = SnapshotWorld(scene = SceneId("arena"))
        sim.spawn(3)
        val captured = sim.service.capture()
        sim.spawnMovementOnly(x = 1f)

        sim.ctx.scenes.requestScene(SceneId("jungle"))
        (sim.ctx.scenes as QueueingSceneManager).applyPending()

        assertFailsWith<SceneMismatchException> { sim.service.applyNow(captured) }
        assertEquals(4, sim.netIds.liveCount, "a refused restore must change nothing")
    }

    @Test
    fun `a service built over an RngService that cannot be captured is refused at construction`() {
        // Silent here would mean a rewind that leaves the random streams running, and a
        // divergence on the first tick that draws a number with nothing pointing at the cause.
        val ctx = testGameContext(seed = 1L)
        val world = configureWorld { injectables { gameContext(ctx) } }
        val failure = assertFailsWith<IllegalArgumentException> {
            SnapshotService(TestComponents.registry(), world, ctx, NetIdIndex(16))
        }
        assertTrue(failure.message!!.contains("CapturableRng"), failure.message!!)
    }

    @Test
    fun `capture walks entities in ascending NetId order whatever order they were spawned in`() {
        val sim = SnapshotWorld()
        val ids = sim.spawn(6)
        sim.destroy(ids[1])
        sim.destroy(ids[4])
        // These reuse the freed indices, so spawn order and index order now disagree.
        val late = List(2) { sim.spawnMovementOnly(x = it.toFloat()) }

        val captured = sim.service.capture()
        val rows = (0 until captured.fields.rowCount).map { captured.fields.netIdAt(it) }

        assertEquals(rows.sortedBy { it.index }, rows, "capture order must be ascending NetId")
        assertEquals((ids.toSet() - setOf(ids[1], ids[4])) + late, rows.toSet())
    }

    @Test
    fun `an excluded subsystem is told once per restore, after the world is whole`() {
        val sim = SnapshotWorld()
        sim.spawn(2)
        val particles = RecordingParticles()
        val service = SnapshotService(
            sim.registry, sim.world, sim.ctx, sim.netIds, listOf(particles),
        )
        val captured = service.capture()

        service.applyNow(captured)
        service.applyNow(captured)

        assertEquals(2, particles.clearCount)
        assertEquals(SnapshotExclusion.Particles, particles.exclusion)
    }

    @Test
    fun `restoring an empty slot is refused rather than emptying the world`() {
        val sim = SnapshotWorld()
        sim.spawn(2)
        val empty = sim.service.newSnapshot()

        assertFailsWith<IllegalArgumentException> { sim.service.applyNow(empty) }
        assertEquals(2, sim.netIds.liveCount)
    }

    @Test
    fun `a snapshot carries the tick it was captured at and restoring moves the clock back`() {
        val sim = SnapshotWorld()
        sim.spawn(2)
        repeat(17) { sim.step() }
        val captured = sim.service.capture()
        assertEquals(Tick(17), captured.tick)

        repeat(9) { sim.step() }
        assertEquals(Tick(26), sim.tick)

        sim.service.applyNow(captured)
        assertEquals(Tick(17), sim.tick)
    }

    private companion object {
        const val ENTITIES: Int = 500
    }
}

/** A stand-in for the particle system: counts the restores it was told about. */
internal class RecordingParticles : ExcludedSubsystem {
    override val exclusion: SnapshotExclusion get() = SnapshotExclusion.Particles

    var clearCount: Int = 0
        private set

    override fun onRestored() {
        clearCount++
    }
}
