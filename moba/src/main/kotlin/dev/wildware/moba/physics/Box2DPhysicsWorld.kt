package dev.wildware.moba.physics

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.Box2D
import com.badlogic.gdx.physics.box2d.ChainShape
import com.badlogic.gdx.physics.box2d.CircleShape
import com.badlogic.gdx.physics.box2d.Contact
import com.badlogic.gdx.physics.box2d.Fixture
import com.badlogic.gdx.physics.box2d.FixtureDef
import com.badlogic.gdx.physics.box2d.Manifold
import com.badlogic.gdx.physics.box2d.PolygonShape
import com.badlogic.gdx.physics.box2d.QueryCallback
import com.badlogic.gdx.physics.box2d.RayCastCallback
import com.badlogic.gdx.utils.GdxNativesLoader
import com.badlogic.gdx.physics.box2d.BodyDef as GdxBodyDef
import com.badlogic.gdx.physics.box2d.ContactImpulse as GdxContactImpulse
import com.badlogic.gdx.physics.box2d.ContactListener as GdxContactListener
import com.badlogic.gdx.physics.box2d.World as GdxWorld
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.BodyDef
import dev.wildware.udea.core.physics.BodyHandle
import dev.wildware.udea.core.physics.BodyHandleBuffer
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.BodyPose
import dev.wildware.udea.core.physics.BodyVelocity
import dev.wildware.udea.core.physics.Capsule
import dev.wildware.udea.core.physics.Chain
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.ContactListener
import dev.wildware.udea.core.physics.NoSuchBodyException
import dev.wildware.udea.core.physics.PhysicsRebuildPlan
import dev.wildware.udea.core.physics.PhysicsWorld
import dev.wildware.udea.core.physics.RayHit
import dev.wildware.udea.core.physics.ShapeComponent

/**
 * The real solver behind [PhysicsWorld]: LibGDX's Box2D, and the first implementation of that
 * interface that actually simulates anything.
 *
 * ## What this deletes
 *
 * `NoOpPhysicsWorld`'s own KDoc said it plainly - "there is no Box2D backend in this tree ...
 * nothing ever moves under its own forces, nothing collides, no raycast hits and no contact
 * fires", and `PhysicsWorld`'s said "Box2D is demoted behind an interface" was "a statement of
 * intent, not of fact". [PhysicsRebuildPlan.rebuild] was correct, tested, and had never rebuilt
 * a body. This class is what makes those three sentences false.
 *
 * ## Spec 3.4, and the old engine it is not
 *
 * Box2D here serves **sensor queries, debris and server-only projectiles**, and is never
 * snapshot state. It does not decide where anything a player or the AI controls ends up:
 * `Position` is written by the game's own systems, and this world is asked *questions* about
 * space rather than told to answer them.
 *
 * The old `common/.../Box2DSystem.kt` is the counter-example and every line of it is avoided
 * here:
 *
 * | old defect | what happens instead |
 * |---|---|
 * | contact listener wrote `Body.touching`, making solver state authoritative | contacts are forwarded to registered listeners and stored nowhere |
 * | `body.setTransform(...)` for every entity, every tick, immediately before `step` | [teleport] is the only transform write, and only [dev.wildware.moba.physics.PhysicsMirrorSystem] and `TeleportSystem` call it - on **kinematic** bodies the solver never integrates, so there is no computed result to overwrite |
 * | `step(1/60f)` from a system driven by the render delta | [stepOneTick] steps [secondsPerTick], and the loop decides how many ticks a frame has |
 * | debug geometry drawn inside the simulation tick | this class names no GL type and draws nothing |
 *
 * ## Scale
 *
 * Box2D's tolerances - `linearSlop`, `maxTranslation`, `maxRotation` - are absolute, tuned for
 * bodies between 0.1 and 10 metres. The game measures in world units where a character is about
 * thirty across, so every quantity crossing this boundary is multiplied by [metresPerUnit] on
 * the way in and divided by it on the way out. It is a constructor parameter and not a constant
 * because it belongs to the game's chosen scale, not to the solver.
 *
 * A public [PhysicsWorld] never sees metres: [poseOf], [velocityOf], [overlap] and [raycast] all
 * speak world units, which is what makes the conversion checkable - `Box2DPhysicsWorldTest`
 * builds a world at two different scales and asserts the same answers.
 *
 * ## Handles carry a generation, so a stale one is *detectably* dead
 *
 * [PhysicsBody.handle]'s KDoc warns that on "a backend that recycles indices, the stale handle
 * aliases some *other* entity's new body, and one entity's teleports silently move another's
 * debris". This backend does recycle indices - it must, or a match that fires ten thousand
 * arrows leaks ten thousand slots - so a handle is not a bare index. Its low [INDEX_BITS] are
 * the slot and the rest is a generation counter bumped every time the slot is reused, so a
 * handle held across a rebuild resolves to nothing rather than to somebody else. [ownerOf]
 * answers `NetId.NONE` for it and [poseOf] throws [NoSuchBodyException]; neither aliases.
 *
 * Not thread-safe. Like the rest of the simulation it belongs to one thread.
 */
