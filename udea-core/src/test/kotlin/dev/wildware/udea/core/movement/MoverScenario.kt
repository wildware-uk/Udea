package dev.wildware.udea.core.movement

/**
 * One level and one scripted input sequence, shared by every mover test.
 *
 * Shared deliberately: parity, replay, allocation and budget all have to be measured against the
 * *same* motion, or a parity run that never touched a wall would prove nothing about the run the
 * budget was measured on. The level below is chosen so a mover driven by [script] hits a floor, a
 * slope, an inside corner, a free-standing crate and a ceilingless wall within the first hundred
 * ticks - which is checked, not assumed, by `CharacterMoverParityTest`.
 */
internal object MoverScenario {

    /** How many ticks the scripted sequence runs for. Spec 3.4's replay window is 60 of these. */
    const val STEPS: Int = 600

    /** The fixed simulation step: 60Hz (spec 3.3). */
    const val DT: Float = 1f / 60f

    /**
     * The level.
     *
     * Built by a plain sequence of calls rather than from a collection, because segment order is
     * the contact tie-break: a level built from a `Set` or a `HashMap` would produce a different
     * order per JVM and this whole module's determinism claim with it.
     */
    fun geometry(): StaticCollision = StaticCollision.Builder(cellSize = 2f)
        .segment(-20f, 0f, 6f, 0f)
        .segment(6f, 0f, 12f, 3f)
        .segment(12f, 3f, 24f, 3f)
        .segment(24f, 3f, 24f, 14f)
        .segment(-20f, 0f, -20f, 14f)
        .box(2f, 0f, 3f, 1.2f)
        .build()

    /** The default character: a 0.8-wide, 1.8-tall capsule at a walking pace. */
    fun config(): MoverConfig = MoverConfig(
        radius = 0.4f,
        halfHeight = 0.5f,
        maxSpeed = 6f,
        acceleration = 40f,
        gravity = 24f,
        jumpSpeed = 9f,
        stepDownHeight = 0.3f,
    )

    /** Standing on the flat floor, left of the crate. */
    fun start(): MoverState = MoverState(x = -10f, y = 0.9f)

    /**
     * Writes the input for [step] into [into], and returns it.
     *
     * An integer hash rather than a `Random`: the sequence has to be the same on two independently
     * constructed movers in two JVMs, and it has to be re-derivable for a replay from any step,
     * which a stateful generator is not. This is a pure function of the step number.
     */
    fun script(step: Int, into: MoveIntent): MoveIntent {
        var hash = step * MULTIPLIER + INCREMENT
        hash = hash xor (hash ushr 16)
        hash = hash * MIXER
        hash = hash xor (hash ushr 15)
        into.move = (((hash ushr 3) % 5) - 2) / 2f
        into.jump = ((hash ushr 21) and 15) == 0
        return into
    }

    private const val MULTIPLIER: Int = 1103515245

    private const val INCREMENT: Int = 12345

    private const val MIXER: Int = 0x45d9f3b
}
