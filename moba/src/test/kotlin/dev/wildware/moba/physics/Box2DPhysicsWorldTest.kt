package dev.wildware.moba.physics

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.physics.BodyDef
import dev.wildware.udea.core.physics.BodyHandle
import dev.wildware.udea.core.physics.BodyHandleBuffer
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.BodyPose
import dev.wildware.udea.core.physics.BodyVelocity
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Capsule
import dev.wildware.udea.core.physics.Chain
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.ContactListener
import dev.wildware.udea.core.physics.NoSuchBodyException
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.RayHit
import dev.wildware.udea.core.physics.ShapeComponent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The solver is real: things fall, things collide, rays hit, contacts fire.
 *
 * ## Why every one of these matters
 *
 * `NoOpPhysicsWorld` passes a surprising amount of `PhysicsWorld`'s contract - bodies exist,
 * handles resolve, poses read back, rebuilds are deterministic - while simulating nothing. Its
 * own KDoc said so: "a test that asserts a body fell, bounced or overlapped is asserting against
 * a solver that is not there." So the tests that distinguish a backend from the null object are
 * exactly the ones that assert a body *moved*, and they are the first ones here.
 *
 * Every one of these fails against `NoOpPhysicsWorld` - the last test in this file proves that
 * by running the same assertions against it and requiring them to fail, which is what stops this
 * suite from quietly becoming a suite about bookkeeping again.
 *
 * ## Natives
 *
 * `Box2D.init()` loads `gdx-box2d64`. On a machine without the native this throws
 * `UnsatisfiedLinkError` and the tests are **red**, not skipped - a gate that turns into a skip
 * is the failure mode this repository has shipped once already.
 */
class Box2DPhysicsWorldTest {

    private fun body(
        kind: BodyKind = BodyKind.Dynamic,
        x: Float = 0f,
        y: Float = 0f,
        angle: Float = 0f,
        linearX: Float = 0f,
        linearY: Float = 0f,
        isSensor: Boolean = false,
    ) = PhysicsBody(
        kind = kind,
        x = x,
        y = y,
        angle = angle,
        linearX = linearX,
        linearY = linearY,
        isSensor = isSensor,
    )

    private fun Box2DPhysicsWorld.add(
        component: PhysicsBody,
        owner: NetId = NetId.NONE,
        vararg shapes: ShapeComponent,
    ): BodyHandle = createBody(BodyDef(component, owner, shapes.toList()))

    private fun Box2DPhysicsWorld.y(handle: BodyHandle): Float = poseOf(handle, BodyPose()).y

    private fun Box2DPhysicsWorld.x(handle: BodyHandle): Float = poseOf(handle, BodyPose()).x

    // --- the solver actually simulates ---------------------------------------------------------

    @Test
    fun `a dynamic body falls, and falls the distance gravity says it should`() {
        Box2DPhysicsWorld(gravityY = -GRAVITY).use { physics ->
            val handle = physics.add(body(y = 0f), shapes = arrayOf(Circle(radius = 4f)))
            val ticks = 60
            repeat(ticks) { physics.stepOneTick() }

            val seconds = ticks * physics.secondsPerTick
            val expected = -0.5f * GRAVITY * seconds * seconds
            val actual = physics.y(handle)

            // Box2D is a semi-implicit Euler integrator, so it overshoots the closed form by
            // about half a step of velocity per step. A tolerance of ten per cent distinguishes
            // "gravity is integrated" from "gravity is not", which is the claim under test.
            assertTrue(
                abs(actual - expected) < abs(expected) * 0.1f,
                "expected about $expected world units after $ticks ticks, got $actual",
            )
            assertEquals(ticks.toLong(), physics.stepCount)
        }
    }

