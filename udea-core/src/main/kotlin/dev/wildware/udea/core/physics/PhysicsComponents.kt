package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId

/**
 * The authoritative description of a body. **The components are the truth; the solver is
 * derived.**
 *
 * Every field here is a plain primitive, which is the whole design: a snapshot carries these,
 * and [PhysicsWorld.rebuildFrom] turns them back into solver bodies. Nothing here is read back
 * *out* of the solver except by the systems that own debris and projectiles, and nothing a
 * player controls is a Box2D body at all (spec 3.4 — `CharacterMover` owns that).
 *
 * `x`/`y`/`angle` rather than a `Vector2`: `udea-core` has no gdx-math, and a component whose
 * field type came from LibGDX would put LibGDX on the classpath of every module that reads it.
 */
public class PhysicsBody(
    public var kind: BodyKind = BodyKind.Dynamic,
    public var x: Float = 0f,
    public var y: Float = 0f,
    /** Radians. */
    public var angle: Float = 0f,
    public var linearX: Float = 0f,
    public var linearY: Float = 0f,
    /** Radians per second. */
    public var angularVelocity: Float = 0f,
    /** Restored explicitly, so a body asleep before a rewind is asleep after it. */
    public var awake: Boolean = true,
    /** Sensors report overlaps and never resolve collisions. */
    public var isSensor: Boolean = false,
) : Component<PhysicsBody> {

    /**
     * The live solver body, or [BodyHandle.NONE].
     *
     * Derived state, not snapshot state: it is invalidated by every
     * [PhysicsWorld.rebuildFrom] and reassigned by it. Anything that persists a handle across
     * a restore is holding a dangling reference, which is why the handle lives here — on the
     * component the rebuild rewrites — rather than in a map somebody else keeps.
     *
     * *Every* handle is invalidated, including on entities the rebuild creates no body for —
     * [PhysicsRebuildPlan.rebuild] owns that, so the sentence above is true for an entity with
     * no `NetId` too, and not only for the ones the plan covers.
     *
     * **Never `@Net` or `@Sim`.** It is the one field on this component that must not be
     * captured: [BodyHandle] is a value class over `Int`, so an annotation here would lower to
     * an ordinary int column, sail through every type-level guard, and be restored *before*
     * `rebuildFrom` reassigns it — handing out handles to bodies that no longer exist, or, on a
     * backend that recycles indices, to somebody else's. `NoBox2DInCoreTest` fails if an
     * annotation appears on a `BodyHandle` property anywhere in this module.
     */
    public var handle: BodyHandle = BodyHandle.NONE

    override fun type(): ComponentType<PhysicsBody> = PhysicsBody

    override fun toString(): String =
        "PhysicsBody($kind at ($x, $y) angle=$angle awake=$awake handle=$handle)"

    public companion object : ComponentType<PhysicsBody>()
}

/**
 * A collision shape attached to the entity's [PhysicsBody].
 *
 * Sealed so a `when` over shapes is exhaustive: a backend that adds a shape then fails to
 * compile at every site that must handle it, rather than silently creating no fixture for it.
 *
 * [shapeOrder] is what makes a rebuild reproducible. An entity may carry several shape
 * components, Fleks does not promise an order over component types, and Box2D's fixture list
 * is creation-ordered — so without an explicit key, two processes rebuilding the same entity
 * could produce different fixture orders and then different contact orders.
 */
public sealed interface ShapeComponent {
    /** Fixture creation order within one body. Unique per shape type, stable forever. */
    public val shapeOrder: Int
}

/** An axis-aligned box, by half-extents. */
public class Box(
    public var halfWidth: Float = 0.5f,
    public var halfHeight: Float = 0.5f,
) : Component<Box>, ShapeComponent {

    override val shapeOrder: Int get() = ORDER

    override fun type(): ComponentType<Box> = Box

    override fun toString(): String = "Box($halfWidth x $halfHeight)"

    public companion object : ComponentType<Box>() {
        public const val ORDER: Int = 0
    }
}

/** A circle centred on the body origin. */
public class Circle(public var radius: Float = 0.5f) : Component<Circle>, ShapeComponent {

    override val shapeOrder: Int get() = ORDER

    override fun type(): ComponentType<Circle> = Circle

