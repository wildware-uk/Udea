package dev.wildware.udea.render.input

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.WorldSimulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One sample per **tick**, whatever the frame pattern was — and the same simulation whether the
 * input came from a device or from an agent.
 *
 * ## Why this test is the point of the whole issue
 *
 * `ControllerSystem` sampled once per frame. The consequences are the three properties asserted
 * here, in the negative:
 *
 * - a second of game time contained 30 samples on a stalling machine and 144 on a fast one, so
 *   "held for ten samples" was a different duration on every machine and no recorded input stream
 *   could be replayed;
 * - a frame that caught up several ticks fed the same sample to all of them;
 * - a frame that ran no tick still sampled, so input was consumed by nothing.
 *
 * Sampling from a `SimSystem` at `SimPhase.Intent` makes all three impossible by construction,
 * and this drives a real [GameLoop] with three real frame patterns to say so.
 */
class IntentSamplingTest {

    /** 33ms frames: a stalling machine, where one frame is two ticks. */
    @Test
    fun `intent count equals tick count at 30fps`() {
        assertSampledOncePerTick(List(60) { 1f / 30f })
    }

    /** 7ms frames: a fast display, where most frames run no tick at all. */
    @Test
    fun `intent count equals tick count at 144fps`() {
        assertSampledOncePerTick(List(300) { 1f / 144f })
    }

    /**
     * A quarter-second stall in the middle of a normal run.
     *
     * `GameLoop` clamps a wall delta at `MAX_WALL_DELTA` and catches up at most `maxCatchUp`
     * ticks per frame, so the tick count here is *not* the arithmetic one - which is exactly why
     * the assertion is "samples equal ticks" rather than "samples equal a number this test
     * predicted".
     */
    @Test
    fun `intent count equals tick count across a 250ms stall`() {
        assertSampledOncePerTick(List(20) { 1f / 60f } + 0.25f + List(20) { 1f / 60f })
    }

    /** A paused loop runs no tick, so it samples no input - and consumes none. */
    @Test
    fun `a paused loop samples nothing`() {
        val fixture = Fixture()
        fixture.loop.paused = true
        repeat(30) { fixture.loop.frame(1f / 60f) }

        assertEquals(0L, fixture.state.sampleCount)
    }

    /**
     * The synthesised-input claim, run as an experiment rather than asserted in a comment.
     *
     * Two worlds, one driven by a fake keyboard and one by an [InjectedIntent] scripted to press
     * the same key on the same ticks, with no device in the process at all. If the agent's input
     * reached the simulation by a different path - the old `Gdx.input.inputProcessor` injection,
     * say - the two would agree only by luck.
     */
    @Test
    fun `a scripted injected source reproduces what a keyboard produced`() {
        val keys = FakeKeyboard()
        val fromDevice = Fixture(DeviceIntent(BINDINGS, keys))
        val injected = InjectedIntent(BINDINGS.catalog)
        val fromAgent = Fixture(injected)

        repeat(TICKS) { tick ->
            when (tick) {
                HOLD_FROM -> {
                    keys.press(KEY_FIRE)
                    injected.press(FIRE)
                }
                HOLD_UNTIL -> {
                    keys.release(KEY_FIRE)
                    injected.release(FIRE)
                }
                TAP_AT -> {
                    keys.tap(KEY_FIRE)
                    injected.tap(FIRE)
                }
            }
            fromDevice.loop.stepTicks(1)
            fromAgent.loop.stepTicks(1)
        }

        assertEquals(
            fromDevice.recorder.log,
            fromAgent.recorder.log,
            "an agent's synthesised input produced a different tick-by-tick history from a " +
                "keyboard's, which means the two are not the same path",
        )
        assertTrue(fromDevice.recorder.log.isNotEmpty())
        assertTrue(fromDevice.recorder.log.any { it.contains("edge") }, "no edge was recorded")
    }

    /** Every frame pattern, one assertion. */
    private fun assertSampledOncePerTick(frames: List<Float>) {
        val fixture = Fixture()
        frames.forEach { fixture.loop.frame(it) }

        assertTrue(fixture.loop.totalTicks > 0, "the loop ran no ticks, so this proves nothing")
        assertEquals(
            fixture.loop.totalTicks,
            fixture.state.sampleCount,
            "the loop ran ${fixture.loop.totalTicks} ticks and sampled input " +
                "${fixture.state.sampleCount} times",
        )
        assertEquals(
            fixture.state.sampleCount,
            fixture.recorder.ticks,
            "a control system saw a different number of intents than were sampled",
        )
    }

    /** A world with the sampler and one system that reads what it sampled. */
    private class Fixture(source: IntentSource = IntentSource.NONE) {

        val state: IntentState = IntentState(BINDINGS, source)

        val ctx: GameContext = testGameContext(seed = 7L)

        // Constructed *inside* `systems { }` and fetched back out. `SimSystem` resolves its
        // `GameContext` with `World.inject` in its constructor, which Fleks only permits while a
        // world is being configured - building the recorder as a field first throws
        // `FleksWrongConfigurationUsageException`, which is how this was found.
        val world: World = configureWorld {
            injectables { gameContext(ctx) }
            systems {
                add(IntentSampleSystem(state))
                add(Recorder(state))
            }
        }

        val recorder: Recorder = world.system<Recorder>()

        val loop: GameLoop = GameLoop.forWorld(WorldSimulation(ctx, world))
    }

    /** Writes down what the intent said on each tick, in tick order. */
    class Recorder(private val state: IntentState) : SimSystem() {

        val log = ArrayList<String>()

        var ticks: Long = 0L
            private set

        override fun onTick() {
            ticks++
            val intent = state.intent
            if (intent.isJustPressed(FIRE)) log += "t${tick.value} edge"
            if (intent.isPressed(FIRE)) log += "t${tick.value} held"
        }
    }

    private companion object {

        const val KEY_FIRE: Int = 62

        const val TICKS: Int = 40
        const val HOLD_FROM: Int = 5
        const val HOLD_UNTIL: Int = 12
        const val TAP_AT: Int = 20

        val BINDINGS: InputBindings = InputBindings(
            actions = listOf(ActionBinding("t/fire", keys = intArrayOf(KEY_FIRE))),
            axes = listOf(Axis2DBinding("t/move")),
        )

        val FIRE: ActionId = BINDINGS.catalog.action("t/fire")
    }
}