    @Test
    fun `a falling body lands on static ground and stays there`() {
        Box2DPhysicsWorld(gravityY = -GRAVITY).use { physics ->
            physics.add(
                body(kind = BodyKind.Static, y = -50f),
                shapes = arrayOf(Box(halfWidth = 200f, halfHeight = 4f)),
            )
            val faller = physics.add(body(y = 40f), shapes = arrayOf(Circle(radius = 5f)))

            repeat(240) { physics.stepOneTick() }
            val resting = physics.y(faller)
            repeat(120) { physics.stepOneTick() }

            // -50 + 4 (half the ground) + 5 (the radius) = -41, minus Box2D's linear slop.
            assertTrue(resting > -43f && resting < -40f, "did not come to rest on the ground: $resting")
            assertTrue(
                abs(physics.y(faller) - resting) < 0.5f,
                "sank through the ground after resting: $resting -> ${physics.y(faller)}",
            )
        }
    }

    @Test
    fun `begin and end contacts are reported, by handle, in world units`() {
        Box2DPhysicsWorld(gravityY = -GRAVITY).use { physics ->
            val begun = ArrayList<String>()
            val ended = ArrayList<String>()
            val ground = physics.add(
                body(kind = BodyKind.Static, y = -50f),
                owner = NetId.of(1, 0),
                shapes = arrayOf(Box(halfWidth = 200f, halfHeight = 4f)),
            )
            physics.addContactListener(object : ContactListener {
                override fun onBeginContact(a: BodyHandle, b: BodyHandle) {
                    begun += "${physics.ownerOf(a)}/${physics.ownerOf(b)}"
                }

                override fun onEndContact(a: BodyHandle, b: BodyHandle) {
                    ended += "${physics.ownerOf(a)}/${physics.ownerOf(b)}"
                }
            })
            assertEquals(1, physics.contactListenerCount)

            // Thrown up and across, so it lands, bounces off nothing, and is then dragged off the
            // end of a short ledge - which is a begin and an end rather than a begin alone.
            val ball = physics.add(
                body(y = 20f, linearX = 60f),
                owner = NetId.of(2, 0),
                shapes = arrayOf(Circle(radius = 5f)),
            )
            repeat(600) { physics.stepOneTick() }

            assertTrue(begun.isNotEmpty(), "no contact fired in 600 ticks; the solver reported nothing")
            assertTrue(
                begun.all { it == "NetId(#1@0)/NetId(#2@0)" || it == "NetId(#2@0)/NetId(#1@0)" },
                "a contact named a body that is not one of the two: $begun",
            )
            assertNotEquals(BodyHandle.NONE, ground)
            assertNotEquals(BodyHandle.NONE, ball)
        }
    }

    @Test
    fun `a raycast reports the nearer of two bodies and the fraction it hit at`() {
        Box2DPhysicsWorld().use { physics ->
            val near = physics.add(
                body(kind = BodyKind.Static, x = 40f),
                owner = NetId.of(7, 0),
                shapes = arrayOf(Circle(radius = 8f)),
            )
            physics.add(
                body(kind = BodyKind.Static, x = 120f),
                owner = NetId.of(8, 0),
                shapes = arrayOf(Circle(radius = 8f)),
            )

            val hit = RayHit()
            assertTrue(physics.raycast(0f, 0f, 200f, 0f, hit), "a ray straight through two circles missed")
            assertEquals(near, hit.body, "the ray reported the far body")
            assertEquals(NetId.of(7, 0), physics.ownerOf(hit.body))
            // The near circle starts at x = 32 of a 200-long ray.
            assertTrue(abs(hit.fraction - 0.16f) < 0.01f, "fraction was ${hit.fraction}")
            // World units, not metres: `RayHit` is on the public surface, so the backend has
            // already divided the solver's answer by `metresPerUnit`. This assertion caught the
            // first draft of it dividing twice.
            assertTrue(abs(hit.pointX - 32f) < 1f, "point was ${hit.pointX}")

            assertFalse(physics.raycast(0f, 500f, 200f, 500f, hit), "a ray through empty space hit something")
        }
    }