    override fun toString(): String = "Circle(r=$radius)"

    public companion object : ComponentType<Circle>() {
        public const val ORDER: Int = 1
    }
}

/** A vertical capsule: a box of `2 * halfHeight` capped by two circles of [radius]. */
public class Capsule(
    public var radius: Float = 0.5f,
    public var halfHeight: Float = 0.5f,
) : Component<Capsule>, ShapeComponent {

    override val shapeOrder: Int get() = ORDER

    override fun type(): ComponentType<Capsule> = Capsule

    override fun toString(): String = "Capsule(r=$radius, halfHeight=$halfHeight)"

    public companion object : ComponentType<Capsule>() {
        public const val ORDER: Int = 2
    }
}

/**
 * An open polyline, for static level geometry.
 *
 * [vertices] is `x0, y0, x1, y1, ...` — a flat array rather than a list of points because it
 * is level data, sized once and read many times, and a `List<Vector2>` would be one allocation
 * per vertex plus a LibGDX type in a kernel signature.
 */
public class Chain(vertices: FloatArray = FloatArray(0)) : Component<Chain>, ShapeComponent {

    /**
     * `x0, y0, x1, y1, ...`. Assigning an odd-length array throws.
     *
     * The check is on the *setter*, not only in an `init` block: this is a `var`, and a
     * constructor-only check is bypassed by every write that matters. `chain.vertices =
     * FloatArray(3)` is one, and a snapshot restore is the other — `Replicator.apply` writes
     * component fields in place by assignment, so a restore takes exactly the same route as
     * game code. Without the setter check, [pointCount] computes `3 / 2 = 1`, the backend
     * builds a one-point chain and the trailing coordinate is dropped with no error anywhere:
     * a silent wrong answer, which standards section 1 puts at the top of what a failure must
     * never be.
     */
    public var vertices: FloatArray = evenPairs(vertices)
        set(value) {
            field = evenPairs(value)
        }

    /** How many points the chain has. */
    public val pointCount: Int get() = vertices.size / 2

    override val shapeOrder: Int get() = ORDER

    override fun type(): ComponentType<Chain> = Chain

    override fun toString(): String = "Chain($pointCount points)"

    public companion object : ComponentType<Chain>() {
        public const val ORDER: Int = 3

        /** Returns [vertices] if it is a whole number of `(x, y)` pairs, or throws. */
        private fun evenPairs(vertices: FloatArray): FloatArray {
            require(vertices.size % 2 == 0) {
                "a chain needs an even number of floats (x, y pairs), got ${vertices.size}"
            }
            return vertices
        }
    }
}

/**
 * A request to move a body discontinuously, consumed once at
 * [dev.wildware.udea.core.module.SimPhase.PreSimulation].
 *
 * This is the **replacement for the per-tick `setTransform` write-back**. The old
 * `Box2DSystem` pushed every entity's transform into its body immediately before stepping
 * (`Box2DSystem.kt:77`), so the solver never got to keep a result it had computed. Making a
 * teleport an explicit, one-shot command means the write happens on the ticks somebody asked
 * for it and on no others — which is checkable, and is checked.
 *
 * Removed by `TeleportSystem` after it applies, so it cannot re-fire on the next tick.
 */
public class Teleport(
    public var x: Float = 0f,
    public var y: Float = 0f,
    /** Radians. */
    public var angle: Float = 0f,
) : Component<Teleport> {

    override fun type(): ComponentType<Teleport> = Teleport

    override fun toString(): String = "Teleport(($x, $y) angle=$angle)"

    public companion object : ComponentType<Teleport>()
}

/**
 * Everything [PhysicsWorld.createBody] needs, gathered from an entity's components.
 *
 * It holds the [PhysicsBody] itself rather than copying its fields, because copying would
 * create a second description of the same body that could disagree with the first. The world
 * reads it and must not mutate it.
 */
public class BodyDef(
    /** The component this body is built from. */
    public val body: PhysicsBody,
    /** The entity that owns it, so a contact callback can name a game object. */
    public val owner: NetId,
    /** Fixtures to create, already in [ShapeComponent.shapeOrder]. */
    public val shapes: List<ShapeComponent>,
) {
    override fun toString(): String = "BodyDef($owner, $body, shapes=$shapes)"
}
