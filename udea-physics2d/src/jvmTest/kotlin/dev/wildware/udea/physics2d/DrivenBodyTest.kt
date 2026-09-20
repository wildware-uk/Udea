package dev.wildware.udea.physics2d

import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A game drives a dynamic body by writing `PhysicsBody.linearX/linearY`, and the solver obeys
 * (issue #250).
 *
 * `Physics2DModule`'s own KDoc has always promised that "systems keep reading and writing plain
 * floats on `PhysicsBody`", and for every field but the two velocities that was true. A velocity
 * written onto the component reached the solver **only when the body was first built**: after that
 * `writeBack` overwrote it every tick with whatever the solver had, so a character controller that
 * set its walk velocity each tick stood still. That is the gap [DrivenVelocitySystem] closes, and
 * it is the one thing a character on the ground plane cannot be written without - a kinematic body
 * is not stopped by a static one, and a `Teleport` ignores collision altogether.
 *
 * Every scene here has no gravity: the plane is the ground seen from above.
 */
class DrivenBodyTest {

    @Test
    fun `a dynamic body walks where the game writes its velocity, and turns when the game turns it`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val walker = scene.spawn(PhysicsBody(), Circle(RADIUS), Transform3D())

            // One second east, then one second north. Written onto the component every tick, the
            // way a movement system writes an axis it read from an intent.
            repeat(TICKS_PER_SECOND) {
                scene.bodyOf(walker).linearX = SPEED
                scene.bodyOf(walker).linearY = 0f
                scene.step()
            }
            // Read out as numbers. `transformOf` hands back the live `Transform3D`, so a reference
            // held across the next loop would be re-read at assert time and the comparison below
            // would be one value against itself - a test that cannot fail.
            val eastX = scene.transformOf(walker).x
            val eastY = scene.transformOf(walker).y
            assertTrue(eastX > SPEED * NEARLY, "a second at $SPEED/s east put it at $eastX")
            assertTrue(eastY in -TOLERANCE..TOLERANCE, "it did not drift north: $eastY")

            repeat(TICKS_PER_SECOND) {
                scene.bodyOf(walker).linearX = 0f
                scene.bodyOf(walker).linearY = SPEED
                scene.step()
            }
            val northX = scene.transformOf(walker).x
            val northY = scene.transformOf(walker).y
            assertTrue(northY > SPEED * NEARLY, "a second at $SPEED/s north put it at $northY")
            assertEquals(eastX, northX, TOLERANCE, "it stopped moving east when the game stopped asking")
        }
    }

    @Test
    fun `a driven body is stopped by a static one, and its Transform3D stops with it`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            // A rock whose near face is at x = 5, and a walker pushed at it for two seconds: with
            // nothing in the way it would be at x = 8.
            scene.spawn(PhysicsBody(kind = BodyKind.Static, x = 5.5f), Box(halfWidth = 0.5f, halfHeight = 5f))
            val walker = scene.spawn(PhysicsBody(), Circle(RADIUS), Transform3D())

            repeat(2 * TICKS_PER_SECOND) {
                scene.bodyOf(walker).linearX = SPEED
                scene.step()
            }

            val stopped = scene.transformOf(walker)
            assertTrue(stopped.x > 4f, "the walker reached the rock, x = ${stopped.x}")
            assertTrue(stopped.x <= 5f - RADIUS + CONTACT_SLOP, "the rock stopped it at its face, x = ${stopped.x}")
        }
    }

    @Test
    fun `a scene nobody drives pushes no velocity at all`() {
        // The known negative for the two tests above: the push is skipped, bit for bit, whenever
        // the component still holds exactly what the last write-back put there. That is what makes
        // adding this system a no-op for every scene that does not use it - including the
        // determinism and rewind scenes, which is the claim that would otherwise have to be taken
        // on trust.
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            scene.spawn(PhysicsBody(linearX = SPEED), Circle(RADIUS))
            scene.spawn(PhysicsBody(kind = BodyKind.Static, x = 5.5f), Box(halfWidth = 0.5f, halfHeight = 5f))

            repeat(TICKS_PER_SECOND) { scene.step() }

            assertEquals(0L, scene.physics.velocityPushes, "nothing wrote a velocity, so nothing was pushed")
        }
    }

    @Test
    fun `a push happens once per tick the game writes a velocity, and not otherwise`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val walker = scene.spawn(PhysicsBody(), Circle(RADIUS))
            // One tick first, so the body exists and is at rest: a body's *first* velocity reaches
            // Box2D through the body definition it is built from, not through a push.
            scene.step()
            assertEquals(0L, scene.physics.velocityPushes, "building a body is not a push")

            // Three ticks asking for the same velocity: the first differs from the solver's zero,
            // and the two after it are the value the write-back has already put back.
            repeat(3) {
                scene.bodyOf(walker).linearX = SPEED
                scene.step()
            }

            assertEquals(1L, scene.physics.velocityPushes, "the unchanged velocity was pushed again")
        }
    }

    private companion object {
        const val TICKS_PER_SECOND = 60
        const val SPEED = 3f
        const val RADIUS = 0.5f

        /** Box2D's soft step is a little short of the exact integral over the first tick. */
        const val NEARLY = 0.97f
        const val TOLERANCE = 1e-3f

        /** Box2D lets a contact settle a few millimetres deep. */
        const val CONTACT_SLOP = 0.02f
    }
}
