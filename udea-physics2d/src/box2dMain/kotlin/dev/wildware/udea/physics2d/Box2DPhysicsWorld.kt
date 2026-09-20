package dev.wildware.udea.physics2d

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor
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
import dev.wildware.udea.core.physics.PhysicsRebuildPlan
import dev.wildware.udea.core.physics.RayHit
import dev.wildware.udea.core.physics.ShapeComponent

internal actual fun openBox2D(settings: Physics2DSettings, tickRate: Int): SolverBackend =
    Box2DPhysicsWorld(settings, tickRate)

/**
 * `udea-core`'s [dev.wildware.udea.core.physics.PhysicsWorld] over Box2D 3, through `box2d-jni`.
 *
 * ## The components are the truth
 *
 * Every body is built from a `PhysicsBody` and its shape components and from nothing else, and
 * after every step the solver's result is copied back into that same `PhysicsBody` - position,
 * angle, velocities and whether it is awake - so the rest of the game reads plain floats and
 * never a Box2D type. The copy happens inside [stepOneTick], once per tick, and it is the only
 * write in that direction. The doors in the other direction are [reconcile], which builds bodies
 * from components before the step, [teleport], and [moveKinematicTo], which sets the velocity that
 * carries a kinematic body to a target pose in one step.
 *
 * ## Determinism
 *
 * The step is always `1 / tickRate` seconds with [Physics2DSettings.subSteps] sub-steps, from
 * [stepOneTick] and from nowhere else. Bodies are created in ascending `NetId` and fixtures in
 * `shapeOrder`, so two processes holding the same components build the same solver. No task
 * system is installed, so Box2D steps on the calling thread. Everything below reads and writes
 * through `box2d-jni`'s address-based calls, so nothing here allocates per body per tick.
 *
 * ## What a rewind restores, exactly
 *
 * [rebuildFrom] destroys the Box2D world and opens a fresh one, then rebuilds every body from the
 * restored components. A *fresh* world rather than the old one with its bodies cleared, because
 * Box2D reuses freed body slots and keeps its broad-phase tree, and both decide the order in
 * which contacts are solved: a world that had lived through other bodies would solve the same
 * components in a different order. With a fresh world a rebuild is a pure function of the
 * components, so a restored run is bit-identical to any other run that rebuilt from the same
 * components at the same tick.
 *
 * It is not always bit-identical to the run that never rewound. What is lost is solver state
 * that no component carries (`SnapshotExclusion.Box2DSolverState` in `udea-core`): the contact
 * manifolds and warm-start impulses of every touching pair, how long each body has been resting
 * towards sleep, and the exact rotation - a component holds an angle and Box2D holds a
 * cosine-sine pair, and Box2D's angle-to-rotation and rotation-to-angle functions are
 * approximations that do not round-trip bit for bit. A scene whose bodies are not touching and
 * not rotating replays exactly; a resting stack settles along a slightly different path after a
 * rewind. That is the trade spec 3.4 chose, and `Box2DRewindTest` measures both halves.
 *
 * ## Bodies belong to components
 *
 * A body whose entity is gone, or whose entity no longer carries the `PhysicsBody` it was built
 * from, is destroyed at the next [reconcile]. So is a body made by calling [createBody] directly
 * for a `PhysicsBody` no entity carries, and an entity whose body was destroyed with
 * [destroyBody] while it still carries its `PhysicsBody` gets a new one. Remove the component to
 * remove the body.
 *
 * Not thread-safe: it belongs to one simulation on one thread.
 */
