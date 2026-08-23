package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * A [PhysicsWorld] with no solver.
 *
 * ## The no-op is the *solver*, not the bookkeeping
 *
 * Bodies exist, handles resolve, poses read back, [rebuildFrom] runs the real deterministic
 * plan. What does not happen is simulation: [stepOneTick] integrates nothing, [raycast] hits
 * nothing, [overlap] finds nothing, contact listeners never fire.
 *
 * A null object that also *lost* bodies would push a branch into every consumer — "is physics
 * real here?" — and that branch is the thing this class exists to remove. A dedicated server
 * with no debris, a `SimHarness` run, a CI tick-loop benchmark and most tests want a context
 * whose `ctx.physics` is present and cheap, not absent.
 *
 * ## Why it is production code and not a test double
 *
 * It is the default `ctx.physics` for a world built from `CoreModule`, which is the only
 * physics `udea-core` can supply: Box2D natives and the `Box2DPhysicsWorld` backend live in
 * their own module, since a kernel that imported `com.badlogic.gdx.physics.box2d` would fail
 * its own no-GL gate. A game swaps the real world in from its module's `context` hook.
 *
 * Not thread-safe: like the rest of the kernel it belongs to one simulation on one thread.
 */
public class NoOpPhysicsWorld : PhysicsWorld {

    /** Live bodies by handle. Insertion-ordered, so [destroyAllBodies] is deterministic. */
    private val bodies = LinkedHashMap<Int, Record>()

    private var nextHandle: Int = 0

    /** Bodies created since construction. A rebuild counter a test can assert against. */
    public var createdCount: Long = 0L
        private set

    /** [rebuildFrom] calls since construction. */
    public var rebuildCount: Long = 0L
        private set

    /** [teleport] calls since construction. Zero on a tick nobody asked for a teleport. */
    public var teleportCount: Long = 0L
        private set

    override val bodyCount: Int get() = bodies.size

    /** Does nothing, on purpose: there is no solver to advance. */
    override fun stepOneTick(): Unit = Unit

    override fun createBody(def: BodyDef): BodyHandle {
        val handle = BodyHandle(nextHandle)
        nextHandle++
        bodies[handle.raw] = Record(
            owner = def.owner,
            x = def.body.x,
            y = def.body.y,
            angle = def.body.angle,
            linearX = def.body.linearX,
            linearY = def.body.linearY,
            angular = def.body.angularVelocity,
            awake = def.body.awake,
            shapeOrders = def.shapes.map { it.shapeOrder },
        )
        createdCount++
        return handle
    }

    override fun destroyBody(handle: BodyHandle): Boolean = bodies.remove(handle.raw) != null

    override fun destroyAllBodies(): Int {
        val destroyed = bodies.size
        bodies.clear()
        return destroyed
    }

    override fun ownerOf(handle: BodyHandle): NetId = bodies[handle.raw]?.owner ?: NetId.NONE

    override fun poseOf(handle: BodyHandle, out: BodyPose): BodyPose {
        val record = bodies[handle.raw] ?: throw NoSuchBodyException(handle)
        return out.set(record.x, record.y, record.angle)
    }

    override fun velocityOf(handle: BodyHandle, out: BodyVelocity): BodyVelocity {
        val record = bodies[handle.raw] ?: throw NoSuchBodyException(handle)
        return out.set(record.linearX, record.linearY, record.angular)
    }

    override fun teleport(handle: BodyHandle, pose: BodyPose) {
        val record = bodies[handle.raw] ?: throw NoSuchBodyException(handle)
        record.x = pose.x
        record.y = pose.y
        record.angle = pose.angle
        teleportCount++
    }

    override fun setAwake(handle: BodyHandle, awake: Boolean) {
        val record = bodies[handle.raw] ?: throw NoSuchBodyException(handle)
        record.awake = awake
    }

    /** True when [handle] is awake. There is no solver to sleep it, so only [setAwake] moves it. */
    public fun isAwake(handle: BodyHandle): Boolean =
        (bodies[handle.raw] ?: throw NoSuchBodyException(handle)).awake

    /** The fixture orders [handle] was built with, so a rebuild's shape order is checkable. */
    public fun shapeOrdersOf(handle: BodyHandle): List<Int> =
        (bodies[handle.raw] ?: throw NoSuchBodyException(handle)).shapeOrders

    /** Never hits: there is nothing to cast against. [hit] is left untouched. */
    override fun raycast(fromX: Float, fromY: Float, toX: Float, toY: Float, hit: RayHit): Boolean = false

    /** Never overlaps. [out] is cleared, as every implementation must leave it. */
    override fun overlap(shape: ShapeComponent, pose: BodyPose, out: BodyHandleBuffer): Int {
        out.clear()
        return 0
    }

    /**
     * Accepts [listener] and never calls it, because no contact can occur without a solver.
     *
     * Kept rather than rejected so a module can register its listener unconditionally: a
     * listener that is never invoked is correct here, whereas a registration that threw would
     * force every caller to know which physics world it got.
     */
    override fun addContactListener(listener: ContactListener) {
        listeners += listener
    }

    override fun removeContactListener(listener: ContactListener): Boolean = listeners.remove(listener)

    /** Registered listeners. Held so [removeContactListener] can answer truthfully. */
    private val listeners = LinkedHashSet<ContactListener>()

    /** How many contact listeners are registered. */
    public val contactListenerCount: Int get() = listeners.size

    override fun rebuildFrom(world: World, netIds: NetIdIndex) {
        destroyAllBodies()
        for (planned in PhysicsRebuildPlan.of(world, netIds).bodies) {
            planned.component.handle = createBody(planned.def)
        }
        rebuildCount++
    }

    override fun toString(): String = "NoOpPhysicsWorld(bodies=${bodies.size})"

    /** One tracked body. Mutable because [teleport] and [setAwake] are the only writers. */
    private class Record(
        val owner: NetId,
        var x: Float,
        var y: Float,
        var angle: Float,
        val linearX: Float,
        val linearY: Float,
        val angular: Float,
        var awake: Boolean,
        val shapeOrders: List<Int>,
    )
}
