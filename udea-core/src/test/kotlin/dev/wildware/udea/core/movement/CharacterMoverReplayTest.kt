package dev.wildware.udea.core.movement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reconciliation primitive Phase 4 is built on: replaying the last sixty inputs from a saved
 * [MoverState] reproduces the current state exactly.
 *
 * ## Why this is the load-bearing test and parity is only half of it
 *
 * Parity says two movers agree when they start from the same place. Reconciliation needs
 * something stronger: that a mover can be *put* into a past state and marched forward. If any
 * part of a tick's result depended on something outside [MoverState] - a cached contact, a
 * previous frame's velocity, the mover instance's own scratch - the replay would drift, and it
 * would drift on exactly the ticks where the client and server disagreed, which is when
 * reconciliation runs.
 *
 * So the replay below deliberately uses a **freshly constructed** mover for the replay and a
 * different one for the live run. A shared instance would hide any state the mover kept.
 */
class CharacterMoverReplayTest {

    /** The rollback window: spec 3.4's "replayable 60x per frame". */
    private val window = 60

    @Test
    fun `replaying the last 60 inputs from a saved state reproduces the current state exactly`() {
        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val intent = MoveIntent()

        val live = CharacterMover()
        val state = MoverScenario.start()
        val saved = MoverState()
        val savedAt = MoverScenario.STEPS - window

        for (step in 0 until MoverScenario.STEPS) {
            if (step == savedAt) saved.set(state)
            live.move(state, MoverScenario.script(step, intent), config, geometry, MoverScenario.DT)
        }

        val replayed = MoverState().set(saved)
        val replay = CharacterMover()
        for (step in savedAt until MoverScenario.STEPS) {
            replay.move(
                replayed,
                MoverScenario.script(step, intent),
                config,
                geometry,
                MoverScenario.DT,
            )
        }

        assertTrue(
            replayed.sameAs(state),
            "replay drifted: live $state, replayed $replayed",
        )
    }

    @Test
    fun `the replayed window is not a no-op`() {
        // If the last sixty inputs happened to move nothing, the test above would pass on a mover
        // that ignored its inputs. The window has to be a second of real motion.
        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val intent = MoveIntent()
        val mover = CharacterMover()
        val state = MoverScenario.start()
        val savedAt = MoverScenario.STEPS - window

        val saved = MoverState()
        for (step in 0 until MoverScenario.STEPS) {
            if (step == savedAt) saved.set(state)
            mover.move(state, MoverScenario.script(step, intent), config, geometry, MoverScenario.DT)
        }

        assertFalse(
            saved.sameAs(state),
            "the last $window ticks changed nothing, so the replay proved nothing",
        )
    }

    @Test
    fun `sixty replays from one save all land on the same state`() {
        // "Replayable 60x per frame" literally: reconciliation may re-run the window every frame,
        // and a mover that drifted a little on each re-run would look correct for one frame and
        // wrong after a second of packet loss.
        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val intent = MoveIntent()
        val mover = CharacterMover()

        val saved = MoverScenario.start()
        val reference = MoverState()
        val scratchState = MoverState()

        for (attempt in 0 until window) {
            scratchState.set(saved)
            for (step in 0 until window) {
                mover.move(
                    scratchState,
                    MoverScenario.script(step, intent),
                    config,
                    geometry,
                    MoverScenario.DT,
                )
            }
            if (attempt == 0) {
                reference.set(scratchState)
            } else {
                assertTrue(
                    scratchState.sameAs(reference),
                    "replay $attempt landed on $scratchState, replay 0 landed on $reference",
                )
            }
        }
    }

    @Test
    fun `a mover reused across two entities does not carry state between them`() {
        // The system holds one mover and steps every entity through it, so this is the shape the
        // shipped path actually runs. A mover that remembered the last entity's contacts would
        // make an entity's result depend on whichever entity was iterated before it, which Fleks
        // does not promise to keep stable across a component add.
        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val intent = MoveIntent()

        val alone = CharacterMover()
        val aloneState = MoverScenario.start()

        val shared = CharacterMover()
        val sharedState = MoverScenario.start()
        val other = MoverState(x = 20f, y = 6f)

        for (step in 0 until 240) {
            alone.move(aloneState, MoverScenario.script(step, intent), config, geometry, MoverScenario.DT)
            // The interleaved entity runs first, so any leaked state would be leaked *into* the
            // one being compared.
            shared.move(other, MoverScenario.script(step + 7, intent), config, geometry, MoverScenario.DT)
            shared.move(sharedState, MoverScenario.script(step, intent), config, geometry, MoverScenario.DT)
        }

        assertTrue(
            aloneState.sameAs(sharedState),
            "sharing a mover changed the result: alone $aloneState, shared $sharedState",
        )
    }
}