internal class Box2DPhysicsWorld(
    private val settings: Physics2DSettings,
    tickRate: Int,
) : SolverBackend {

    init {
        require(tickRate > 0) { "tickRate must be positive, was $tickRate" }
        loadBox2DNatives()
    }

    /** One simulation tick, in the seconds Box2D steps in. The same expression as `SimClock.dt`. */
    private val tickSeconds: Float = 1f / tickRate

    /** Native structs this world allocated and has not freed. Zero after [close]. */
    internal var liveNativeStructs: Int = 0
        private set

    private val vecA = allocate(B2Vec2.b2Vec2())
    private val vecB = allocate(B2Vec2.b2Vec2())
    private val rot = allocate(B2Rot.b2Rot())
    private val transform = allocate(B2Transform.b2Transform())
    private val bodyDef = allocate(B2BodyDef.b2BodyDef())
    private val shapeDef = allocate(B2ShapeDef.b2ShapeDef())
    private val polygon = allocate(B2Polygon.b2Polygon())
    private val circle = allocate(B2Circle.b2Circle())
    private val capsule = allocate(B2Capsule.b2Capsule())
    private val segment = allocate(B2Segment.b2Segment())
    private val proxy = allocate(B2ShapeProxy.b2ShapeProxy())
    private val queryFilter = allocate(B2QueryFilter.b2QueryFilter()).also { filter ->
        // Box2D's default query filter: category 1, and every mask bit. Written out because the
        // bindings do not expose `b2DefaultQueryFilter`.
        B2QueryFilter.setCategoryBits(filter, DEFAULT_CATEGORY_BITS)
        B2QueryFilter.setMaskBits(filter, ALL_MASK_BITS)
    }

    /** Where the overlap callback writes, for the duration of one [overlap] call. */
    private var overlapTarget: BodyHandleBuffer? = null

    private val overlapCallback = object : B2OverlapCallback() {
        override fun overlapResultFcn(shapeId: Long): Boolean {
            val target = checkNotNull(overlapTarget) { "an overlap result arrived outside overlap()" }
            val handle = handleOfShape(shapeId)
            if (handle.isValid && !target.containsHandle(handle)) target.add(handle)
            return true
        }
    }

    private var worldId: Long = openWorld()

    private var closed = false

    // --- the body table -------------------------------------------------------------------------
    //
    // Slot-indexed parallel arrays. A handle is `generation << 16 | slot`, so a handle kept past a
    // destroy reads as dead rather than naming whatever took the slot next.

    private var capacity = INITIAL_CAPACITY
    private var live = BooleanArray(capacity)
    private var bodyIds = LongArray(capacity)
    private var generations = IntArray(capacity)
    private var owners = IntArray(capacity)
    private var components = arrayOfNulls<PhysicsBody>(capacity)
    private var chainCopies = arrayOfNulls<FloatArray>(capacity)
    private var kinds = IntArray(capacity)
    private var sensors = BooleanArray(capacity)
    private var shapeMasks = IntArray(capacity)
    private var dims = FloatArray(capacity * DIMS_PER_BODY)

    /**
     * The velocity each body was last *known* to hold: what the solver produced at the last
     * [writeBack], or what [pushWrittenVelocity] last wrote.
     *
     * This is what makes "the game wrote a velocity" answerable at all. A `PhysicsBody` is a plain
     * component and nothing instruments its setters (`AGENTS.md`'s do-not list), so the only way to
     * tell a velocity a game wrote from the one the solver produced is to remember the latter and
     * compare - capture-and-diff, the same bargain replication strikes.
     */
    private var solvedVelX = FloatArray(capacity)

    /** @see solvedVelX */
    private var solvedVelY = FloatArray(capacity)

    private var freeSlots = IntArray(capacity)
    private var freeCount = 0
    private var highWater = 0
    private var liveCount = 0

    private val listeners = ArrayList<ContactListener>()

    /** [rebuildFrom] calls since construction. */
    internal var rebuildCount: Long = 0L
        private set

    /**
     * Velocities [pushWrittenVelocity] actually put into the solver since construction.
     *
     * The observable half of "a scene nobody steers costs nothing": a counter a test can read,
     * rather than a claim about native calls that nothing can check.
     */
    internal var velocityPushes: Long = 0L
        private set

    override val bodyCount: Int get() = liveCount

    // --- stepping -------------------------------------------------------------------------------

    override fun stepOneTick() {
        checkOpen()
        B2World.step(worldId, tickSeconds, settings.subSteps)
        writeBack()
        if (listeners.isNotEmpty()) dispatchContacts()
    }

    /** Copies every non-static body's solved state into the `PhysicsBody` it was built from. */
    private fun writeBack() {
        for (slot in 0 until highWater) {
            if (!live[slot] || kinds[slot] == BodyKind.Static.ordinal) continue
            val body = bodyIds[slot]
            val component = checkNotNull(components[slot])
            val position = B2Body.getPosition(body)
            component.x = B2Vec2.getX(position)
            component.y = B2Vec2.getY(position)
            component.angle = B2RotMath.getAngle(B2Body.getRotation(body))
            val velocity = B2Body.getLinearVelocity(body)
            component.linearX = B2Vec2.getX(velocity)
            component.linearY = B2Vec2.getY(velocity)
            solvedVelX[slot] = component.linearX
            solvedVelY[slot] = component.linearY
            component.angularVelocity = B2Body.getAngularVelocity(body)
            component.awake = B2Body.isAwake(body)
        }
    }

    private fun dispatchContacts() {
        val contacts = B2World.getContactEvents(worldId)
        val beginCount = B2ContactEvents.getBeginCount(contacts)
        val beginBase = B2ContactEvents.getBeginEvents(contacts)
        val endCount = B2ContactEvents.getEndCount(contacts)
        val endBase = B2ContactEvents.getEndEvents(contacts)
        val beginStride = contactBeginStride().toLong()
        for (index in 0 until beginCount) {
            val event = beginBase + beginStride * index
            notify(begin = true, B2ContactBegin.getShapeIdA(event), B2ContactBegin.getShapeIdB(event))
        }
        val endStride = contactEndStride().toLong()
        for (index in 0 until endCount) {
            val event = endBase + endStride * index
            notify(begin = false, B2ContactEnd.getShapeIdA(event), B2ContactEnd.getShapeIdB(event))
        }

        val sensorEvents = B2World.getSensorEvents(worldId)
        val sensorBeginCount = B2SensorEvents.getBeginCount(sensorEvents)
        val sensorBeginBase = B2SensorEvents.getBeginEvents(sensorEvents)
        val sensorEndCount = B2SensorEvents.getEndCount(sensorEvents)
        val sensorEndBase = B2SensorEvents.getEndEvents(sensorEvents)
        val sensorBeginStride = sensorBeginStride().toLong()
        for (index in 0 until sensorBeginCount) {
            val event = sensorBeginBase + sensorBeginStride * index
            notify(begin = true, B2SensorBegin.getSensorShapeId(event), B2SensorBegin.getVisitorShapeId(event))
        }
        val sensorEndStride = sensorEndStride().toLong()
        for (index in 0 until sensorEndCount) {
            val event = sensorEndBase + sensorEndStride * index
            notify(begin = false, B2SensorEnd.getSensorShapeId(event), B2SensorEnd.getVisitorShapeId(event))
        }
    }

    /**
     * Tells every listener about one pair, given the addresses of the two shape-id structs.
     *
     * A pair whose shape was destroyed during the step names no body any more and is not
     * reported: the destruction of the body is the event its owner already knows about.
     */
    private fun notify(begin: Boolean, shapeA: Long, shapeB: Long) {
        val a = handleOfShape(B2Base.storeShapeId(shapeA))
        val b = handleOfShape(B2Base.storeShapeId(shapeB))
        if (!a.isValid || !b.isValid) return
        for (index in listeners.indices) {
            if (begin) listeners[index].onBeginContact(a, b) else listeners[index].onEndContact(a, b)
        }
    }

    // --- reconciliation ---------------------------------------------------------------------------

    private val creator = BodyCreator()

    /** The `PhysicsBody` family of the world [reconcile] last saw, resolved once per world. */
    private var familyWorld: World? = null
    private var bodiesFamily: Family? = null

    override fun reconcile(world: World, netIds: NetIdIndex) {
        checkOpen()
        for (slot in 0 until highWater) {
            if (live[slot]) reconcileSlot(slot, world, netIds)
        }
        creator.world = world
        creator.withBody = 0
        netIds.forEachLive(creator)
        creator.world = null
        requireEveryBodyHasANetId(world, netIds, creator.withBody)
    }

    /** Destroys, rebuilds or refuses one existing body according to its entity's components. */
    private fun reconcileSlot(slot: Int, world: World, netIds: NetIdIndex) {
        val owner = NetId.ofRaw(owners[slot])
        val entity = netIds.resolveOrNull(owner)?.takeIf { it in world }
        val component = entity?.let { with(world) { it.getOrNull(PhysicsBody) } }
        val chain = entity?.let { with(world) { it.getOrNull(Chain) } }
        val builtChain = chainCopies[slot]

        if (component == null || component !== components[slot] || component.handle != handleAt(slot)) {
            if (builtChain != null) {
                throw StaticGeometryChangedException(owner, "its body, which holds a Chain, was removed")
            }
            destroySlot(slot)
            return
        }
        if (builtChain == null && chain != null) {
            throw StaticGeometryChangedException(owner, "a Chain was added after its body was built")
        }
        if (builtChain != null && (chain == null || !chain.vertices.contentEquals(builtChain))) {
            throw StaticGeometryChangedException(owner, "its Chain changed after its body was built")
        }
        if (signatureChanged(slot, component, checkNotNull(entity), world)) {
            // A shape edit is a new body, built from the components in the creation pass below -
            // which is also exactly what a rewind would build, so the edit cannot desync one. It
            // is built awake: a resting body whose shape grew into the floor must be pushed out,
            // and a body built asleep would sit there overlapping it until something woke it.
            destroySlot(slot)
            component.awake = true
            component.handle = BodyHandle.NONE
        }
    }

    /** Visits live `NetId`s in ascending order and builds a body for every one that lacks one. */
    private inner class BodyCreator : NetIdVisitor {
        var world: World? = null
        var withBody = 0

        override fun visit(netId: NetId, entity: Entity) {
            val world = checkNotNull(world)
            val component = with(world) { entity.getOrNull(PhysicsBody) } ?: return
            withBody++
            if (slotOf(component.handle) >= 0) return
            component.handle = createBody(BodyDef(component, netId, PhysicsRebuildPlan.shapesOf(world, entity)))
        }
    }

    /**
     * Fails if an entity carries a `PhysicsBody` and no `NetId`.
     *
     * Such an entity has no body - a rebuild could not place it in a reproducible order, so
     * `PhysicsRebuildPlan` skips it, and so does this world - and it is not in any snapshot. Left
     * alone it would be a body that silently never moves. The count comparison costs nothing on a
     * tick where every body has an id; the walk to name the offender only runs when one does not.
     */
    private fun requireEveryBodyHasANetId(world: World, netIds: NetIdIndex, withNetId: Int) {
        val family = bodiesOf(world)
        if (family.numEntities == withNetId) return
        family.forEach { entity ->
            check(netIds.netIdOf(entity) != NetId.NONE) {
                "entity ${entity.id} carries a PhysicsBody but has no NetId, so it gets no Box2D body " +
                    "and is outside every snapshot. Allocate it a NetId, as every simulated entity has."
            }
        }
    }

    private fun bodiesOf(world: World): Family {
        if (familyWorld !== world) {
            bodiesFamily = world.family { all(PhysicsBody) }
            familyWorld = world
        }
        return checkNotNull(bodiesFamily)
    }

    /** True if [component]'s kind, sensor flag or shapes no longer match what [slot] was built from. */
    private fun signatureChanged(slot: Int, component: PhysicsBody, entity: Entity, world: World): Boolean {
        if (kinds[slot] != component.kind.ordinal || sensors[slot] != component.isSensor) return true
        val box = with(world) { entity.getOrNull(Box) }
        val circle = with(world) { entity.getOrNull(Circle) }
        val capsule = with(world) { entity.getOrNull(Capsule) }
        if (shapeMasks[slot] and SCALAR_SHAPES != scalarShapeMask(box, circle, capsule)) return true
        val base = slot * DIMS_PER_BODY
        return (box != null && (differs(base + BOX_HALF_WIDTH, box.halfWidth) || differs(base + BOX_HALF_HEIGHT, box.halfHeight))) ||
            (circle != null && differs(base + CIRCLE_RADIUS, circle.radius)) ||
            (capsule != null && (differs(base + CAPSULE_RADIUS, capsule.radius) || differs(base + CAPSULE_HALF_HEIGHT, capsule.halfHeight)))
    }

    /**
     * Bit comparison, as `FieldStore.fieldEquals` compares a float: under `!=` a NaN dimension
     * would differ from itself and rebuild its body on every tick.
     */
    private fun differs(index: Int, value: Float): Boolean = dims[index].toRawBits() != value.toRawBits()

    private fun scalarShapeMask(box: Box?, circle: Circle?, capsule: Capsule?): Int =
        (if (box != null) SHAPE_BOX else 0) or
            (if (circle != null) SHAPE_CIRCLE else 0) or
            (if (capsule != null) SHAPE_CAPSULE else 0)

    // --- PhysicsWorld ---------------------------------------------------------------------------

    override fun createBody(def: BodyDef): BodyHandle {
        checkOpen()
        val component = def.body
        val slot = claimSlot()

        B2Body.defaultBodyDef(bodyDef)
        B2BodyDef.setType(bodyDef, bodyType(component.kind))
        B2BodyDef.setPosition(bodyDef, vec(vecA, component.x, component.y))
        B2RotMath.makeRot(component.angle, rot)
        B2BodyDef.setRotation(bodyDef, rot)
        B2BodyDef.setLinearVelocity(bodyDef, vec(vecB, component.linearX, component.linearY))
        B2BodyDef.setAngularVelocity(bodyDef, component.angularVelocity)
        B2BodyDef.setIsAwake(bodyDef, component.awake)
        val body = B2Body.createBody(worldId, bodyDef)
        // The slot, offset by one so that zero - a body with no user data - names nothing. Box2D
        // stores the value and never dereferences it.
        B2Body.setUserData(body, slot + 1L)
        // The velocity the body was built with is already in the solver, so it is what this slot is
        // known to hold. Without this a body built moving would look, on its first tick, like a
        // body a game had just written a velocity onto, and be pushed for nothing.
        solvedVelX[slot] = component.linearX
        solvedVelY[slot] = component.linearY

        val base = slot * DIMS_PER_BODY
        var mask = 0
        var builtChain: FloatArray? = null
        for (shape in def.shapes) {
            B2Shape.defaultShapeDef(shapeDef)
            B2ShapeDef.setIsSensor(shapeDef, component.isSensor)
            // Both on for every shape: a sensor only sees a visitor that has sensor events on,
            // and contact events are what `ContactListener` is told about.
            B2ShapeDef.setEnableSensorEvents(shapeDef, true)
            B2ShapeDef.setEnableContactEvents(shapeDef, true)
            when (shape) {
                is Box -> {
                    B2Geometry.makeBox(shape.halfWidth, shape.halfHeight, polygon)
                    B2Shape.createPolygonShape(body, shapeDef, polygon)
                    dims[base + BOX_HALF_WIDTH] = shape.halfWidth
                    dims[base + BOX_HALF_HEIGHT] = shape.halfHeight
                    mask = mask or SHAPE_BOX
                }
                is Circle -> {
                    B2Circle.setCenter(circle, vec(vecA, 0f, 0f))
                    B2Circle.setRadius(circle, shape.radius)
                    B2Shape.createCircleShape(body, shapeDef, circle)
                    dims[base + CIRCLE_RADIUS] = shape.radius
                    mask = mask or SHAPE_CIRCLE
                }
                is Capsule -> {
                    B2Capsule.setCenter1(capsule, vec(vecA, 0f, -shape.halfHeight))
                    B2Capsule.setCenter2(capsule, vec(vecB, 0f, shape.halfHeight))
                    B2Capsule.setRadius(capsule, shape.radius)
                    B2Shape.createCapsuleShape(body, shapeDef, capsule)
                    dims[base + CAPSULE_RADIUS] = shape.radius
                    dims[base + CAPSULE_HALF_HEIGHT] = shape.halfHeight
                    mask = mask or SHAPE_CAPSULE
                }
                is Chain -> {
                    // Two-sided segments, one per edge. Box2D's own chain shape is one-sided and
                    // treats its first and last points as ghost vertices, which is not what an
                    // open polyline of level geometry means here.
                    val vertices = shape.vertices
                    for (point in 0 until shape.pointCount - 1) {
                        B2Segment.setPoint1(segment, vec(vecA, vertices[point * 2], vertices[point * 2 + 1]))
                        B2Segment.setPoint2(segment, vec(vecB, vertices[point * 2 + 2], vertices[point * 2 + 3]))
                        B2Shape.createSegmentShape(body, shapeDef, segment)
                    }
                    builtChain = vertices.copyOf()
                    mask = mask or SHAPE_CHAIN
                }
            }
        }

        live[slot] = true
        bodyIds[slot] = body
        owners[slot] = def.owner.raw
        components[slot] = component
        chainCopies[slot] = builtChain
        kinds[slot] = component.kind.ordinal
        sensors[slot] = component.isSensor
        shapeMasks[slot] = mask
        liveCount++
        return handleAt(slot)
    }

    override fun destroyBody(handle: BodyHandle): Boolean {
        checkOpen()
        val slot = slotOf(handle)
        if (slot < 0) return false
        destroySlot(slot)
        return true
    }

    override fun destroyAllBodies(): Int {
        checkOpen()
        val destroyed = liveCount
        for (slot in 0 until highWater) {
            if (live[slot]) destroySlot(slot)
        }
        return destroyed
    }

    override fun ownerOf(handle: BodyHandle): NetId {
        val slot = slotOf(handle)
        return if (slot < 0) NetId.NONE else NetId.ofRaw(owners[slot])
    }

    override fun poseOf(handle: BodyHandle, out: BodyPose): BodyPose {
        val body = bodyIds[requireSlot(handle)]
        val position = B2Body.getPosition(body)
        val x = B2Vec2.getX(position)
        val y = B2Vec2.getY(position)
        return out.set(x, y, B2RotMath.getAngle(B2Body.getRotation(body)))
    }

    override fun velocityOf(handle: BodyHandle, out: BodyVelocity): BodyVelocity {
        val body = bodyIds[requireSlot(handle)]
        val velocity = B2Body.getLinearVelocity(body)
        val x = B2Vec2.getX(velocity)
        val y = B2Vec2.getY(velocity)
        return out.set(x, y, B2Body.getAngularVelocity(body))
    }

    override fun teleport(handle: BodyHandle, pose: BodyPose) {
        val body = bodyIds[requireSlot(handle)]
        B2RotMath.makeRot(pose.angle, rot)
        B2Body.setTransform(body, vec(vecA, pose.x, pose.y), rot)
    }

    override fun moveKinematicTo(handle: BodyHandle, x: Float, y: Float, angle: Float) {
        val slot = requireSlot(handle)
        require(kinds[slot] == BodyKind.Kinematic.ordinal) { "$handle is not a kinematic body" }
        B2RotMath.makeRot(angle, rot)
        B2Transform.setP(transform, vec(vecA, x, y))
        B2Transform.setQ(transform, rot)
        // Box2D's own "reach this transform in this time step": the velocity from the body's
        // current pose to the target, over one tick, and a wake only if that velocity is above the
        // sleep threshold - so a kinematic body standing still stays asleep.
        B2Body.setTargetTransform(bodyIds[slot], transform, tickSeconds)
    }

    override fun setAwake(handle: BodyHandle, awake: Boolean) {
        B2Body.setAwake(bodyIds[requireSlot(handle)], awake)
    }

    /**
     * Puts [component]'s `linearX`/`linearY` into the solver, if the game changed them.
     *
     * How a game *steers* a dynamic body (issue #250): a character walks because its game writes a
     * velocity onto its `PhysicsBody` every tick, and this is the one place that reaches Box2D.
     * Before this existed a written velocity was read only when the body was built, so a component
     * could be written all day and the body never moved.
     *
     * "If the game changed them" is the whole of the cost argument, and it is exact rather than
     * approximate: the comparison is on raw bits against [solvedVelX]/[solvedVelY], which hold what
     * the solver last produced, so a scene nobody steers makes **zero** native calls here -
     * `DrivenBodyTest` asserts that on the counter. A tolerance would have been wrong twice over: a
     * game writing a velocity a hair from the solved one means it, and `-0.0` and `0.0` are the same
     * number to `==` and a different push to Box2D.
     *
     * Wakes the body only for a non-zero velocity: writing zero is how a game stops a character, and
     * waking a body to tell it to stand still is how a resting body never sleeps again.
     *
     * A handle that names no live body is ignored rather than refused: a caller walking a family of
     * components is looking at exactly the entities whose bodies may have been destroyed this tick.
     */
    override fun pushWrittenVelocity(component: PhysicsBody) {
        val slot = slotOf(component.handle)
        if (slot < 0) return
        val x = component.linearX
        val y = component.linearY
        if (x.toRawBits() == solvedVelX[slot].toRawBits() && y.toRawBits() == solvedVelY[slot].toRawBits()) return
        val body = bodyIds[slot]
        B2Body.setLinearVelocity(body, vec(vecA, x, y))
        if (x != 0f || y != 0f) B2Body.setAwake(body, true)
        solvedVelX[slot] = x
        solvedVelY[slot] = y
        velocityPushes++
    }

    override fun raycast(fromX: Float, fromY: Float, toX: Float, toY: Float, hit: RayHit): Boolean {
        checkOpen()
        val result = B2World.castRayClosest(
            worldId,
            vec(vecA, fromX, fromY),
            vec(vecB, toX - fromX, toY - fromY),
            queryFilter,
        )
        if (!B2RayResult.getHit(result)) return false
        val fraction = B2RayResult.getFraction(result)
        val point = B2RayResult.getPoint(result)
        val pointX = B2Vec2.getX(point)
        val pointY = B2Vec2.getY(point)
        val normal = B2RayResult.getNormal(result)
        val normalX = B2Vec2.getX(normal)
        val normalY = B2Vec2.getY(normal)
        val body = handleOfShape(B2Base.storeShapeId(B2RayResult.getShapeId(result)))
        if (!body.isValid) return false
        hit.body = body
        hit.pointX = pointX
        hit.pointY = pointY
        hit.normalX = normalX
        hit.normalY = normalY
        hit.fraction = fraction
        return true
    }

    override fun overlap(shape: ShapeComponent, pose: BodyPose, out: BodyHandleBuffer): Int {
        checkOpen()
        out.clear()
        B2RotMath.makeRot(pose.angle, rot)
        val cos = B2Rot.getC(rot)
        val sin = B2Rot.getS(rot)
        when (shape) {
            is Box -> {
                B2ShapeProxy.setCount(proxy, BOX_CORNERS)
                proxyPoint(0, -shape.halfWidth, -shape.halfHeight, pose, cos, sin)
                proxyPoint(1, shape.halfWidth, -shape.halfHeight, pose, cos, sin)
                proxyPoint(2, shape.halfWidth, shape.halfHeight, pose, cos, sin)
                proxyPoint(3, -shape.halfWidth, shape.halfHeight, pose, cos, sin)
                B2ShapeProxy.setRadius(proxy, 0f)
            }
            is Circle -> {
                B2ShapeProxy.setCount(proxy, 1)
                proxyPoint(0, 0f, 0f, pose, cos, sin)
                B2ShapeProxy.setRadius(proxy, shape.radius)
            }
            is Capsule -> {
                B2ShapeProxy.setCount(proxy, 2)
                proxyPoint(0, 0f, -shape.halfHeight, pose, cos, sin)
                proxyPoint(1, 0f, shape.halfHeight, pose, cos, sin)
                B2ShapeProxy.setRadius(proxy, shape.radius)
            }
            is Chain -> throw IllegalArgumentException(
                "a Chain is level geometry, not a query shape; query with a Box, Circle or Capsule",
            )
        }
        overlapTarget = out
        try {
            B2World.overlapShape(worldId, proxy, queryFilter, addressOf(overlapCallback))
        } finally {
            overlapTarget = null
        }
        return out.size
    }

    /** Writes proxy point [index]: the local point ([localX], [localY]) placed at [pose]. */
    private fun proxyPoint(index: Int, localX: Float, localY: Float, pose: BodyPose, cos: Float, sin: Float) {
        val worldX = pose.x + cos * localX - sin * localY
        val worldY = pose.y + sin * localX + cos * localY
        B2ShapeProxy.setPoints(proxy, index, vec(vecA, worldX, worldY))
    }

    override fun addContactListener(listener: ContactListener) {
        if (listener !in listeners) listeners += listener
    }

    override fun removeContactListener(listener: ContactListener): Boolean = listeners.remove(listener)

    override fun rebuildFrom(world: World, netIds: NetIdIndex) {
        checkOpen()
        // A fresh Box2D world, not the old one emptied: see the class KDoc.
        B2World.destroyWorld(worldId)
        clearTable()
        worldId = openWorld()
        PhysicsRebuildPlan.of(world, netIds).rebuild(::createBody)
        rebuildCount++
    }

    override fun close() {
        if (closed) return
        closed = true
        B2World.destroyWorld(worldId)
        clearTable()
        free(vecA, B2Vec2::destroy)
        free(vecB, B2Vec2::destroy)
        free(rot, B2Rot::destroy)
        free(transform, B2Transform::destroy)
        free(bodyDef, B2BodyDef::destroy)
        free(shapeDef, B2ShapeDef::destroy)
        free(polygon, B2Polygon::destroy)
        free(circle, B2Circle::destroy)
        free(capsule, B2Capsule::destroy)
        free(segment, B2Segment::destroy)
        free(proxy, B2ShapeProxy::destroy)
        free(queryFilter, B2QueryFilter::destroy)
        overlapCallback.destroy()
    }

    override fun toString(): String = "Box2DPhysicsWorld(bodies=$liveCount, closed=$closed)"

    // --- internals ------------------------------------------------------------------------------

    /** Opens a Box2D world with every setting that can change a result stated explicitly. */
    private fun openWorld(): Long {
        val def = allocate(B2WorldDef.b2WorldDef())
        B2World.defaultWorldDef(def)
        B2WorldDef.setGravity(def, vec(vecA, settings.gravityX, settings.gravityY))
        B2WorldDef.setEnableSleep(def, settings.enableSleep)
        B2WorldDef.setEnableContinuous(def, settings.enableContinuous)
        val id = B2World.createWorld(def)
        free(def, B2WorldDef::destroy)
        return id
    }

    /** Whether continuous collision and sleeping are on in the live Box2D world, read back from Box2D. */
    internal fun solverFlags(): SolverFlags = SolverFlags(
        continuous = B2World.isContinuousEnabled(worldId),
        sleeping = B2World.isSleepingEnabled(worldId),
    )

    /** Box2D's own count of the bodies, shapes and contacts alive in this world. */
    internal fun solverCounts(): SolverCounts {
        val counters = B2World.getCounters(worldId)
        return SolverCounts(
            bodies = B2Counters.getBodyCount(counters),
            shapes = B2Counters.getShapeCount(counters),
            contacts = B2Counters.getContactCount(counters),
        )
    }

    /** Box2D's version, as the native library reports it. */
    internal fun box2DVersion(): String {
        val version = B2Base.getVersion()
        return "${B2Version.getMajor(version)}.${B2Version.getMinor(version)}.${B2Version.getRevision(version)}"
    }

    private fun destroySlot(slot: Int) {
        B2Body.destroyBody(bodyIds[slot])
        live[slot] = false
        bodyIds[slot] = 0L
        components[slot] = null
        chainCopies[slot] = null
        freeSlots[freeCount++] = slot
        liveCount--
    }

    /** Forgets every body without touching Box2D. Generations survive, so old handles read dead. */
    private fun clearTable() {
        for (slot in 0 until highWater) {
            live[slot] = false
            bodyIds[slot] = 0L
            components[slot] = null
            chainCopies[slot] = null
        }
        highWater = 0
        freeCount = 0
        liveCount = 0
    }

    private fun claimSlot(): Int {
        val slot = if (freeCount > 0) freeSlots[--freeCount] else highWater++
        if (slot >= capacity) grow()
        generations[slot] = (generations[slot] + 1) and GENERATION_MASK
        return slot
    }

    private fun grow() {
        check(capacity < MAX_BODIES) { "a Box2DPhysicsWorld holds at most $MAX_BODIES bodies" }
        capacity = minOf(capacity * 2, MAX_BODIES)
        live = live.copyOf(capacity)
        bodyIds = bodyIds.copyOf(capacity)
        generations = generations.copyOf(capacity)
        owners = owners.copyOf(capacity)
        components = components.copyOf(capacity)
        chainCopies = chainCopies.copyOf(capacity)
        kinds = kinds.copyOf(capacity)
        sensors = sensors.copyOf(capacity)
        shapeMasks = shapeMasks.copyOf(capacity)
        dims = dims.copyOf(capacity * DIMS_PER_BODY)
        solvedVelX = solvedVelX.copyOf(capacity)
        solvedVelY = solvedVelY.copyOf(capacity)
        freeSlots = freeSlots.copyOf(capacity)
    }

    private fun handleAt(slot: Int): BodyHandle = BodyHandle((generations[slot] shl SLOT_BITS) or slot)

    /** The live slot [handle] names, or -1. */
    private fun slotOf(handle: BodyHandle): Int {
        if (!handle.isValid) return -1
        val slot = handle.raw and SLOT_MASK
        if (slot >= highWater || !live[slot]) return -1
        return if (generations[slot] == handle.raw ushr SLOT_BITS) slot else -1
    }

    private fun requireSlot(handle: BodyHandle): Int {
        checkOpen()
        val slot = slotOf(handle)
        if (slot < 0) throw NoSuchBodyException(handle)
        return slot
    }

    /** The handle of the body owning the packed shape id [shapeId], or [BodyHandle.NONE]. */
    private fun handleOfShape(shapeId: Long): BodyHandle {
        if (!B2Shape.isValid(shapeId)) return BodyHandle.NONE
        val slot = B2Body.getUserData(B2Shape.getBody(shapeId)).toInt() - 1
        return if (slot in 0 until highWater && live[slot]) handleAt(slot) else BodyHandle.NONE
    }

    private fun bodyType(kind: BodyKind): Int = when (kind) {
        BodyKind.Static -> B2BodyType.b2_staticBody.value
        BodyKind.Kinematic -> B2BodyType.b2_kinematicBody.value
        BodyKind.Dynamic -> B2BodyType.b2_dynamicBody.value
    }

    private fun vec(address: Long, x: Float, y: Float): Long {
        B2Vec2.setX(address, x)
        B2Vec2.setY(address, y)
        return address
    }

    private fun allocate(address: Long): Long {
        liveNativeStructs++
        return address
    }

    private fun free(address: Long, destroy: (Long) -> Unit) {
        destroy(address)
        liveNativeStructs--
    }

    private fun checkOpen() {
        check(!closed) { "this Box2D world has been closed" }
    }

    /** What `b2World_GetCounters` reports: Box2D's tally, not this class's. */
    internal data class SolverCounts(val bodies: Int, val shapes: Int, val contacts: Int)

    /** The two world-level switches [Physics2DSettings] sets, read back from Box2D. */
    internal data class SolverFlags(val continuous: Boolean, val sleeping: Boolean)

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val SLOT_BITS = 16
        const val SLOT_MASK = (1 shl SLOT_BITS) - 1

        /** `NetId` is a dense u16, so no world holds more bodies than there are ids. */
        const val MAX_BODIES = 1 shl SLOT_BITS

        /** Keeps `generation << 16` non-negative, so a live handle is never `isValid == false`. */
        const val GENERATION_MASK = 0x7FFF

        const val DEFAULT_CATEGORY_BITS = 1L
        const val ALL_MASK_BITS = -1L
        const val BOX_CORNERS = 4

        const val SHAPE_BOX = 1
        const val SHAPE_CIRCLE = 2
        const val SHAPE_CAPSULE = 4
        const val SHAPE_CHAIN = 8
        const val SCALAR_SHAPES = SHAPE_BOX or SHAPE_CIRCLE or SHAPE_CAPSULE

        const val DIMS_PER_BODY = 5
        const val BOX_HALF_WIDTH = 0
        const val BOX_HALF_HEIGHT = 1
        const val CIRCLE_RADIUS = 2
        const val CAPSULE_RADIUS = 3
        const val CAPSULE_HALF_HEIGHT = 4
    }
}

/**
 * Bytes Box2D itself holds, across every world in the process: `b2GetByteCount`.
 *
 * Box2D routes every allocation it makes through one counter, so a body, a shape, a contact or a
 * world that is never freed shows up here as a number that does not come back down. It does not
 * see the handful of scratch structs a world allocates through the bindings, which
 * `Box2DPhysicsWorld.liveNativeStructs` counts instead.
 */
internal fun box2DAllocatedBytes(): Int {
    loadBox2DNatives()
    return B2Base.getByteCount()
}

/** True if [handle] is already in this buffer. Linear, for the handful a query returns. */
private fun BodyHandleBuffer.containsHandle(handle: BodyHandle): Boolean {
    for (index in 0 until size) if (this[index] == handle) return true
    return false
}