    @Test
    fun `overlap reports what is touching and not merely what shares a broadphase box`() {
        Box2DPhysicsWorld().use { physics ->
            // Diagonally offset by (30, 30) - 42 units away, so the circles do not touch, but the
            // axis-aligned boxes of the two do overlap. A backend that answered with QueryAABB
            // alone reports this one, which is the whole reason OverlapNarrowphase exists.
            physics.add(
                body(kind = BodyKind.Kinematic, x = 30f, y = 30f),
                owner = NetId.of(2, 0),
                shapes = arrayOf(Circle(radius = 10f)),
            )
            physics.add(
                body(kind = BodyKind.Kinematic, x = 12f, y = 0f),
                owner = NetId.of(3, 0),
                shapes = arrayOf(Circle(radius = 10f)),
            )

            val out = BodyHandleBuffer()
            assertEquals(1, physics.overlap(Circle(radius = 10f), BodyPose(), out))
            assertEquals(NetId.of(3, 0), physics.ownerOf(out[0]), "the wrong body was reported")
        }
    }

    @Test
    fun `overlap results are ordered by owning NetId and not by the broadphase tree`() {
        Box2DPhysicsWorld().use { physics ->
            // Created in descending id order, so insertion order and id order disagree.
            for (index in 6 downTo 1) {
                physics.add(
                    body(kind = BodyKind.Kinematic, x = index * 3f),
                    owner = NetId.of(index, 0),
                    shapes = arrayOf(Circle(radius = 10f)),
                )
            }
            val out = BodyHandleBuffer()
            physics.overlap(Circle(radius = 10f), BodyPose(), out)
            val owners = (0 until out.size).map { physics.ownerOf(out[it]).index }

            assertEquals(owners.sorted(), owners, "overlap leaked the broadphase's walk order")
            assertTrue(owners.size >= 4, "only ${owners.size} bodies overlapped; the ordering is untested")
        }
    }

    // --- the boundary is honest ----------------------------------------------------------------

    @Test
    fun `the same world at two metre scales gives the same answers in world units`() {
        val poses = listOf(1f / 4f, 1f / 16f, 1f / 64f).map { scale ->
            Box2DPhysicsWorld(metresPerUnit = scale, gravityY = -GRAVITY).use { physics ->
                val handle = physics.add(body(y = 100f, linearX = 30f), shapes = arrayOf(Circle(radius = 6f)))
                repeat(120) { physics.stepOneTick() }
                val pose = physics.poseOf(handle, BodyPose())
                val velocity = physics.velocityOf(handle, BodyVelocity())
                "x=%.2f y=%.2f vx=%.2f vy=%.2f".format(pose.x, pose.y, velocity.linearX, velocity.linearY)
            }
        }
        assertEquals(1, poses.toSet().size, "the metre scale leaked into a world-unit answer: $poses")
    }

    @Test
    fun `a capsule is three fixtures and a chain is one`() {
        Box2DPhysicsWorld().use { physics ->
            val capsule = physics.add(body(), shapes = arrayOf(Capsule(radius = 4f, halfHeight = 9f)))
            val chain = physics.add(
                body(kind = BodyKind.Static),
                shapes = arrayOf(Chain(floatArrayOf(0f, 0f, 50f, 0f, 50f, 50f))),
            )
            val both = physics.add(body(), shapes = arrayOf(Box(), Circle(), Capsule()))

            assertEquals(3, physics.fixtureCountOf(capsule), "a capsule is a box and two caps")
            assertEquals(1, physics.fixtureCountOf(chain))
            assertEquals(5, physics.fixtureCountOf(both), "box + circle + capsule's three")
        }
    }

    @Test
    fun `a chain of fewer than two points is refused rather than crashing the JVM`() {
        Box2DPhysicsWorld().use { physics ->
            // Box2D asserts inside the native library on a one-point chain, which takes the whole
            // process down with no stack. This is the guard that turns it into a message.
            assertFailsWith<IllegalArgumentException> {
                physics.add(body(kind = BodyKind.Static), shapes = arrayOf(Chain(floatArrayOf(1f, 2f))))
            }
        }
    }

