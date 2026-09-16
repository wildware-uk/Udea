package dev.wildware.udea.net.prediction

import dev.wildware.udea.net.input.MoveInput

/**
 * A planar position a predictor advances in place: two floats and nothing else.
 *
 * Mutable and reused rather than a `data class` returned per step, because a reconciliation
 * replays every unacknowledged command on the tick the correction lands - at 150ms that is
 * eighteen steps in one tick, every tick a snapshot arrives - and eighteen allocations per tick
 * on the client's hot path is exactly the smell the charter names. The predictor owns two of
 * these for the life of the session.
 */
public class PredictedPose(

    /** World x. */
    public var x: Float = 0f,

    /** World y. */
    public var y: Float = 0f,
) {

    /** Overwrites both axes. */
    public fun set(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    /** Copies [other] into this pose. */
    public fun set(other: PredictedPose) {
        x = other.x
        y = other.y
    }

    override fun toString(): String = "PredictedPose($x, $y)"
}

/**
 * One tick of movement, as a pure function of (pose, command).
 *
 * ## Why this is an interface and not a call into the game's movement system
 *
 * Prediction is only sound if the client's step and the server's step produce the **same**
 * answer for the same command (spec 3.4). A client that predicted with its own approximation
 * of the server's rule would drift by a fixed amount every tick, and reconciliation would then
 * correct that drift forever - which is precisely what a player calls rubber-banding. So the
 * step has to be one piece of code, parameterised, with no world, no ECS, no clock and no
 * device in it. That is this.
 *
 * `udea-net` cannot reach a game's systems, and a game's systems must not reach a network
 * module, so the shared piece is a strategy the composition root supplies to both. The
 * implementation a game hands the predictor must be the identical instance - or at least the
 * identical type and constants - that its authoritative movement system runs.
 *
 * ## What an implementation must promise
 *
 * - **Pure.** The result depends on the arguments alone: no wall clock, no unseeded randomness,
 *   no `IntervalSystem.deltaTime`. Replay calls this with the same arguments a second time and
 *   must get the same pose.
 * - **`Float` throughout, in a fixed operation order.** `CharacterMover`'s KDoc states the
 *   reasons at length and they apply unchanged here: a reassociated expression is a one-ulp
 *   divergence that grows.
 * - **Allocation-free**, for the reason [PredictedPose] is mutable.
 */
public fun interface MoveModel {

    /** Advances [pose] by exactly one tick under [command]. */
    public fun step(pose: PredictedPose, command: MoveInput)
}

/**
 * The top-down move rule: `position += axis * speed`, in world units per **tick**.
 *
 * This is `moba`'s `PlayerMovementSystem` written as a pure function, and the duplication is
 * deliberate only until that system calls this - see the module report. Two things in it are
 * not obvious and both are load-bearing:
 *
 * ## It steps the axis the **server will read**, not the axis the player pressed
 *
 * `MoveInput.moveX` goes on the wire through [MoveInput.AXIS], eight bits over `-1..1`. The
 * server therefore applies `dequantise(quantise(pressed))` and never `pressed`. A predictor
 * that stepped the raw value would be wrong by up to half a quantisation step **every tick**,
 * in a fixed direction for a held key - about 0.4% of a step, which over the ~18 unacknowledged
 * ticks of a 150ms link is a standing error the reconciler corrects on every single snapshot.
 * That is a permanent, low-amplitude rubber-band, and it is invisible in a loopback test where
 * the same rounding happens on both sides. Rounding here makes the client's arithmetic
 * identical to the server's rather than merely close to it.
 *
 * ## The zero check is part of the model, not an optimisation
 *
 * `PlayerMovementSystem` returns early when both axes are zero. Adding `0f * speed` would give
 * the same answer for a positive pose and **not** for `-0.0f`, so dropping the branch would be
 * a divergence that only appears at the origin. The rule is copied, branch included.
 */
public class PlanarMoveModel(

    /** World units travelled per tick at full deflection. `UnitKind.moveSpeed` in `moba`. */
    public val speed: Float,
) : MoveModel {

    init {
        require(speed.isFinite()) { "speed must be finite, was $speed" }
    }

    override fun step(pose: PredictedPose, command: MoveInput) {
        val moveX = onWire(command.moveX)
        val moveY = onWire(command.moveY)
        if (moveX == 0f && moveY == 0f) return
        pose.x = pose.x + moveX * speed
        pose.y = pose.y + moveY * speed
    }

    override fun toString(): String = "PlanarMoveModel(speed=$speed)"

    public companion object {

        /**
         * [value] as the server will read it: through [MoveInput.AXIS] and back.
         *
         * Public because it is also what a test asserting server/client parity has to compare
         * against, and a test that recomputed the round trip itself would be asserting its own
         * copy of the wire format rather than the wire format.
         */
        public fun onWire(value: Float): Float = MoveInput.AXIS.dequantise(MoveInput.AXIS.quantise(value))
    }
}
