package dev.wildware.udea.physics2d

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.physics2d.Box2DScene.Companion.buildStandardScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a rewind restores, measured rather than claimed.
 *
 * Three runs of one scene, each [TOTAL] ticks:
 *
 * - **original**: runs straight through.
 * - **control**: runs to [CUT], calls `rebuildFrom` on the live world - no snapshot, no restore,
 *   just "throw the solver away and rebuild it from the components as they stand" - and runs on.
 * - **restored**: runs to [CUT], captures, runs on to [TOTAL], restores the capture with the real
 *   `SnapshotService.applyNow` (components applied, then `rebuildFrom`), and re-simulates.
 *
 * The guarantee is **restored == control at every tick**: a restore reproduces exactly what the
 * components say, bit for bit. **restored == original** holds too, but only for a scene with no
 * contacts and no rotation, because a component does not carry contact warm-starting, resting time
 * or Box2D's exact rotation (the class KDoc of `Box2DPhysicsWorld`). The last two tests measure
 * where a stacking scene and a spinning scene part from their original runs, so the limitation is
 * a measured number rather than a word.
 */
class Box2DRewindTest {

    @Test
    fun `a restored run hashes identically to a run that rebuilt from the same components`() {
        val control = controlRun { buildStandardScene() }
        val restored = restoredRun { buildStandardScene() }

        assertEquals(-1, firstDifference(control, restored), "first re-simulated tick whose hashes differ")
    }

    @Test
    fun `without contacts or rotation a restored run replays the original exactly`() {
        val scene: Box2DScene.() -> Unit = {
            // Falling and flying, far apart, never touching and never turning.
            for (index in 0 until 12) {
                spawn(
                    PhysicsBody(x = index * 10f, y = 50f + index, linearX = index - 6f, linearY = index * 0.5f),
                    if (index % 2 == 0) Box(0.5f, 0.25f) else Circle(0.3f),
                )
            }
        }
        val original = originalRun(scene)
        val restored = restoredRun(scene)

        assertEquals(-1, firstDifference(original, restored), "first re-simulated tick whose hashes differ")
    }

    @Test
    fun `a restore brings back a destroyed body and removes one spawned after the capture`() {
        Box2DScene().use { scene ->
            scene.buildStandardScene()
            repeat(CUT) { scene.step() }
            val captured = scene.snapshots.capture()
            val bodiesAtCut = scene.physics.bodyCount
            val hashAtCut = scene.hash()

            // One body destroyed and one spawned after the capture: the restore must undo both.
            val doomed = scene.netIds.resolveOrNull(firstDynamic(scene))!!
            scene.world -= doomed
            scene.spawn(PhysicsBody(x = 3f, y = 20f), Circle(0.5f))
            repeat(30) { scene.step() }
            assertEquals(bodiesAtCut, scene.physics.bodyCount, "one destroyed and one spawned")

            scene.snapshots.applyNow(captured)

            assertEquals(bodiesAtCut, scene.physics.bodyCount, "the rebuild made one body per restored entity")
            assertEquals(hashAtCut, scene.hash(), "the restored world is the captured one")
            scene.step()
            assertEquals(bodiesAtCut, scene.physics.bodyCount, "the reconcile after a restore adds and drops nothing")
        }
    }

    @Test
    fun `how far a contact-heavy scene departs from the unrewound run`() {
        // Not a guarantee but a measurement, asserted only in the direction that keeps the
        // limitation honest: if a restored stacking scene ever matched the original exactly, the
        // KDoc saying it does not would be wrong, and this would say so.
        val original = originalRun { buildStandardScene() }
        val restored = restoredRun { buildStandardScene() }
        val departs = firstDifference(original, restored)

        println("stacking scene: restored run departs from the original at re-simulated tick $departs of ${TOTAL - CUT}")
        assertTrue(departs >= 0, "the stacking scene replayed the original exactly; update Box2DPhysicsWorld's KDoc")
    }

    @Test
    fun `a spinning scene departs too, because an angle does not round-trip through Box2D`() {
        // No contacts at all - only rotation. `PhysicsBody.angle` is Box2D's rotation read back
        // through its own approximate atan2, and a rebuild turns it into a rotation again through
        // its approximate cosine and sine, so the restored bodies start a hair off their original
        // orientation. Measured, like the stacking scene, so the KDoc cannot outlive the fact.
        val scene: Box2DScene.() -> Unit = {
            for (index in 0 until 12) {
                spawn(PhysicsBody(x = index * 10f, y = 50f, angularVelocity = 0.7f + index), Box(0.5f, 0.25f))
            }
        }
        val departs = firstDifference(originalRun(scene), restoredRun(scene))

        println("spinning scene: restored run departs from the original at re-simulated tick $departs of ${TOTAL - CUT}")
        assertTrue(departs >= 0, "the spinning scene replayed the original exactly; update Box2DPhysicsWorld's KDoc")
    }

    /** The hashes of ticks CUT+1 .. TOTAL of a run that never rewinds. */
    private fun originalRun(build: Box2DScene.() -> Unit): LongArray = Box2DScene().use { scene ->
        scene.build()
        repeat(CUT) { scene.step() }
        scene.run(TOTAL - CUT)
    }

    /** The same ticks, from a run that rebuilt the solver from its components at [CUT]. */
    private fun controlRun(build: Box2DScene.() -> Unit): LongArray = Box2DScene().use { scene ->
        scene.build()
        repeat(CUT) { scene.step() }
        scene.physics.rebuildFrom(scene.world, scene.netIds)
        scene.run(TOTAL - CUT)
    }

    /** The same ticks, re-simulated after restoring a capture taken at [CUT]. */
    private fun restoredRun(build: Box2DScene.() -> Unit): LongArray = Box2DScene().use { scene ->
        scene.build()
        repeat(CUT) { scene.step() }
        val captured = scene.snapshots.capture()
        repeat(TOTAL - CUT) { scene.step() }
        val rebuildsBefore = scene.physics.rebuildCount
        scene.snapshots.applyNow(captured)
        assertEquals(rebuildsBefore + 1, scene.physics.rebuildCount, "the restore rebuilt the solver once")
        scene.run(TOTAL - CUT)
    }

    /** The lowest `NetId` carrying a dynamic body. */
    private fun firstDynamic(scene: Box2DScene): NetId {
        var found = NetId.NONE
        scene.netIds.forEachLive { netId, entity ->
            if (found.isNone && with(scene.world) { entity[PhysicsBody] }.kind == BodyKind.Dynamic) found = netId
        }
        check(!found.isNone) { "the scene has no dynamic body" }
        return found
    }

    private companion object {
        /** Two seconds in: the stack is still settling and circles are still landing. */
        const val CUT = 120
        const val TOTAL = 360
    }
}
