package dev.wildware.udea.physics2d

import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.physics2d.Box2DScene.Companion.buildStandardScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two runs of the same headless scene agree on `WorldHasher.hash` at every tick.
 *
 * The hash is the determinism gate (spec 7), and it now sees physics: `PhysicsBody` and the shapes
 * are captured through their generated replicators, and the solver writes its result back into
 * `PhysicsBody` every tick. So a solver that drifted between two runs - a step length read off a
 * clock, a body created in a different order, a second worker thread racing the first - shows up
 * here as the first tick whose hashes differ.
 */
class Box2DDeterminismTest {

    @Test
    fun `two runs of the same scene hash identically at every tick`() {
        val first = Box2DScene().use { scene ->
            scene.buildStandardScene()
            val before = scene.dynamicHeights()
            val hashes = scene.run(TICKS)
            val after = scene.dynamicHeights()
            val maxDrop = before.indices.maxOf { before[it] - after[it] }
            SceneOutcome(hashes, scene.contactBegins, maxDrop, scene.asleep())
        }
        val second = Box2DScene().use { scene ->
            scene.buildStandardScene()
            scene.run(TICKS)
        }

        // The scene must actually have done something, or two identical hash streams prove
        // nothing: bodies fell, touched, and some came to rest.
        assertTrue(first.contactBegins > 20, "only ${first.contactBegins} contacts began; the scene is not colliding")
        assertTrue(first.maxDrop > 5f, "the highest body fell only ${first.maxDrop}; the scene is not falling")
        assertTrue(first.asleep > 0, "no body came to rest in $TICKS ticks; the scene is not stacking")

        assertEquals(-1, firstDifference(first.hashes, second), "first tick whose hashes differ")
    }

    @Test
    fun `the hash sees a one-ulp difference in a single body`() {
        // The control for the test above. If physics state were missing from the hash, every run
        // would agree however different its bodies were, and "identical at every tick" would be
        // true of any two runs at all. So nudge one circle's starting x by the smallest float step
        // and require the streams to part.
        val nudged = Box2DScene().use { scene ->
            scene.buildStandardScene()
            scene.spawn(PhysicsBody(kind = BodyKind.Dynamic, x = Math.nextUp(30f), y = 3f), Circle(0.4f))
            scene.run(TICKS)
        }
        val control = Box2DScene().use { scene ->
            scene.buildStandardScene()
            scene.spawn(PhysicsBody(kind = BodyKind.Dynamic, x = 30f, y = 3f), Circle(0.4f))
            scene.run(TICKS)
        }

        assertEquals(0, firstDifference(control, nudged), "one ulp of x must change the hash from the first tick")
    }

    private class SceneOutcome(val hashes: LongArray, val contactBegins: Int, val maxDrop: Float, val asleep: Int)

    /** Every dynamic body's height, in the family's order, which is stable within one run. */
    private fun Box2DScene.dynamicHeights(): FloatArray {
        val heights = ArrayList<Float>()
        world.family { all(PhysicsBody) }.forEach { entity ->
            val body = entity[PhysicsBody]
            if (body.kind == BodyKind.Dynamic) heights += body.y
        }
        return heights.toFloatArray()
    }

    private fun Box2DScene.asleep(): Int {
        var count = 0
        world.family { all(PhysicsBody) }.forEach { entity ->
            val body = entity[PhysicsBody]
            if (body.kind == BodyKind.Dynamic && !body.awake) count++
        }
        return count
    }

    private companion object {
        /** Five seconds at 60Hz: long enough for the stack to settle and bodies to sleep. */
        const val TICKS = 300
    }
}

/** The first index at which [a] and [b] differ, or -1 when they are identical. */
internal fun firstDifference(a: LongArray, b: LongArray): Int {
    require(a.size == b.size) { "streams of different lengths: ${a.size} and ${b.size}" }
    for (index in a.indices) if (a[index] != b[index]) return index
    return -1
}
