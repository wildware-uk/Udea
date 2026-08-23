package dev.wildware.udea.core.movement

import kotlin.math.sqrt

/**
 * The authoritative movement model: a capsule swept against [StaticCollision], allocation-free,
 * replayable, and bit-identical on server and client (spec 3.4).
 *
 * ## What it replaces, and why the replacement is not optional
 *
 * `CharacterControllerSystem` applied a Box2D impulse and let the solver decide where the
 * character ended up (`common/.../CharacterControllerSystem.kt:33`). Three consequences, and all
 * three are fatal to prediction:
 *
 * - the result depends on solver state - warm-start impulses, island order, contact caches - that
 *   no snapshot carries, so a tick cannot be replayed from a restored snapshot;
 * - Box2D is not bit-identical across machines, so a client that predicted a move and a server
 *   that authoritatively made the same move disagree by construction;
 * - the speed came from the `gameScreen` global at construction time (`:16`), so two characters
 *   could not differ and no snapshot could carry the difference.
 *
 * Here a tick of movement is `move(state, intent, config, geometry, dt)` and nothing else: every
 * input is a parameter, every output is in [MoverState], and [MoverState] is seven primitives.
 * Restore the state, re-supply the intents, and the same positions come back - which is the
 * primitive Phase 4's reconciliation is built on and is why `CharacterMoverReplayTest` exists.
 *
 * ## The float operation order is part of the contract
 *
 * Phase 7 replays this across Windows and Linux. A reassociated expression - `a * (b * c)` where
 * the other machine wrote `(a * b) * c` - is a divergence of one ulp that grows over six hundred
 * ticks and is unfindable after the fact. So:
 *
 * - **every value is `Float`.** Nothing is widened to `Double` and narrowed back. The one call
 *   into the JDK is [sqrt], which `Math.sqrt` specifies as correctly rounded, and computing a
 *   `Float` square root in `Double` and rounding once cannot double-round: `2 * 24 + 2 <= 53`.
 * - **no transcendentals.** No `sin`, `cos`, `atan2`, `pow`. `Math` does not specify those to the
 *   last bit (only `StrictMath` does), and this needs none of them.
 * - **no fused multiply-add**, and no reassociation. Every expression below is written in the
 *   order it is evaluated, and JDK 17 evaluates `float` expressions under strict IEEE-754 by
 *   default - `strictfp` became the only semantics in Java 17, which is the pin this repository
 *   builds against.
 * - **contacts are resolved in ascending segment index**, because resolution is sequential and
 *   order-dependent: a corner is two walls that disagree, and the one applied second wins.
 *   [StaticCollision.query] promises that order and [CollisionScratch] restores it.
 * - **iteration counts are fixed constants**, never "until converged": a loop that ran until a
 *   tolerance was met would run a different number of times on two machines the moment they
 *   differed by one ulp, and the difference would then amplify instead of staying at one ulp.
 *
 * ## Why the sweep is substepped depenetration and not a time-of-impact solve
 *
 * An exact capsule-vs-segment time of impact is a quartic. Solving one per candidate per tick is
 * both slower and - because the root finder is iterative - the exact thing the paragraph above
 * forbids. Instead the motion is cut into substeps no longer than half a radius, and after each
 * substep the capsule is pushed out of anything it overlaps and its velocity is projected onto
 * the contact normals. Half a radius is what makes tunnelling impossible: a capsule cannot pass a
 * segment without, at some substep, overlapping it.
 *
 * The substep count is capped at [MAX_SUBSTEPS], so a single call moves at most
 * [maxTravelPerMove] world units. That cap is a **documented refusal to tunnel**, not a silent
 * one: past it the mover moves slower than asked rather than through a wall. At 60Hz with the
 * default radius that is 96 units per second, well above any character speed; a game that wants
 * more should raise the radius or move a projectile through `PhysicsWorld` instead.
 *
 * ## Allocation
 *
 * [move] allocates nothing. The one allocation this class ever makes is its [CollisionScratch],
 * sized from the geometry, and it is made only when [move] first sees geometry larger than the
 * scratch it already holds - so a mover that stays in one scene allocates once, ever, and a
 * mover replayed sixty times a frame allocates zero bytes. That is measured, not asserted:
 * `CharacterMoverAllocationTest` counts thread-allocated bytes across 60000 calls.
 *
 * ## Not thread-safe, on purpose
 *
 * One mover holds one scratch and one set of contact accumulators. Two threads sharing one
 * instance would interleave in them. [StaticCollision] is immutable and *is* shared; give each
 * thread its own [CharacterMover] over it.
 */
public class CharacterMover {

    /** Sized from the geometry on first use, and grown if a bigger scene arrives. */
    private var scratch: CollisionScratch = CollisionScratch(0)

    // --- contact accumulators, reset at the top of every `move` ---------------------------