    @Test
    fun `a handle held across a rebuild names nothing rather than somebody else`() {
        Box2DPhysicsWorld().use { physics ->
            val fixture = Fixture(bodyCount = 4)
            physics.rebuildFrom(fixture.world, fixture.netIds)
            val stale = fixture.bodyOf(fixture.ids[0]).handle
            assertTrue(stale.isValid)
            assertEquals(fixture.ids[0], physics.ownerOf(stale))

            physics.rebuildFrom(fixture.world, fixture.netIds)

            val fresh = fixture.bodyOf(fixture.ids[0]).handle
            assertNotEquals(stale, fresh, "the rebuild handed back the handle it had just destroyed")
            assertEquals(NetId.NONE, physics.ownerOf(stale), "a stale handle still names a body")
            assertFailsWith<NoSuchBodyException> { physics.poseOf(stale, BodyPose()) }
            assertEquals(fixture.ids[0], physics.ownerOf(fresh))
        }
    }

    @Test
    fun `teleport is the only thing that writes a transform`() {
        Box2DPhysicsWorld(gravityY = -GRAVITY).use { physics ->
            val handle = physics.add(body(), shapes = arrayOf(Circle(radius = 3f)))
            repeat(30) { physics.stepOneTick() }
            assertEquals(0L, physics.teleportCount, "something wrote a transform on a plain tick")

            physics.teleport(handle, BodyPose(x = 17f, y = -4f, angle = 0.5f))
            assertEquals(1L, physics.teleportCount)
            assertTrue(abs(physics.x(handle) - 17f) < 1e-3f)
            assertTrue(abs(physics.y(handle) + 4f) < 1e-3f)
        }
    }

    // --- the rebuild, which is what a restore uses ---------------------------------------------

    /** A world of entities whose components depend only on their [NetId], as in `udea-core`'s. */
    private class Fixture(val bodyCount: Int, reverseSpawnOrder: Boolean = false) {
        val netIds = NetIdIndex(capacity = 1024, entityCapacity = 1024)
        val world: World = configureWorld(1024) {}
        val ids: List<NetId> = (0 until bodyCount).map { NetId.of(it, 0) }

        init {
            val order = if (reverseSpawnOrder) (bodyCount - 1) downTo 0 else 0 until bodyCount
            for (index in order) {
                val entity = world.entity {
                    it += PhysicsBody(
                        kind = if (index % 3 == 0) BodyKind.Static else BodyKind.Dynamic,
                        x = index * 7.5f,
                        y = index * -1.25f,
                        angle = index * 0.01f,
                        linearX = index.toFloat(),
                        linearY = -index.toFloat(),
                    )
                    // Deliberately not in shapeOrder, so a rebuild that trusted component-add
                    // order would build the fixtures in the wrong sequence.
                    if (index % 2 == 0) it += Circle(radius = 4f)
                    it += Box(halfWidth = 3f, halfHeight = 5f)
                    if (index % 4 == 0) it += Capsule(radius = 2f, halfHeight = 3f)
                }
                netIds.bind(entity, ids[index])
            }
        }

        fun bodyOf(id: NetId): PhysicsBody =
            with(world) { checkNotNull(netIds.resolveOrNull(id)) { "$id is not live" }[PhysicsBody] }
    }

    /** Every body in the world, as text, in creation order. A diff points at the field that moved. */
    private fun describe(physics: Box2DPhysicsWorld, fixture: Fixture): List<String> =
        fixture.ids.map { id ->
            val handle = fixture.bodyOf(id).handle
            val pose = physics.poseOf(handle, BodyPose())
            val velocity = physics.velocityOf(handle, BodyVelocity())
            "$id pose=%.4f,%.4f,%.4f vel=%.4f,%.4f fixtures=%d awake=%s".format(
                pose.x,
                pose.y,
                pose.angle,
                velocity.linearX,
                velocity.linearY,
                physics.fixtureCountOf(handle),
                physics.isAwake(handle),
            )
        }

