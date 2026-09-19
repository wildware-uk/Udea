package dev.wildware.udea.physics2d

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.RayHit
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A body on the ground plane moves a 3D entity (issue #247).
 *
 * An entity carrying both a `PhysicsBody` and a `Transform3D` has two poses, and exactly one of them
 * is the truth: a dynamic body's solved pose is copied into `Transform3D.x/y/rotationZ` after every
 * step, and a kinematic or static body goes wherever `Transform3D` says. `z` belongs to the game.
 *
 * Every scene here has no gravity: the plane is the ground seen from above, and nothing falls
 * across it.
 */
class Transform3DPhysicsTest {

    @Test
    fun `a dynamic body pushed into a static wall stops at the wall and its Transform3D shows it`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            // A wall whose near face is at x = 5, and a ball of radius 0.5 rolling at it at 4 units
            // a second: without the wall it would be at x = 8 after two seconds.
            scene.spawn(PhysicsBody(kind = BodyKind.Static, x = 5.5f), Box(halfWidth = 0.5f, halfHeight = 5f))
            val ball = scene.spawn(PhysicsBody(linearX = 4f), Circle(0.5f), Transform3D(z = 1.25f))

            repeat(120) { tick ->
                scene.step()
                val body = scene.bodyOf(ball)
                val transform = scene.transformOf(ball)
                assertEquals(body.x.toRawBits(), transform.x.toRawBits(), "tick $tick: Transform3D.x is the body's x")
                assertEquals(body.y.toRawBits(), transform.y.toRawBits(), "tick $tick: Transform3D.y is the body's y")
                assertEquals(body.angle.toRawBits(), transform.rotationZ.toRawBits(), "tick $tick: rotationZ is the body's angle")
            }

            val transform = scene.transformOf(ball)
            assertTrue(transform.x > 4.4f, "the ball reached the wall, x = ${transform.x}")
            assertTrue(transform.x <= 4.5f + WALL_SLOP, "the wall stopped it at its face, x = ${transform.x}")
            assertEquals(1.25f, transform.z, "z is the game's, and physics left it alone")
        }
    }

    @Test
    fun `a dynamic body with a Transform3D starts where its Transform3D says`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val ball = scene.spawn(PhysicsBody(), Circle(0.5f), Transform3D(x = 3f, y = -2f, rotationZ = 0.25f))

            scene.step()

            val body = scene.bodyOf(ball)
            assertEquals(3f, body.x, "the body was built at Transform3D.x, not at PhysicsBody's default")
            assertEquals(-2f, body.y)
            assertEquals(3f, scene.transformOf(ball).x, "and Transform3D did not jump to the origin")
        }
    }

    @Test
    fun `a kinematic body follows its Transform3D and pushes a dynamic one`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val pusher = scene.spawn(
                PhysicsBody(kind = BodyKind.Kinematic),
                Box(halfWidth = 0.5f, halfHeight = 1f),
                Transform3D(x = 0f),
            )
            val crate = scene.spawn(PhysicsBody(x = 2f), Circle(0.5f), Transform3D(x = 2f))

            // The game moves the pusher by writing its Transform3D, 3 units a second, for two seconds.
            repeat(120) { tick ->
                scene.transformOf(pusher).x = (tick + 1) * PUSH_PER_TICK
                scene.step()
                val target = scene.transformOf(pusher).x
                val reached = scene.bodyOf(pusher).x
                assertTrue(abs(reached - target) < FOLLOW_TOLERANCE, "tick $tick: the pusher's body is at $reached, its Transform3D at $target")
            }

            val pusherX = scene.transformOf(pusher).x
            val crateX = scene.transformOf(crate).x
            assertEquals(6f, pusherX, 1e-4f, "the pusher went where the game put it")
            assertTrue(crateX >= pusherX + 1f - WALL_SLOP, "the crate was pushed ahead of the pusher: crate $crateX, pusher $pusherX")
            assertTrue(crateX > 6.5f, "the crate moved: it started at 2 and is at $crateX")
        }
    }

    @Test
    fun `a kinematic body turns with Transform3D rotationZ`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val door = scene.spawn(PhysicsBody(kind = BodyKind.Kinematic), Box(2f, 0.1f), Transform3D())

            // Swung by the game, 3 radians a second, on past pi, where the angle wraps. Every tick
            // lands near the target and the error does not grow: each tick's velocity is aimed from
            // where the body actually is.
            var worst = 0f
            repeat(80) { tick ->
                val target = (tick + 1) * 0.05f
                scene.transformOf(door).rotationZ = target
                scene.step()
                val error = abs(wrapAngle(scene.bodyOf(door).angle - target))
                worst = maxOf(worst, error)
                assertTrue(error < TURN_TOLERANCE, "tick $tick: the body is at ${scene.bodyOf(door).angle}, rotationZ at $target")
                assertEquals(target, scene.transformOf(door).rotationZ, "tick $tick: rotationZ is the game's, untouched")
            }
            println("kinematic turn: worst error over 80 ticks at 0.05 rad a tick = $worst rad")
        }
    }

    @Test
    fun `a static body sits where its Transform3D says, and moves when it moves`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val wall = scene.spawn(PhysicsBody(kind = BodyKind.Static), Box(0.5f, 5f), Transform3D(x = 5.5f))
            val ball = scene.spawn(PhysicsBody(linearX = 4f), Circle(0.5f), Transform3D())

            repeat(120) { scene.step() }
            assertEquals(5.5f, scene.bodyOf(wall).x, "the wall was built at its Transform3D")
            assertTrue(scene.transformOf(ball).x <= 4.5f + WALL_SLOP, "and it stops the ball there")

            // Moved by its Transform3D - the editor's gizmo, or a level edit.
            scene.transformOf(wall).x = 20f
            scene.step()
            assertEquals(20f, scene.bodyOf(wall).x, "the wall's PhysicsBody moved with its Transform3D")
            // And the solver's body moved too, not only the component: a ray along the ground now
            // meets the wall's near face at 19.5, not at 5.
            val hit = RayHit()
            assertTrue(scene.physics.raycast(6f, 0f, 30f, 0f, hit), "the ray met the wall")
            assertEquals(19.5f, hit.pointX, WALL_SLOP, "the wall's solver body is at its Transform3D")
        }
    }

    @Test
    fun `a body without a Transform3D moves exactly as the solver alone moves it`() {
        // Two runs of one scene. `game = true` steps the whole game, with the Transform3D systems
        // installed; `game = false` drives the same solver the way it was driven before issue #247:
        // reconcile, then step, and nothing else. Every body without a Transform3D must follow the
        // same path to the bit in both, beside a 3D entity that the systems do move.
        fun run(game: Boolean): List<Int> = Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            scene.spawn(PhysicsBody(kind = BodyKind.Static, x = 5.5f), Box(0.5f, 5f))
            val ball = scene.spawn(PhysicsBody(linearX = 4f, angularVelocity = 1f), Circle(0.5f))
            val kinematic = scene.spawn(PhysicsBody(kind = BodyKind.Kinematic, y = 3f, linearX = 1f, angularVelocity = 0.5f), Box(0.5f, 0.5f))
            val bystander = scene.spawn(PhysicsBody(x = -20f, y = -30f, linearY = -1f), Circle(0.5f), Transform3D(x = -20f, y = -30f))
            val trace = ArrayList<Int>()
            repeat(120) {
                if (game) {
                    scene.step()
                } else {
                    scene.physics.reconcile(scene.world, scene.netIds)
                    scene.physics.stepOneTick()
                }
                for (id in listOf(ball, kinematic)) {
                    val body = scene.bodyOf(id)
                    trace += body.x.toRawBits()
                    trace += body.y.toRawBits()
                    trace += body.angle.toRawBits()
                }
            }
            assertNull(scene.transformOrNull(ball), "no Transform3D was added to a body that had none")
            if (game) assertEquals(-32f, scene.transformOf(bystander).y, 1e-3f, "the systems did run: the 3D bystander's Transform3D moved")
            trace
        }

        assertEquals(run(game = false), run(game = true))
    }

    @Test
    fun `a restored run gives bit-identical Transform3D to a run that rebuilt from the same components`() {
        val control = pushRun(Rewind.Control)
        val restored = pushRun(Rewind.Restore)

        assertEquals(control.size, restored.size)
        assertEquals(-1, firstDifference(control, restored), "first re-simulated tick whose Transform3D differs")
    }

    @Test
    fun `without contacts or rotation a restored run gives the original Transform3D bit for bit`() {
        val scene: Box2DScene.() -> List<NetId> = {
            // The kinematic body first, so the game drives it; it runs along y = -20, clear of both.
            listOf(
                spawn(PhysicsBody(kind = BodyKind.Kinematic), Box(0.5f, 0.5f), Transform3D(y = -20f)),
                spawn(PhysicsBody(linearX = 2f, linearY = 1f), Circle(0.5f), Transform3D(z = 0.5f)),
                spawn(PhysicsBody(linearX = -1f), Box(0.5f, 0.5f), Transform3D(x = 40f, y = 10f)),
            )
        }
        val original = transformRun(Rewind.None, scene)
        val restored = transformRun(Rewind.Restore, scene)

        assertEquals(-1, firstDifference(original, restored), "first re-simulated tick whose Transform3D differs")
    }

    // --- runs -----------------------------------------------------------------------------------

    private enum class Rewind { None, Control, Restore }

    /** The pusher-and-crate scene against a wall: contacts from the first second on. */
    private fun pushRun(rewind: Rewind): List<IntArray> = transformRun(rewind) {
        spawn(PhysicsBody(kind = BodyKind.Static), Box(0.5f, 5f), Transform3D(x = 9f))
        listOf(
            spawn(PhysicsBody(kind = BodyKind.Kinematic), Box(0.5f, 1f), Transform3D()),
            spawn(PhysicsBody(), Circle(0.5f), Transform3D(x = 2f, y = 0.3f)),
            spawn(PhysicsBody(), Box(0.4f, 0.4f), Transform3D(x = 3.5f, y = -0.4f)),
        )
    }

    /**
     * Ticks CUT+1 .. TOTAL of one scene, as every watched entity's `Transform3D` in raw bits.
     *
     * The first watched entity, if kinematic, is driven by the game along x at [PUSH_PER_TICK] a
     * tick, the way a game system moves one: `x += step`, from wherever its `Transform3D` is. So a
     * restored run re-drives it from the restored `Transform3D`, and a restore that lost it would
     * drive the body on from where the future left it. [Rewind.Control] rebuilds the solver from
     * the live components at CUT; [Rewind.Restore] captures at CUT, runs on to TOTAL, restores the
     * capture and re-simulates.
     */
    private fun transformRun(rewind: Rewind, build: Box2DScene.() -> List<NetId>): List<IntArray> =
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val watched = scene.build()
            fun tick() {
                val driven = watched.first()
                if (scene.bodyOf(driven).kind == BodyKind.Kinematic) {
                    scene.transformOf(driven).x += PUSH_PER_TICK
                }
                scene.step()
            }
            fun record(): IntArray = watched.flatMap { id ->
                val t = scene.transformOf(id)
                listOf(t.x, t.y, t.z, t.rotationX, t.rotationY, t.rotationZ, t.scaleX, t.scaleY, t.scaleZ).map { it.toRawBits() }
            }.toIntArray()

            repeat(CUT) { tick() }
            when (rewind) {
                Rewind.None -> Unit
                Rewind.Control -> scene.physics.rebuildFrom(scene.world, scene.netIds)
                Rewind.Restore -> {
                    val captured = scene.snapshots.capture()
                    repeat(TOTAL - CUT) { tick() }
                    scene.snapshots.applyNow(captured)
                    assertEquals(CUT.toLong(), scene.game.ctx.tick.value, "the restore put the clock back")
                }
            }
            List(TOTAL - CUT) {
                tick()
                record()
            }
        }

    private fun firstDifference(a: List<IntArray>, b: List<IntArray>): Int =
        a.indices.firstOrNull { !a[it].contentEquals(b[it]) } ?: -1

    private fun Box2DScene.transformOf(id: NetId): Transform3D = with(world) { entityOf(id)[Transform3D] }

    private fun Box2DScene.transformOrNull(id: NetId): Transform3D? = with(world) { entityOf(id).getOrNull(Transform3D) }

    private companion object {
        const val CUT = 60
        const val TOTAL = 180

        /** 3 units a second at 60 ticks a second. */
        const val PUSH_PER_TICK = 0.05f

        /** Box2D keeps touching shapes a linear slop apart or less; its default slop is 0.005. */
        const val WALL_SLOP = 0.01f

        /** How close a kinematic body lands to its target position after one step: float rounding. */
        const val FOLLOW_TOLERANCE = 1e-4f

        /**
         * How close a kinematic body lands to its target angle after one step. Coarser than position,
         * because Box2D's angle-to-rotation and rotation-to-angle functions are approximations (the
         * class KDoc of `Box2DPhysicsWorld`). Two thousandths of a radian is about a ninth of a degree.
         */
        const val TURN_TOLERANCE = 2e-3f

        const val PI_F = kotlin.math.PI.toFloat()

        /** [angle] brought into (-pi, pi]. */
        fun wrapAngle(angle: Float): Float {
            var wrapped = angle
            while (wrapped > PI_F) wrapped -= 2f * PI_F
            while (wrapped <= -PI_F) wrapped += 2f * PI_F
            return wrapped
        }
    }
}
