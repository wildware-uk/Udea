package dev.wildware.udea.core.loop

/**
 * Recording doubles for the loop tests.
 *
 * They are here rather than inside a test class because both `GameLoopFixedStepTest` and the
 * barrier tests need them, and because the point of [GameLoop] taking a [Simulation] and a
 * [Presentation] interface is precisely that it can be driven with no world and no GL.
 */

/** Counts steps. Advances nothing, so a loop test measures the loop and not a world. */
internal class RecordingSimulation(
    override val tickRate: Int = 60,
) : Simulation {

    var steps: Int = 0
        private set

    /** Runs on every [step], for the tests that need to observe a mid-tick world. */
    var onStep: (() -> Unit)? = null

    override fun step() {
        steps++
        onStep?.invoke()
    }
}

/** Keeps every alpha it was handed, in order. */
internal class RecordingPresentation : Presentation {

    private val recorded = ArrayList<Float>()

    val alphas: List<Float> get() = recorded

    val renderCount: Int get() = recorded.size

    override fun render(alpha: Float) {
        recorded += alpha
    }
}
