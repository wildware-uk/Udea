package dev.wildware.udea.core.physics

/**
 * Names one body inside a [PhysicsWorld].
 *
 * A value class over an `Int` rather than a reference to a solver body, for the reason the
 * whole physics boundary exists: a body does not survive [PhysicsWorld.rebuildFrom], so a
 * field holding one would be a dangling reference that still type-checked after every rewind.
 * A handle is checkable — [PhysicsWorld.ownerOf] answers `NetId.NONE` for a dead one.
 *
 * Distinct at compile time from a `NetId`, an entity id and a field index, all of which are
 * also ints: `createBody` returning a bare `Int` is exactly the stringly-typed smell the
 * charter names, with a different primitive.
 */
@JvmInline
public value class BodyHandle(public val raw: Int) {

    /** False for [NONE] and for anything a world has not handed out. */
    public val isValid: Boolean get() = raw >= 0

    override fun toString(): String = if (raw < 0) "BodyHandle(none)" else "BodyHandle($raw)"

    public companion object {
        /** The absence of a body. What an entity's `PhysicsBody.handle` holds before a build. */
        public val NONE: BodyHandle = BodyHandle(-1)
    }
}

/** What the solver does with a body. */
public enum class BodyKind {
    /** Never moves. Level geometry, walls, sensors bolted to the map. */
    Static,

    /** Moves only when told to. Moving platforms; unaffected by forces. */
    Kinematic,

    /** Integrated by the solver. Debris and server-only projectiles, and nothing predicted. */
    Dynamic,
}

/**
 * Position and rotation, as a reusable out-parameter.
 *
 * Mutable, and deliberately: [PhysicsWorld.poseOf] is a per-tick read for a sensor-heavy
 * simulation, so a caller keeps one of these as a field and fills it, rather than allocating
 * one per query against a budget the spec gates at zero. Two floats and an angle in radians —
 * no `Vector2`, because `udea-core` has no gdx-math and a kernel that named one would put
 * LibGDX on every consumer's classpath.
 */
public class BodyPose(
    public var x: Float = 0f,
    public var y: Float = 0f,
    /** Radians. */
    public var angle: Float = 0f,
) {
    /** Overwrites this pose and returns it, so a fill reads as one expression. */
    public fun set(x: Float, y: Float, angle: Float): BodyPose {
        this.x = x
        this.y = y
        this.angle = angle
        return this
    }

    override fun toString(): String = "BodyPose(x=$x, y=$y, angle=$angle)"
}

/** Linear and angular velocity, as a reusable out-parameter. See [BodyPose]. */
public class BodyVelocity(
    public var linearX: Float = 0f,
    public var linearY: Float = 0f,
    /** Radians per second. */
    public var angular: Float = 0f,
) {
    public fun set(linearX: Float, linearY: Float, angular: Float): BodyVelocity {
        this.linearX = linearX
        this.linearY = linearY
        this.angular = angular
        return this
    }

    override fun toString(): String = "BodyVelocity(x=$linearX, y=$linearY, angular=$angular)"
}

/**
 * What a raycast found, as a reusable out-parameter.
 *
 * Only meaningful when [PhysicsWorld.raycast] returned true — a miss leaves the previous
 * contents alone rather than writing a sentinel, so reading this without checking the return
 * value reports the *previous* hit, which is why the return value is not optional.
 */
public class RayHit(
    public var body: BodyHandle = BodyHandle.NONE,
    public var pointX: Float = 0f,
    public var pointY: Float = 0f,
    public var normalX: Float = 0f,
    public var normalY: Float = 0f,
    /** Where along the segment the hit landed, in `0..1`. */
    public var fraction: Float = 0f,
) {
    override fun toString(): String =
        "RayHit($body at ($pointX, $pointY) normal ($normalX, $normalY) fraction=$fraction)"
}

/**
 * A growable, reusable buffer of [BodyHandle]s.
 *
 * A `MutableList<BodyHandle>` would box every handle — a value class in a generic position is
 * boxed — once per overlapping body, once per query, on a per-tick path. This holds the raw
 * ints and hands back typed handles.
 */
public class BodyHandleBuffer(initialCapacity: Int = DEFAULT_CAPACITY) {

    private var raw = IntArray(initialCapacity.coerceAtLeast(1))

    /** How many handles are currently held. */
    public var size: Int = 0
        private set

    public operator fun get(index: Int): BodyHandle {
        require(index in 0 until size) { "index $index is outside 0 until $size" }
        return BodyHandle(raw[index])
    }

    /** Appends [handle], growing the backing array if it is full. Called by implementations. */
    public fun add(handle: BodyHandle) {
        if (size == raw.size) raw = raw.copyOf(raw.size * 2)
        raw[size] = handle.raw
        size++
    }

    /** Empties the buffer without releasing its capacity, which is the point of reusing one. */
    public fun clear() {
        size = 0
    }

    override fun toString(): String = "BodyHandleBuffer(size=$size, capacity=${raw.size})"

    private companion object {
        /** More overlaps than a sensor query realistically returns, without a regrow. */
        const val DEFAULT_CAPACITY: Int = 16
    }
}