public class Box2DPhysicsWorld(
    /**
     * Box2D metres per world unit. The game's `PERSONAL_SPACE` is 16 units, so the default
     * makes a unit's personal space exactly one metre.
     */
    public val metresPerUnit: Float = DEFAULT_METRES_PER_UNIT,
    gravityX: Float = 0f,
    gravityY: Float = 0f,
    /**
     * Seconds in one simulation tick. Comes from `EngineConfig.tickRate`, never from a literal
     * at a call site - that disagreement is exactly what `Box2DSystem` shipped.
     */
    public val secondsPerTick: Float = DEFAULT_SECONDS_PER_TICK,
    private val velocityIterations: Int = DEFAULT_VELOCITY_ITERATIONS,
    private val positionIterations: Int = DEFAULT_POSITION_ITERATIONS,
    allowSleep: Boolean = true,
) : PhysicsWorld, AutoCloseable {

    init {
        require(metresPerUnit > 0f) { "metresPerUnit must be positive, was $metresPerUnit" }
        require(secondsPerTick > 0f) { "secondsPerTick must be positive, was $secondsPerTick" }
        // Two libraries, in this order, and the order is not optional: `b2World`'s constructor
        // reaches `com.badlogic.gdx.utils.BufferUtils.getBufferAddress`, which lives in gdx's
        // *own* native (`gdx64`), not in `gdx-box2d64`. Loading only the second one produces an
        // `UnsatisfiedLinkError` naming `BufferUtils` - a message that points at the wrong
        // library and cost this class an afternoon.
        //
        // Both loaders are idempotent, and both are called here rather than from an entry point
        // so that a test constructing this class directly gets a working solver. A windowed run
        // has already loaded gdx's native through `Lwjgl3Application`; the guard inside
        // `GdxNativesLoader` makes the second call free.
        GdxNativesLoader.load()
        Box2D.init()
    }

    private val world: GdxWorld = GdxWorld(Vector2(gravityX * metresPerUnit, gravityY * metresPerUnit), allowSleep)

    /** Slot per body index. A null entry is a free slot; [freeSlots] holds its index. */
    private val slots = ArrayList<Slot?>()

    /** Indices of free entries in [slots], as a stack. Recycled newest-first, deterministically. */
    private var freeSlots = IntArray(INITIAL_FREE_CAPACITY)
    private var freeCount = 0

    /** Generation counter per slot index, so a recycled slot hands out a different handle. */
    private var generations = IntArray(INITIAL_FREE_CAPACITY)

    /** Live bodies, in creation order. Iterated by [destroyAllBodies], so it must be ordered. */
    private val live = LinkedHashMap<Int, Slot>()

    private val listeners = LinkedHashSet<ContactListener>()

    /** Reused across every conversion. Box2D's setters take a `Vector2`; this is the only one. */
    private val scratchVector = Vector2()
    private val rayFrom = Vector2()
    private val rayTo = Vector2()

    /** [stepOneTick] calls since construction. A health signal an agent can poll. */
    public var stepCount: Long = 0L
        private set

    /** [rebuildFrom] calls since construction. */
    public var rebuildCount: Long = 0L
        private set

    /** [teleport] calls since construction. */
    public var teleportCount: Long = 0L
        private set

    /** Bodies created since construction, including by every rebuild. */
    public var createdCount: Long = 0L
        private set

    override val bodyCount: Int get() = live.size

    // --- stepping ---------------------------------------------------------------------------

    override fun stepOneTick() {
        world.step(secondsPerTick, velocityIterations, positionIterations)
        stepCount++
    }

    // --- lifecycle --------------------------------------------------------------------------

    override fun createBody(def: BodyDef): BodyHandle {
        val body = def.body
        gdxDef.type = when (body.kind) {
            BodyKind.Static -> GdxBodyDef.BodyType.StaticBody
            BodyKind.Kinematic -> GdxBodyDef.BodyType.KinematicBody
            BodyKind.Dynamic -> GdxBodyDef.BodyType.DynamicBody
        }
        gdxDef.position.set(body.x * metresPerUnit, body.y * metresPerUnit)
        gdxDef.angle = body.angle
        gdxDef.linearVelocity.set(body.linearX * metresPerUnit, body.linearY * metresPerUnit)
        gdxDef.angularVelocity = body.angularVelocity
        gdxDef.awake = body.awake
        // A body with no fixture has no mass, and Box2D puts a massless dynamic body to sleep
        // instantly; `awake` is restored explicitly from the component either way (spec 3.4).
        val created = world.createBody(gdxDef)

        for (shape in def.shapes) addFixtures(created, shape, body.isSensor)

        val index = allocateSlot()
        val handle = BodyHandle((generations[index] shl INDEX_BITS) or index)
        val slot = Slot(handle = handle, body = created, owner = def.owner, shapes = def.shapes)
        slots[index] = slot
        live[handle.raw] = slot
        // Boxed once per body creation, never per tick: the contact listener and every query
        // callback map a Box2D `Body` back to a handle through this and nothing else.
        created.userData = slot
        created.isAwake = body.awake
        createdCount++
        return handle
    }

    override fun destroyBody(handle: BodyHandle): Boolean {
        val slot = live.remove(handle.raw) ?: return false
        releaseSlot(slot)
        return true
    }

    override fun destroyAllBodies(): Int {
        val destroyed = live.size
        // Copy first: `world.destroyBody` cannot run while the map is being iterated, and the
        // iteration order is what makes a rebuild's destroy pass deterministic.
        for (slot in live.values.toList()) releaseSlot(slot)
        live.clear()
        return destroyed
    }

    override fun ownerOf(handle: BodyHandle): NetId = live[handle.raw]?.owner ?: NetId.NONE

    // --- reads ------------------------------------------------------------------------------

    override fun poseOf(handle: BodyHandle, out: BodyPose): BodyPose {
        val body = bodyOf(handle)
        val position = body.position
        return out.set(position.x / metresPerUnit, position.y / metresPerUnit, body.angle)
    }

    override fun velocityOf(handle: BodyHandle, out: BodyVelocity): BodyVelocity {
        val body = bodyOf(handle)
        val velocity = body.linearVelocity
        return out.set(velocity.x / metresPerUnit, velocity.y / metresPerUnit, body.angularVelocity)
    }

    override fun teleport(handle: BodyHandle, pose: BodyPose) {
        val body = bodyOf(handle)
        body.setTransform(pose.x * metresPerUnit, pose.y * metresPerUnit, pose.angle)
        teleportCount++
    }

    override fun setAwake(handle: BodyHandle, awake: Boolean) {
        bodyOf(handle).isAwake = awake
    }

    /** True when [handle]'s body is awake. Not on [PhysicsWorld]; a test and an agent read it. */
    public fun isAwake(handle: BodyHandle): Boolean = bodyOf(handle).isAwake

    /** How many fixtures [handle] was built with, in creation order. Checkable rebuild evidence. */
    public fun fixtureCountOf(handle: BodyHandle): Int = bodyOf(handle).fixtureList.size

    /** How many contact listeners are registered. */
    public val contactListenerCount: Int get() = listeners.size

    /**
     * Visits every live body, in creation order, with its handle and its owner.
     *
     * Not on [PhysicsWorld]: the interface deliberately hands out handles and never a body list,
     * because a consumer that enumerated the solver would be reading solver state. This is a
     * *read* for a test and for an agent tool - "what does the physics world contain right now" -
     * and the whole point of the rewind proof is being able to ask it before and after a restore
     * and compare the answers. Creation order, which after a [rebuildFrom] is ascending
     * [NetId], so the answer is comparable rather than merely equal as a set.
     */
    public fun forEachBody(action: (BodyHandle, NetId) -> Unit) {
        for (slot in live.values) action(slot.handle, slot.owner)
    }

    // --- queries ----------------------------------------------------------------------------

    /**
     * The nearest body along the segment.
     *
     * Box2D's `rayCast` reports hits in whatever order the broadphase walks them and lets the
     * callback shorten the ray by returning the fraction, which is the documented way to ask for
     * the nearest: keeping the smallest fraction seen would be a second, redundant comparison,
     * but it is what makes the answer independent of the tree's shape - and therefore the same
     * on two machines whose bodies were created in the same order. Both are done.
     *
     * A zero-length segment is a miss rather than an error: Box2D asserts on one, and a caller
     * asking "what is between me and myself" wants "nothing".
     */
    override fun raycast(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        hit: RayHit,
    ): Boolean {
        rayFrom.set(fromX * metresPerUnit, fromY * metresPerUnit)
        rayTo.set(toX * metresPerUnit, toY * metresPerUnit)
        if (rayFrom.epsilonEquals(rayTo, ZERO_LENGTH_RAY)) return false
        rayCallback.reset()
        world.rayCast(rayCallback, rayFrom, rayTo)
        if (!rayCallback.found) return false
        hit.body = rayCallback.hitHandle
        hit.pointX = rayCallback.pointX / metresPerUnit
        hit.pointY = rayCallback.pointY / metresPerUnit
        hit.normalX = rayCallback.normalX
        hit.normalY = rayCallback.normalY
        hit.fraction = rayCallback.fraction
        return true
    }

    /**
     * Every body overlapping [shape] placed at [pose].
     *
     * Box2D's `QueryAABB` is the broadphase - a balanced dynamic tree, which is the whole reason
     * this replaces the game's `O(n^2)` scan - and it reports fixtures whose *fat* AABB touches
     * the query box, so a narrowphase has to follow or the answer includes bodies a hand's
     * breadth away. [OverlapNarrowphase] is that pass, run against the shapes each body was
     * created with rather than against Box2D's fixtures, because gdx does not expose `b2Distance`.
     *
     * **Exact for the shapes this game queries with, conservative for the rest**, and that is a
     * limit rather than a rounding: circle-versus-circle, circle-versus-box and
     * circle-versus-capsule are analytic; a [Chain] is answered by its fixture AABB, so a query
     * near a wall can report the wall when it is a few units clear of it. Stated here rather
     * than discovered later; see [OverlapNarrowphase].
     *
     * Results are sorted by owning [NetId] before they are returned, and that is load-bearing:
     * a broadphase walk order depends on the tree's shape, which depends on every insertion and
     * removal the world has ever seen, so a caller that sums a force over the results would get
     * a different rounding after a rewind rebuilt the tree. Sorting by an id the simulation
     * owns makes the result a function of the world rather than of the tree. Bodies with no
     * owner sort last, by handle, so the order is total.
     */
    override fun overlap(shape: ShapeComponent, pose: BodyPose, out: BodyHandleBuffer): Int {
        out.clear()
        val radius = OverlapNarrowphase.boundingRadius(shape)
        queryCallback.begin(shape, pose, out)
        val lowerX = (pose.x - radius) * metresPerUnit
        val lowerY = (pose.y - radius) * metresPerUnit
        val upperX = (pose.x + radius) * metresPerUnit
        val upperY = (pose.y + radius) * metresPerUnit
        world.QueryAABB(queryCallback, lowerX, lowerY, upperX, upperY)
        queryCallback.end()
        sortByOwner(out)
        return out.size
    }

    // --- contacts ---------------------------------------------------------------------------

    override fun addContactListener(listener: ContactListener) {
        listeners += listener
    }

    override fun removeContactListener(listener: ContactListener): Boolean = listeners.remove(listener)

    // --- restore ----------------------------------------------------------------------------

    /**
     * Destroys every body and recreates them from components, in [PhysicsRebuildPlan]'s order.
     *
     * The shared walk rather than a loop of this class's own: the plan also invalidates the
     * handle of every `PhysicsBody` it does *not* build a body for, which is precisely the half
     * a hand-written backend forgets, and on this backend - which recycles slot indices - the
     * forgotten half is the one that would alias.
     */
    override fun rebuildFrom(world: World, netIds: NetIdIndex) {
        destroyAllBodies()
        PhysicsRebuildPlan.of(world, netIds).rebuild(::createBody)
        rebuildCount++
    }

    override fun close() {
        destroyAllBodies()
        world.dispose()
    }

    override fun toString(): String =
        "Box2DPhysicsWorld(bodies=${live.size}, steps=$stepCount, rebuilds=$rebuildCount)"

    // --- internals --------------------------------------------------------------------------

    /** Reused across every [createBody]. Box2D copies out of it, so one is enough. */
    private val gdxDef = GdxBodyDef()

    /** Reused across every fixture. Same reason. */
    private val fixtureDef = FixtureDef()

    private fun bodyOf(handle: BodyHandle): Body =
        live[handle.raw]?.body ?: throw NoSuchBodyException(handle)

    private fun allocateSlot(): Int {
        if (freeCount > 0) {
            freeCount--
            val index = freeSlots[freeCount]
            // Wraps rather than overflows into the sign bit: a wrapped generation can only alias
            // a handle nobody has held for GENERATION_LIMIT reuses of the same slot.
            generations[index] = (generations[index] + 1) % GENERATION_LIMIT
            return index
        }
        val index = slots.size
        check(index <= INDEX_MASK) { "Box2DPhysicsWorld holds at most ${INDEX_MASK + 1} bodies" }
        slots.add(null)
        if (index >= generations.size) generations = generations.copyOf(generations.size * 2)
        generations[index] = 0
        return index
    }

    private fun releaseSlot(slot: Slot) {
        val index = slot.handle.raw and INDEX_MASK
        slots[index] = null
        slot.body.userData = null
        world.destroyBody(slot.body)
        if (freeCount == freeSlots.size) freeSlots = freeSlots.copyOf(freeSlots.size * 2)
        freeSlots[freeCount] = index
        freeCount++
    }

    /** Adds the one or three fixtures [shape] lowers to. Shapes are disposed; Box2D copies them. */
    private fun addFixtures(body: Body, shape: ShapeComponent, isSensor: Boolean) {
        fixtureDef.isSensor = isSensor
        fixtureDef.density = FIXTURE_DENSITY
        fixtureDef.friction = FIXTURE_FRICTION
        fixtureDef.restitution = 0f
        when (shape) {
            is Box -> PolygonShape().use { polygon ->
                polygon.setAsBox(shape.halfWidth * metresPerUnit, shape.halfHeight * metresPerUnit)
                fixtureDef.shape = polygon
                body.createFixture(fixtureDef)
            }

            is Circle -> CircleShape().use { circle ->
                circle.radius = shape.radius * metresPerUnit
                fixtureDef.shape = circle
                body.createFixture(fixtureDef)
            }

            // Three fixtures in a fixed order - box, bottom cap, top cap - because Box2D has no
            // capsule and a fixture list is creation-ordered. The order is written down here so
            // two processes building the same capsule get the same list.
            is Capsule -> {
                PolygonShape().use { polygon ->
                    polygon.setAsBox(shape.radius * metresPerUnit, shape.halfHeight * metresPerUnit)
                    fixtureDef.shape = polygon
                    body.createFixture(fixtureDef)
                }
                for (sign in CAPSULE_CAP_SIGNS) {
                    CircleShape().use { circle ->
                        circle.radius = shape.radius * metresPerUnit
                        circle.position = scratchVector.set(0f, sign * shape.halfHeight * metresPerUnit)
                        fixtureDef.shape = circle
                        body.createFixture(fixtureDef)
                    }
                }
            }

            is Chain -> {
                // A chain of fewer than two points has no segment. Box2D asserts inside the
                // native library on one, which is a JVM crash rather than an exception, so it
                // is refused here where the message can name the component.
                require(shape.pointCount >= 2) {
                    "a Chain fixture needs at least 2 points, got ${shape.pointCount}"
                }
                ChainShape().use { chain ->
                    val scaled = FloatArray(shape.vertices.size)
                    for (index in scaled.indices) scaled[index] = shape.vertices[index] * metresPerUnit
                    chain.createChain(scaled)
                    fixtureDef.shape = chain
                    body.createFixture(fixtureDef)
                }
            }
        }
        fixtureDef.shape = null
    }

    /**
     * Insertion-sorts [out] by owning [NetId], then by handle for the unowned.
     *
     * Insertion sort and not `sortedBy`: an overlap returns a handful of handles on a per-tick
     * path, and a comparator over boxed keys would allocate twice per query against a budget
     * the spec gates at zero. It reads back through [BodyHandleBuffer]'s public surface, which
     * is why the buffer is rebuilt rather than sorted in place.
     */
    private fun sortByOwner(out: BodyHandleBuffer) {
        val size = out.size
        if (size < 2) return
        if (sortKeys.size < size) {
            sortKeys = LongArray(size * 2)
        }
        for (index in 0 until size) {
            val handle = out[index]
            val owner = live[handle.raw]?.owner ?: NetId.NONE
            // Unowned bodies sort after every owned one: NONE is negative, so it is mapped to
            // the top of the key space rather than to the bottom.
            val primary = if (owner.raw < 0) Int.MAX_VALUE.toLong() else owner.raw.toLong()
            sortKeys[index] = (primary shl KEY_SHIFT) or handle.raw.toLong()
        }
        for (index in 1 until size) {
            val key = sortKeys[index]
            var cursor = index - 1
            while (cursor >= 0 && sortKeys[cursor] > key) {
                sortKeys[cursor + 1] = sortKeys[cursor]
                cursor--
            }
            sortKeys[cursor + 1] = key
        }
        out.clear()
        for (index in 0 until size) out.add(BodyHandle((sortKeys[index] and KEY_MASK).toInt()))
    }

    private var sortKeys = LongArray(BodyHandleBufferSortCapacity)

    /** One tracked body: its handle, its Box2D body, its owner and the shapes it was built from. */
    internal class Slot(
        val handle: BodyHandle,
        val body: Body,
        val owner: NetId,
        /** Retained for the overlap narrowphase; gdx exposes no shape-versus-shape distance. */
        val shapes: List<ShapeComponent>,
    )

    /** Reused. `QueryAABB` calls back once per candidate fixture; this filters them. */
    private val queryCallback = object : QueryCallback {

        private var shape: ShapeComponent? = null
        private var poseX = 0f
        private var poseY = 0f
        private var poseAngle = 0f
        private var out: BodyHandleBuffer? = null
        private var lastHandle = -1

        fun begin(shape: ShapeComponent, pose: BodyPose, out: BodyHandleBuffer) {
            this.shape = shape
            this.poseX = pose.x
            this.poseY = pose.y
            this.poseAngle = pose.angle
            this.out = out
            this.lastHandle = -1
        }

        fun end() {
            shape = null
            out = null
        }

        override fun reportFixture(fixture: Fixture): Boolean {
            val slot = fixture.body.userData as? Slot ?: return true
            // One body, many fixtures: a capsule reports three times. The buffer holds bodies,
            // so the duplicate is dropped here. `lastHandle` alone is enough because Box2D
            // reports a body's fixtures contiguously only *usually* - so the buffer is scanned
            // as well, which is a handful of ints on a handful of candidates.
            if (slot.handle.raw == lastHandle) return true
            val buffer = out ?: return false
            for (index in 0 until buffer.size) if (buffer[index].raw == slot.handle.raw) return true
            val queryShape = shape ?: return false
            val position = slot.body.position
            if (OverlapNarrowphase.overlaps(
                    queryShape = queryShape,
                    queryX = poseX,
                    queryY = poseY,
                    queryAngle = poseAngle,
                    target = slot,
                    targetX = position.x / metresPerUnit,
                    targetY = position.y / metresPerUnit,
                    targetAngle = slot.body.angle,
                )
            ) {
                buffer.add(slot.handle)
                lastHandle = slot.handle.raw
            }
            return true
        }
    }

    /** Reused. Keeps the nearest hit and shortens the ray, which is Box2D's documented idiom. */
    private val rayCallback = object : RayCastCallback {

        var found = false
        var hitHandle: BodyHandle = BodyHandle.NONE
        var pointX = 0f
        var pointY = 0f
        var normalX = 0f
        var normalY = 0f
        var fraction = 1f

        fun reset() {
            found = false
            hitHandle = BodyHandle.NONE
            fraction = 1f
        }

        override fun reportRayFixture(
            fixture: Fixture,
            point: Vector2,
            normal: Vector2,
            fraction: Float,
        ): Float {
            val slot = fixture.body.userData as? Slot ?: return -1f
            if (found && fraction > this.fraction) return this.fraction
            found = true
            hitHandle = slot.handle
            pointX = point.x
            pointY = point.y
            normalX = normal.x
            normalY = normal.y
            this.fraction = fraction
            return fraction
        }
    }

    /**
     * The one Box2D contact listener, forwarding to whoever registered with [addContactListener].
     *
     * It stores nothing. That is the whole difference from `Box2DSystem`'s, which wrote
     * `Body.touching` and thereby made a contact manifold - state a rewind destroys - part of
     * what the game read to decide gameplay.
     */
    private val bridge = object : GdxContactListener {

        override fun beginContact(contact: Contact) {
            val a = contact.fixtureA.body.userData as? Slot ?: return
            val b = contact.fixtureB.body.userData as? Slot ?: return
            for (listener in listeners) listener.onBeginContact(a.handle, b.handle)
        }

        override fun endContact(contact: Contact) {
            val a = contact.fixtureA.body.userData as? Slot ?: return
            val b = contact.fixtureB.body.userData as? Slot ?: return
            for (listener in listeners) listener.onEndContact(a.handle, b.handle)
        }

        override fun preSolve(contact: Contact, oldManifold: Manifold): Unit = Unit

        override fun postSolve(contact: Contact, impulse: GdxContactImpulse): Unit = Unit
    }

    init {
        world.setContactListener(bridge)
    }

    public companion object {

        /** One metre per sixteen world units, which is the game's `PERSONAL_SPACE`. */
        public const val DEFAULT_METRES_PER_UNIT: Float = 1f / 16f

        /** 60Hz, matching `SimClock.DEFAULT_TICK_RATE`. Overridden from `EngineConfig`. */
        public const val DEFAULT_SECONDS_PER_TICK: Float = 1f / 60f

        /** Box2D's own recommended defaults. */
        public const val DEFAULT_VELOCITY_ITERATIONS: Int = 8
        public const val DEFAULT_POSITION_ITERATIONS: Int = 3

        /** Slot index bits in a [BodyHandle]. The rest, below the sign bit, is the generation. */
        public const val INDEX_BITS: Int = 20

        /** The largest slot index a handle can name. */
        public const val INDEX_MASK: Int = (1 shl INDEX_BITS) - 1

        /** Generations wrap here, keeping a handle's raw value positive so `isValid` holds. */
        public const val GENERATION_LIMIT: Int = 1 shl (30 - INDEX_BITS)

        private const val INITIAL_FREE_CAPACITY: Int = 64
        private const val FIXTURE_DENSITY: Float = 1f
        private const val FIXTURE_FRICTION: Float = 0.2f
        private const val ZERO_LENGTH_RAY: Float = 1e-7f
        private val CAPSULE_CAP_SIGNS = floatArrayOf(-1f, 1f)

        /** Sort keys pack `owner` above `handle`, so the order is total and allocation-free. */
        private const val KEY_SHIFT: Int = 32
        private const val KEY_MASK: Long = 0xFFFF_FFFFL

        /** Matches `BodyHandleBuffer`'s own default, so the first query does not regrow. */
        private const val BodyHandleBufferSortCapacity: Int = 16

        /** `use` for a Box2D shape, which is `Disposable` but not `Closeable`. */
        private inline fun <T : com.badlogic.gdx.physics.box2d.Shape> T.use(block: (T) -> Unit) {
            try {
                block(this)
            } finally {
                dispose()
            }
        }
    }
}