    private var groundNormalX: Float = 0f

    private var groundNormalY: Float = 0f

    private var grounded: Boolean = false

    /** How many contacts the last [move] resolved. A diagnostic, not simulation state. */
    public var lastContactCount: Int = 0
        private set

    // --- closest-point scratch, written by `closestBetween` --------------------------------

    private var coreX: Float = 0f

    private var coreY: Float = 0f

    private var wallX: Float = 0f

    private var wallY: Float = 0f

    // --- saved pose, for the step-down probe ------------------------------------------------

    private var savedX: Float = 0f

    private var savedY: Float = 0f

    private var savedVelocityX: Float = 0f

    private var savedVelocityY: Float = 0f

    /**
     * Advances [state] by exactly one tick of [dt] seconds, in place.
     *
     * The order below is the model, and it is fixed:
     *
     * 1. horizontal velocity converges on `intent.axis * config.maxSpeed`, limited by
     *    `config.acceleration * dt`;
     * 2. gravity is subtracted from vertical velocity - always, so a mover standing on a slope
     *    keeps being pressed into it and does not drift off;
     * 3. a jump held while [MoverState.grounded] *was* true replaces vertical velocity outright;
     * 4. the motion is substepped, and after each substep the capsule is depenetrated from every
     *    overlapping segment in ascending index order, its velocity projected onto each contact
     *    normal it is moving into;
     * 5. a mover that was grounded, is no longer, and is not moving upwards probes down by
     *    [MoverConfig.stepDownHeight] and keeps the result only if it lands.
     *
     * @param dt seconds. The simulation's fixed step; a variable value here is a variable result
     *   and defeats the whole class.
     */
    public fun move(
        state: MoverState,
        intent: MoveIntent,
        config: MoverConfig,
        geometry: StaticCollision,
        dt: Float,
    ) {
        require(config.radius > 0f) {
            "a mover's radius bounds its substep length, so it must be positive; was ${config.radius}"
        }
        require(config.halfHeight >= 0f) { "halfHeight must not be negative, was ${config.halfHeight}" }
        require(dt >= 0f && dt.isFinite()) { "dt must be a finite, non-negative step, was $dt" }
        ensureScratch(geometry)

        // 1. horizontal acceleration toward the requested speed.
        val target = intent.axis * config.maxSpeed
        val limit = config.acceleration * dt
        val delta = target - state.velocityX
        val applied = if (delta > limit) limit else if (delta < -limit) -limit else delta
        state.velocityX = state.velocityX + applied

        // 2. gravity, unconditionally.
        state.velocityY = state.velocityY - config.gravity * dt

        // 3. jump, from the grounded flag the *previous* move left behind.
        val wasGrounded = state.grounded
        if (intent.jump && wasGrounded) state.velocityY = config.jumpSpeed

        grounded = false
        groundNormalX = 0f
        groundNormalY = 0f
        lastContactCount = 0

        // 4. substepped sweep.
        val maxStep = config.radius * SUBSTEP_FRACTION
        val travelX = state.velocityX * dt
        val travelY = state.velocityY * dt
        val travel = sqrt(travelX * travelX + travelY * travelY)
        var substeps = 1
        if (travel > maxStep) {
            substeps = (travel / maxStep).toInt() + 1
            if (substeps > MAX_SUBSTEPS) substeps = MAX_SUBSTEPS
        }
        val slice = dt / substeps.toFloat()

        var step = 0
        while (step < substeps) {
            advance(state, state.velocityX * slice, state.velocityY * slice, maxStep)
            resolveContacts(state, config, geometry)
            step++
        }

        // 5. step down, so walking off a tread does not become a fall.
        if (wasGrounded && !grounded && state.velocityY <= 0f && config.stepDownHeight > 0f) {
            probeDown(state, config, geometry, maxStep)
        }

        state.grounded = grounded
        state.groundNormalX = groundNormalX
        state.groundNormalY = groundNormalY
    }

    /**
     * The furthest one [move] can carry a mover with this config, in world units.
     *
     * Public because it is the number a game has to check its speeds against: ask for more in one
     * tick and the mover moves this far instead. See the class KDoc on why the cap exists.
     */
    public fun maxTravelPerMove(config: MoverConfig): Float =
        config.radius * SUBSTEP_FRACTION * MAX_SUBSTEPS.toFloat()

