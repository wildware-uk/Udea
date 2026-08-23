package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * Everything physical that is *not* authoritative movement (spec 3.4).
 *
 * ## Box2D is demoted, and this interface is the demotion
 *
 * `CharacterMover` — an allocation-free capsule sweep, replayable 60x per frame — owns
 * movement for every predicted or replicated entity. Box2D is to survive behind this interface
 * for **sensor queries, debris and server-only projectiles** and nothing else, and is **never
 * snapshot state**. (`CharacterMover` does not exist yet either; see "Honest status" below.)
 *
 * That is not a simplification to revisit later. It is what makes rewind work at all: contact
 * manifolds, warm-start impulses and joint accumulated impulses are lost on every
 * [rebuildFrom], deliberately, because nothing authoritative lives in them. The old engine had
 * the inverse arrangement — `Box2DSystem.kt:77` wrote `body.setTransform(...)` for every
 * entity immediately before `world.step(...)`, so the solver's resolved position was
 * overwritten by last tick's transform before it could integrate (which is why bodies jittered
 * against static geometry), and a contact listener mutated `Body.touching`, making solver
 * state authoritative.
 *
 * **Do not "fix" the fidelity of a restore by snapshotting the solver.** There is nothing in
 * the solver worth restoring; if something becomes worth restoring, it belongs in a component
 * and [rebuildFrom] will carry it.
 *
 * ## No Box2D types here
 *
 * Not one signature on this interface names `com.badlogic.gdx.physics.box2d`, and a source
 * scan test enforces it. `udea-core` has no GL and no natives on its compile classpath.
 *
 * ## Honest status: there is no Box2D backend yet
 *
 * As of Phase 0 this interface has **exactly one implementation, [NoOpPhysicsWorld]**, and no
 * module in the tree imports Box2D. "Box2D is demoted behind an interface" is therefore a
 * statement of intent, not of fact: what exists is the seam and the shape of the contract —
 * handles rather than body references, whole-tick stepping, a rebuild driven by
 * [PhysicsRebuildPlan] — chosen so that a solver-backed implementation is an addition rather
 * than a redesign.
 *
 * What that seam has actually bought already, and what makes it more than speculation: the
 * restore path is written and tested against it (spec 3.4's "components are the truth" is
 * checkable today), `udea-core` compiles and tests with no natives, and every consumer is
 * written against handles, so a backend cannot leak solver objects into game code later.
 *
 * What it has **not** bought is any evidence that the contract survives a real solver. Nothing
 * here has been exercised against contacts, fixtures or a body that goes to sleep on its own.
 * Treat a signature that a Box2D backend cannot implement as a defect in *this* file when one
 * lands, not as a reason to widen the interface in advance.
 *
 * ## There is no `step(seconds)`
 *
 * The physics world advances a whole tick at a time like everything else. A `step(dt)` taking
 * a variable delta is how the old engine ended up stepping a hardcoded `1/60f` from inside a
 * system driven by the render delta (`Box2DSystem.kt:80`) — two clocks, disagreeing.
 */
public interface PhysicsWorld {

    /** How many bodies exist right now. Zero orphans is a checkable property, so it is public. */
    public val bodyCount: Int

    /** Advances the non-authoritative physics world by exactly one simulation tick. */
    public fun stepOneTick()

    /**
     * Creates a body and returns its handle, which is the only way to name it afterwards.
     *
     * Handles rather than body objects because a body is solver state: it does not survive a
     * [rebuildFrom], so a reference to one held across a restore would be a dangling pointer
     * that still compiled.
     */
    public fun createBody(def: BodyDef): BodyHandle

    /** Destroys [handle]'s body. Returns false when it was already gone. */
    public fun destroyBody(handle: BodyHandle): Boolean

    /** Destroys every body and returns how many there were. The first half of [rebuildFrom]. */
    public fun destroyAllBodies(): Int

    /** The entity that owns [handle], or [NetId.NONE] if the handle is not live. */
    public fun ownerOf(handle: BodyHandle): NetId

