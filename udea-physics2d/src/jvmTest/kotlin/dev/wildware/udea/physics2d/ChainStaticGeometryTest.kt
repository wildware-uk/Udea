package dev.wildware.udea.physics2d

import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Chain
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `Chain` is the one physics component a snapshot does not carry, so it must be static.
 *
 * Both halves of that sentence are tested. A static chain survives a rewind correctly - the
 * restored run matches a rebuilt one bit for bit, with bodies rolling down the ramp - because
 * nothing about it changed. And a chain that does change is refused at the tick it changes,
 * naming the entity, instead of becoming a desync the next time somebody rewinds.
 */
class ChainStaticGeometryTest {

    private fun Box2DScene.ramp() = spawn(
        PhysicsBody(kind = BodyKind.Static),
        Chain(floatArrayOf(-10f, 8f, -4f, 3f, 0f, 1f, 6f, 0f)),
    )

    private fun Box2DScene.rollers() {
        for (index in 0 until 5) spawn(PhysicsBody(x = -9f + index * 0.8f, y = 10f + index), Circle(0.3f))
    }

    @Test
    fun `a static chain rewinds correctly with bodies rolling down it`() {
        val control = Box2DScene().use { scene ->
            scene.ramp()
            scene.rollers()
            repeat(CUT) { scene.step() }
            scene.physics.rebuildFrom(scene.world, scene.netIds)
            scene.run(TICKS)
        }
        val restored = Box2DScene().use { scene ->
            scene.ramp()
            scene.rollers()
            repeat(CUT) { scene.step() }
            val captured = scene.snapshots.capture()
            repeat(TICKS) { scene.step() }
            // The rollers really did use the ramp: they reached its lower end.
            assertTrue(scene.contactBegins >= 5, "only ${scene.contactBegins} contacts; the rollers missed the ramp")
            scene.snapshots.applyNow(captured)
            scene.run(TICKS)
        }

        assertEquals(-1, firstDifference(control, restored), "first re-simulated tick whose hashes differ")
    }

    @Test
    fun `editing a chain's vertices is refused at the next tick, naming the entity`() {
        Box2DScene().use { scene ->
            val ramp = scene.ramp()
            scene.step()

            with(scene.world) { scene.entityOf(ramp)[Chain].vertices = floatArrayOf(0f, 0f, 5f, 5f) }
            val failure = assertFailsWith<StaticGeometryChangedException> { scene.step() }

            assertEquals(ramp, failure.owner)
            assertTrue("changed after its body was built" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `mutating a chain's array in place is refused too`() {
        Box2DScene().use { scene ->
            val ramp = scene.ramp()
            scene.step()

            // The setter check cannot see this one; the copy taken when the body was built can.
            with(scene.world) { scene.entityOf(ramp)[Chain].vertices[1] = 9f }

            assertFailsWith<StaticGeometryChangedException> { scene.step() }
        }
    }

    @Test
    fun `adding a chain to an entity that already has a body is refused`() {
        Box2DScene().use { scene ->
            val id = scene.spawn(PhysicsBody(kind = BodyKind.Static), Circle(1f))
            scene.step()

            with(scene.world) { scene.entityOf(id).configure { it += Chain(floatArrayOf(0f, 0f, 1f, 1f)) } }
            val failure = assertFailsWith<StaticGeometryChangedException> { scene.step() }

            assertTrue("added after its body was built" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `destroying a chain entity mid-scene is refused, and scene teardown is not`() {
        Box2DScene().use { scene ->
            val ramp = scene.ramp()
            scene.step()
            scene.world -= scene.entityOf(ramp)
            scene.netIds.free(ramp)

            assertFailsWith<StaticGeometryChangedException> { scene.step() }
        }
        Box2DScene().use { scene ->
            val ramp = scene.ramp()
            scene.step()
            // What `BarrierSceneManager` does on a scene swap: bodies first, then entities.
            scene.physics.destroyAllBodies()
            scene.world -= scene.entityOf(ramp)
            scene.netIds.free(ramp)

            scene.step()
            assertEquals(0, scene.physics.bodyCount)
        }
    }

    private companion object {
        const val CUT = 90
        const val TICKS = 240
    }
}