    /**
     * Translates by ([dx], [dy]), with the length of a single translation capped at [maxStep].
     *
     * The cap is the tunnelling guarantee. [move] already sizes the substeps so that this cap is
     * not reached in the ordinary case; it bites only when the requested travel exceeded
     * [maxTravelPerMove], and it is what makes "cannot tunnel" true rather than usually true.
     */
    private fun advance(state: MoverState, dx: Float, dy: Float, maxStep: Float) {
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared > maxStep * maxStep) {
            val length = sqrt(lengthSquared)
            val scale = maxStep / length
            state.x = state.x + dx * scale
            state.y = state.y + dy * scale
        } else {
            state.x = state.x + dx
            state.y = state.y + dy
        }
    }

    /**
     * Pushes the capsule out of everything it overlaps, up to [MAX_DEPENETRATION_ITERATIONS]
     * times, and projects velocity onto each contact normal it is moving into.
     *
     * Sequential, not simultaneous: each push moves the capsule before the next segment is
     * tested, so a corner resolves to the position the *second* wall dictates. That is
     * order-dependent, which is why the order is pinned to the segment index and not to whatever
     * the broadphase happened to emit.
     *
     * The iteration count is fixed. A convergence test would run a machine-dependent number of
     * times, and a mover wedged into a seam between two colliders is precisely the case where two
     * machines would first disagree about whether it had converged.
     */
    private fun resolveContacts(state: MoverState, config: MoverConfig, geometry: StaticCollision) {
        if (geometry.segmentCount == 0) return
        val reach = config.radius + SKIN
        var iteration = 0
        while (iteration < MAX_DEPENETRATION_ITERATIONS) {
            val found = geometry.query(
                state.x - reach,
                state.y - config.halfHeight - reach,
                state.x + reach,
                state.y + config.halfHeight + reach,
                scratch,
            )
            var pushed = false
            var candidate = 0
            while (candidate < found) {
                if (resolveOne(state, config, geometry, scratch.indices[candidate], reach)) pushed = true
                candidate++
            }
            if (!pushed) return
            iteration++
        }
    }

    /** Resolves one segment against the capsule. Returns whether it pushed. */
    private fun resolveOne(
        state: MoverState,
        config: MoverConfig,
        geometry: StaticCollision,
        segment: Int,
        reach: Float,
    ): Boolean {
        closestBetween(
            state.x,
            state.y - config.halfHeight,
            state.x,
            state.y + config.halfHeight,
            geometry.startX(segment),
            geometry.startY(segment),
            geometry.endX(segment),
            geometry.endY(segment),
        )
        var normalX = coreX - wallX
        var normalY = coreY - wallY
        val distanceSquared = normalX * normalX + normalY * normalY
        if (distanceSquared >= reach * reach) return false

        val distance: Float
        if (distanceSquared > DEGENERATE) {
            distance = sqrt(distanceSquared)
            val inverse = 1f / distance
            normalX = normalX * inverse
            normalY = normalY * inverse
        } else {
            // The capsule's core lies exactly on the wall, so the difference vector carries no
            // direction. Fall back to the wall's own left normal, flipped to oppose the motion:
            // any other choice would push the mover further through the wall it is inside.
            val wallDeltaX = geometry.endX(segment) - geometry.startX(segment)
            val wallDeltaY = geometry.endY(segment) - geometry.startY(segment)
            val wallLength = sqrt(wallDeltaX * wallDeltaX + wallDeltaY * wallDeltaY)
            normalX = -wallDeltaY / wallLength
            normalY = wallDeltaX / wallLength
            if (normalX * state.velocityX + normalY * state.velocityY > 0f) {
                normalX = -normalX
                normalY = -normalY
            }
            distance = 0f
        }

        val push = reach - distance
        state.x = state.x + normalX * push
        state.y = state.y + normalY * push

        val approach = state.velocityX * normalX + state.velocityY * normalY
        if (approach < 0f) {
            state.velocityX = state.velocityX - approach * normalX
            state.velocityY = state.velocityY - approach * normalY
        }

        if (normalY >= config.minGroundNormalY && normalY > groundNormalY) {
            grounded = true
            groundNormalX = normalX
            groundNormalY = normalY
        }
        lastContactCount++
        return true
    }

    /**
     * Reaches down by [MoverConfig.stepDownHeight] and keeps the result only if it finds ground.
     *
     * Without this, a mover walking off the top tread of a staircase leaves the ground for a tick,
     * gravity gets a tick to act, and it arrives at the next tread already falling - so a
     * staircase is descended in a series of small arcs and `grounded` flickers, which any
     * animation or ability that gates on it then flickers with.
     *
     * The probe is a real move: it is substepped and depenetrated exactly as the main sweep is, so
     * it cannot step down *through* a floor. On a miss, the pose is restored bit for bit from the
     * saved fields, which is why they are saved rather than recomputed.
     */
    private fun probeDown(
        state: MoverState,
        config: MoverConfig,
        geometry: StaticCollision,
        maxStep: Float,
    ) {
        savedX = state.x
        savedY = state.y
        savedVelocityX = state.velocityX
        savedVelocityY = state.velocityY

        var substeps = (config.stepDownHeight / maxStep).toInt() + 1
        if (substeps > MAX_SUBSTEPS) substeps = MAX_SUBSTEPS
        val slice = config.stepDownHeight / substeps.toFloat()

        var step = 0
        while (step < substeps && !grounded) {
            advance(state, 0f, -slice, maxStep)
            resolveContacts(state, config, geometry)
            step++
        }

        if (!grounded) {
            state.x = savedX
            state.y = savedY
            state.velocityX = savedVelocityX
            state.velocityY = savedVelocityY
        }
    }

    /**
     * Closest points between segment `p0 -> p1` and segment `q0 -> q1`, into [coreX]..[wallY].
     *
     * The standard clamped-parameter solve (Ericson, *Real-Time Collision Detection* §5.1.9),
     * written out in `Float` with the branches in a fixed order. Degenerate inputs - a
     * zero-length capsule core, which [MoverConfig.halfHeight] `= 0` produces - fall into the
     * explicit branches rather than dividing by zero.
     */
    private fun closestBetween(
        p0x: Float,
        p0y: Float,
        p1x: Float,
        p1y: Float,
        q0x: Float,
        q0y: Float,
        q1x: Float,
        q1y: Float,
    ) {
        val dPx = p1x - p0x
        val dPy = p1y - p0y
        val dQx = q1x - q0x
        val dQy = q1y - q0y
        val rx = p0x - q0x
        val ry = p0y - q0y
        val a = dPx * dPx + dPy * dPy
        val e = dQx * dQx + dQy * dQy
        val f = dQx * rx + dQy * ry

        var s: Float
        var t: Float
        if (a <= DEGENERATE && e <= DEGENERATE) {
            s = 0f
            t = 0f
        } else if (a <= DEGENERATE) {
            s = 0f
            t = clamp01(f / e)
        } else {
            val c = dPx * rx + dPy * ry
            if (e <= DEGENERATE) {
                t = 0f
                s = clamp01(-c / a)
            } else {
                val b = dPx * dQx + dPy * dQy
                val denominator = a * e - b * b
                s = if (denominator > DEGENERATE) clamp01((b * f - c * e) / denominator) else 0f
                t = (b * s + f) / e
                if (t < 0f) {
                    t = 0f
                    s = clamp01(-c / a)
                } else if (t > 1f) {
                    t = 1f
                    s = clamp01((b - c) / a)
                }
            }
        }

        coreX = p0x + dPx * s
        coreY = p0y + dPy * s
        wallX = q0x + dQx * t
        wallY = q0y + dQy * t
    }

    private fun clamp01(value: Float): Float =
        if (value < 0f) 0f else if (value > 1f) 1f else value

    /**
     * Sizes the candidate buffer for [geometry].
     *
     * The only allocation this class performs, and it happens when a mover first meets a scene
     * bigger than the last one. Growing rather than sizing at construction because a mover
     * outlives a scene: it is per-thread workspace, and the scene under it is swapped between
     * ticks.
     */
    private fun ensureScratch(geometry: StaticCollision) {
        if (scratch.capacity < geometry.segmentCount) {
            scratch = CollisionScratch(geometry.segmentCount)
        }
    }

    override fun toString(): String = "CharacterMover(scratch=${scratch.capacity})"

    public companion object {

        /**
         * The longest a single substep may translate, as a fraction of the radius.
         *
         * Below `1` by necessity, not by taste: a translation of a whole radius can place the
         * capsule's core on the far side of a segment it never overlapped at either endpoint.
         * A half leaves the overlap unmissable.
         */
        public const val SUBSTEP_FRACTION: Float = 0.5f

        /** The hard cap on substeps per [move]. With [SUBSTEP_FRACTION], four radii of travel. */
        public const val MAX_SUBSTEPS: Int = 8

        /**
         * Depenetration passes per substep. Fixed, never "until converged" - see the class KDoc.
         *
         * Four is what a capsule wedged into a corner between two walls and a floor needs: one
         * pass per wall plus one to settle the pair, plus one spare. A fifth changes nothing that
         * has been observed and costs a broadphase query per substep.
         */
        public const val MAX_DEPENETRATION_ITERATIONS: Int = 4

        /**
         * The separation a resolved contact is left with, in world units.
         *
         * A capsule left touching a wall at exactly zero distance re-reports the contact on every
         * subsequent pass, because floating-point equality at the boundary goes either way. A
         * tenth of a millimetre is below anything a player can see and above anything `Float`
         * loses at the coordinate magnitudes a level uses.
         */
        public const val SKIN: Float = 1e-4f

        /**
         * Below this squared length a vector is treated as having no direction.
         *
         * Used for both the degenerate-segment branches and the zero-distance contact fallback,
         * so there is one number rather than two that could drift apart.
         */
        private const val DEGENERATE: Float = 1e-12f
    }
}