    /**
     * Reads [handle]'s current pose into [out] and returns it.
     *
     * An out-parameter because a sensor-heavy tick reads many poses and this is a per-tick
     * path: returning a fresh `BodyPose` would allocate once per read against a budget the
     * spec gates at zero.
     *
     * @throws NoSuchBodyException if [handle] is not live. Silently returning the origin would
     *   place a projectile at world zero and look like a gameplay bug.
     */
    public fun poseOf(handle: BodyHandle, out: BodyPose): BodyPose

    /** Reads [handle]'s current velocity into [out] and returns it. See [poseOf]. */
    public fun velocityOf(handle: BodyHandle, out: BodyVelocity): BodyVelocity

    /**
     * Moves [handle] to [pose] discontinuously.
     *
     * **The only sanctioned `setTransform`.** It exists for the [Teleport] command, consumed
     * at [dev.wildware.udea.core.module.SimPhase.PreSimulation], and for nothing else: a
     * per-tick write-back is the defect this whole interface reverses. A body moved here has
     * its velocity left alone, because a teleport relocates a thing without re-aiming it.
     */
    public fun teleport(handle: BodyHandle, pose: BodyPose)

    /** Wakes or sleeps [handle]. Restored from `PhysicsBody.awake`, never from the solver. */
    public fun setAwake(handle: BodyHandle, awake: Boolean)

    /**
     * The nearest body along the segment, written into [hit].
     *
     * @return true when something was hit, in which case [hit] describes it; false leaves
     *   [hit] untouched, so a caller reusing one across calls must read the return value.
     */
    public fun raycast(fromX: Float, fromY: Float, toX: Float, toY: Float, hit: RayHit): Boolean

    /**
     * Every body overlapping [shape] placed at [pose], written into [out].
     *
     * The sensor query. [out] is cleared first and is meant to be reused between calls, which
     * is what keeps a per-tick overlap test allocation-free.
     *
     * @return how many handles were written.
     */
    public fun overlap(shape: ShapeComponent, pose: BodyPose, out: BodyHandleBuffer): Int

    /** Registers [listener] for begin/end contact callbacks. Registering twice is a no-op. */
    public fun addContactListener(listener: ContactListener)

    /** Unregisters [listener]. Returns false when it was not registered. */
    public fun removeContactListener(listener: ContactListener): Boolean

    /**
     * Destroys every body, then recreates them from components in one deterministic pass.
     *
     * The restore path (spec 3.4): a snapshot carries `PhysicsBody` and the
     * [ShapeComponent]s, never a body, a fixture, a contact manifold or an impulse. Runs as
     * the **final** stage of the barrier's restore action, after every `Replicator.apply`,
     * because the components it reads are what those calls have just written.
     *
     * Order is fixed twice over so two processes rebuilding the same world produce the same
     * solver: entities in ascending [NetId] order, and each entity's fixtures in
     * [ShapeComponent.shapeOrder] order. [PhysicsRebuildPlan] is that walk, shared by every
     * implementation so the ordering cannot drift between one backend and another.
     *
     * Deliberately **not** restored, and this is the point rather than an omission: contact
     * manifolds, warm-start impulses and joint accumulated impulses (no joints are used).
     * Allocation-tolerant — it runs once per restore, not per tick.
     */
    public fun rebuildFrom(world: World, netIds: NetIdIndex)
}

/**
 * A [PhysicsWorld] handle that names no live body.
 *
 * Typed rather than an `IllegalStateException` so a caller that legitimately races a destroy —
 * a projectile system reading a body a contact just removed — can catch exactly this.
 */
public class NoSuchBodyException(public val handle: BodyHandle) :
    IllegalStateException("no live body for $handle")

/** Begin/end contact notifications from the solver. */
public interface ContactListener {

    /** [a] and [b] began touching during the last [PhysicsWorld.stepOneTick]. */
    public fun onBeginContact(a: BodyHandle, b: BodyHandle)

    /** [a] and [b] stopped touching during the last [PhysicsWorld.stepOneTick]. */
    public fun onEndContact(a: BodyHandle, b: BodyHandle)
}
