package dev.wildware.udea.core.fixtures

import dev.wildware.udea.core.Tick

/**
 * A mutable 2D vector, mutated in place.
 *
 * A local stand-in for LibGDX's `Vector2` — `udea-core` has no graphics on its classpath —
 * and it matters that it is mutable. `Transform.position` is mutated by `position.set(...)`
 * and by physics write-back, so **no setter ever fires for the field that matters most**.
 * That fact is why replication is capture-and-diff and not setter instrumentation (spec 5,
 * "Dirty determination"), and the executable specification has to exhibit it.
 */
public class Vec2(
    public var x: Float = 0f,
    public var y: Float = 0f,
) {
    public fun set(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    override fun equals(other: Any?): Boolean =
        other is Vec2 && other.x == x && other.y == y

    override fun hashCode(): Int = 31 * x.toRawBits() + y.toRawBits()

    override fun toString(): String = "Vec2($x, $y)"
}

/**
 * The component from spec 3.1, hand-written.
 *
 * In the real tree this carries `@Replicated`, `@Net position`,
 * `@Net @Q(bits = 12, min = -3.1416f, max = 3.1416f) rotation`
 * and `@Sim lastGroundedTick`, and `udea-codegen` emits its `Replicator`. Here the masks are
 * written by hand in [TransformReplicator] and the annotations are named in documentation
 * only: this module is frozen *before* any component is annotated, which is the point of
 * freezing it in Phase 0.
 */
public class Transform(
    /** `@Net` — replicated and snapshotted. */
    public val position: Vec2 = Vec2(),
    /** `@Net @Q(bits = 12, min = -3.1416f, max = 3.1416f)` — replicated and snapshotted. */
    public var rotation: Float = 0f,
    /** `@Sim` — snapshotted only. Must rewind; must never reach a client. */
    public var lastGroundedTick: Tick = Tick.ZERO,
) {
    override fun toString(): String =
        "Transform(position=$position, rotation=$rotation, lastGroundedTick=$lastGroundedTick)"
}
