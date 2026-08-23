package dev.wildware.udea.core.movement

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Phase 3 movement budget: **200 movers replayed 60 times must fit inside one frame.**
 *
 * ## Where the number comes from
 *
 * Spec 3.4 says "replayable 60x per frame" and spec 3.3 fixes the simulation at 60Hz, so one
 * frame is 16.67ms. A client reconciling a 200-entity match re-runs the last second of movement
 * for every entity it predicts, every frame, and that work has to leave room for the rest of the
 * tick. The gate is therefore [BUDGET_MS] for 12000 `move` calls - a quarter of the frame - and
 * it is a *hard* gate wired into `check`, not an aspiration.
 *
 * ## The remedy when it fails is never to loosen the number
 *
 * A slower machine that cannot make this budget cannot run 200 predicted entities, and hiding
 * that behind a larger constant moves the discovery to a player's machine. The remedy is to
 * predict fewer entities, or to raise [StaticCollision.Builder]'s cell size so a query walks
 * fewer cells. Both are decisions with a visible cost; a bigger constant is not.
 *
 * The measured number is printed, because on the day this fails the useful information is how
 * far off it was, and a test report nobody opens does not carry it.
 */
class CharacterMoverBudgetTest {

    /** A quarter of a 60Hz frame. See the class KDoc before changing it. */
    private val budgetMs = BUDGET_MS

    private val movers = 200

    private val replays = 60

    @Test
    fun `200 movers replayed 60 times fit in the per-frame budget`() {
        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val intent = MoveIntent()

        // One state per mover, spread along the level so they meet different geometry - a
        // benchmark where every mover sat in the same empty cell would measure an empty query.
        val states = Array(movers) { index ->
            MoverState(x = -18f + index * 0.2f, y = 1.2f + (index % 7) * 0.1f)
        }

        // Warm up: the budget is about steady-state cost, and a cold JIT measures the interpreter.
        repeat(5) { runFrame(mover, states, intent, config, geometry) }

        val attempts = 9
        val samples = LongArray(attempts)
        for (attempt in 0 until attempts) {
            val started = System.nanoTime()
            runFrame(mover, states, intent, config, geometry)
            samples[attempt] = System.nanoTime() - started
        }
        samples.sort()
        val medianMs = samples[attempts / 2] / 1_000_000.0

        println(
            "[CharacterMoverBudgetTest] $movers movers x $replays replays " +
                "(${movers * replays} move calls) median ${"%.3f".format(medianMs)}ms, " +
                "budget ${budgetMs}ms",
        )
        assertTrue(
            medianMs < budgetMs,
            "movement took ${medianMs}ms for ${movers * replays} calls; the budget is ${budgetMs}ms",
        )
    }

    @Test
    fun `the benchmark's movers are actually colliding`() {
        // A budget met by movers in empty space is not the budget. This asserts the benchmark's
        // own setup, so a level change that moved everyone off the floor would fail here rather
        // than quietly make the gate free to pass.
        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val intent = MoveIntent()
        val states = Array(movers) { index ->
            MoverState(x = -18f + index * 0.2f, y = 1.2f + (index % 7) * 0.1f)
        }

        var contacts = 0L
        repeat(replays) { step ->
            for (state in states) {
                mover.move(state, MoverScenario.script(step, intent), config, geometry, MoverScenario.DT)
                contacts += mover.lastContactCount.toLong()
            }
        }
        assertTrue(
            contacts > movers.toLong() * replays / 4,
            "only $contacts contacts across ${movers * replays} calls; the benchmark is " +
                "measuring movers in mid-air",
        )
    }

    private fun runFrame(
        mover: CharacterMover,
        states: Array<MoverState>,
        intent: MoveIntent,
        config: MoverConfig,
        geometry: StaticCollision,
    ) {
        var step = 0
        while (step < replays) {
            MoverScenario.script(step, intent)
            for (state in states) {
                mover.move(state, intent, config, geometry, MoverScenario.DT)
            }
            step++
        }
    }

    private companion object {
        const val BUDGET_MS: Double = 4.0
    }
}
