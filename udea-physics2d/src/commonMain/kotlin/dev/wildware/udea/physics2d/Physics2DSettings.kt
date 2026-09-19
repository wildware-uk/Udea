package dev.wildware.udea.physics2d

/**
 * The solver's fixed configuration, set once when the world is opened.
 *
 * Every setting that can change a result is here and is passed to Box2D explicitly, rather than
 * left to whatever Box2D's own defaults are in the version that happens to be on the classpath.
 * The step length is not here: it is one simulation tick, taken from `EngineConfig.tickRate`, and
 * nothing else steps the solver.
 *
 * Threading is not here either, and it cannot be. Box2D is multithreaded only when the world
 * definition is given a task callback and a worker count, and `box2d-jni` 1.0.0 exposes neither
 * field of `b2WorldDef` (its `TaskManager` class is never wired to a world definition). So every
 * world keeps `b2DefaultWorldDef`'s single worker and no task system, and every step runs on the
 * simulation thread. Box2D states that its results do not depend on the worker count; one worker
 * is also the configuration with no scheduler to reason about.
 */
public data class Physics2DSettings(
    /** Gravity along x, in world units per second squared. */
    public val gravityX: Float = 0f,
    /** Gravity along y, in world units per second squared. Box2D's own default is -10. */
    public val gravityY: Float = DEFAULT_GRAVITY_Y,
    /**
     * Solver sub-steps per tick. Box2D's recommended value is 4; more is stiffer stacking at a
     * proportional cost.
     */
    public val subSteps: Int = DEFAULT_SUB_STEPS,
    /**
     * Whether bodies at rest fall asleep. Sleep is deterministic, and `PhysicsBody.awake` carries
     * it across a rewind, but the time a body has spent resting is solver state (see
     * `Box2DPhysicsWorld`'s restore notes).
     */
    public val enableSleep: Boolean = true,
    /** Continuous collision between fast dynamic bodies and static geometry. */
    public val enableContinuous: Boolean = true,
) {
    init {
        require(subSteps > 0) { "subSteps must be positive, was $subSteps" }
        require(gravityX.isFinite() && gravityY.isFinite()) { "gravity must be finite, was ($gravityX, $gravityY)" }
    }

    public companion object {
        /** Box2D's own default gravity. */
        public const val DEFAULT_GRAVITY_Y: Float = -10f

        /** Box2D's recommended sub-step count. */
        public const val DEFAULT_SUB_STEPS: Int = 4
    }
}