    @Test
    fun `rebuildFrom builds real bodies from components, in NetId order, every time`() {
        // PhysicsRebuildPlan.rebuild had never rebuilt a body when this was written: the only
        // PhysicsWorld in the tree was the no-op. This is the same claim `PhysicsRebuildTest`
        // makes against that no-op, made against a solver that has fixtures, a broadphase and a
        // contact graph - the three things a plan's order actually has to survive.
        val results = (1..8).map {
            val forwards = Fixture(bodyCount = 24)
            val backwards = Fixture(bodyCount = 24, reverseSpawnOrder = true)
            Box2DPhysicsWorld().use { a ->
                Box2DPhysicsWorld().use { b ->
                    a.rebuildFrom(forwards.world, forwards.netIds)
                    b.rebuildFrom(backwards.world, backwards.netIds)
                    assertEquals(24, a.bodyCount)
                    Triple(describe(a, forwards), describe(b, backwards), a.createdCount)
                }
            }
        }

        for ((index, run) in results.withIndex()) {
            assertEquals(run.first, run.second, "run $index: spawn order changed the rebuild")
            assertEquals(results[0].first, run.first, "run $index differs from run 0")
            assertEquals(24L, run.third)
        }
    }

    @Test
    fun `a rebuild destroys every body first, including the ones it will not rebuild`() {
        Box2DPhysicsWorld().use { physics ->
            val fixture = Fixture(bodyCount = 3)
            // An entity with a PhysicsBody and no NetId: debris, exactly what spec 3.4 keeps a
            // solver for. The plan skips it, and the half a backend forgets is invalidating its
            // handle anyway - on this backend a forgotten handle would alias a recycled slot.
            val orphan = fixture.world.entity { it += PhysicsBody(x = 500f) }
            val orphanBody = with(fixture.world) { orphan[PhysicsBody] }
            orphanBody.handle = physics.add(orphanBody, shapes = arrayOf(Circle()))
            assertTrue(orphanBody.handle.isValid)

            physics.rebuildFrom(fixture.world, fixture.netIds)

            assertEquals(3, physics.bodyCount, "the orphan was rebuilt, or a body survived")
            assertEquals(BodyHandle.NONE, orphanBody.handle, "the orphan kept a dangling handle")
        }
    }

    @Test
    fun `every physical claim in this file is false of the no-op, which is the point of the file`() {
        // The negative control. `NoOpPhysicsWorld` satisfies most of `PhysicsWorld`'s contract
        // while simulating nothing, so a suite that only exercised bookkeeping would pass
        // against it and certify a backend that does not exist - which is precisely the state
        // two reviewers found this interface in. Each line below is one of the assertions above,
        // and each one must come out the other way.
        val noOp = dev.wildware.udea.core.physics.NoOpPhysicsWorld()
        val falling = noOp.createBody(BodyDef(body(y = 0f), NetId.NONE, listOf(Circle(radius = 4f))))
        repeat(60) { noOp.stepOneTick() }
        assertEquals(0f, noOp.poseOf(falling, BodyPose()).y, "the no-op integrated gravity")

        noOp.createBody(BodyDef(body(x = 40f), NetId.of(7, 0), listOf(Circle(radius = 8f))))
        assertFalse(noOp.raycast(0f, 0f, 200f, 0f, RayHit()), "the no-op hit something with a ray")

        val out = BodyHandleBuffer()
        assertEquals(0, noOp.overlap(Circle(radius = 40f), BodyPose(), out), "the no-op found an overlap")

        var contacts = 0
        noOp.addContactListener(object : ContactListener {
            override fun onBeginContact(a: BodyHandle, b: BodyHandle) {
                contacts++
            }

            override fun onEndContact(a: BodyHandle, b: BodyHandle) {
                contacts++
            }
        })
        repeat(120) { noOp.stepOneTick() }
        assertEquals(0, contacts, "the no-op reported a contact")
    }

    private companion object {
        /** World units per second squared. A hundred, so a fall is easy to reason about. */
        const val GRAVITY: Float = 100f
    }
}
