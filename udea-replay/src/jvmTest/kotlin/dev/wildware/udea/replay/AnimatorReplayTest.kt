package dev.wildware.udea.replay

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.fixtures.QueueingSceneManager
import dev.wildware.udea.core.fixtures.RecordingCueSink
import dev.wildware.udea.core.fixtures.RecordingPhysicsWorld
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.loop.simBarrier
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Loop
import dev.wildware.udea.core.ticks
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A replay of an animated session reproduces the same animation state (issue #241).
 *
 * The world is real in every part that matters: a Fleks world stepped by `WorldSimulation`, the
 * `Animator` registered through its own `snapshotType()`, and a hash taken by `SnapshotService` and
 * `WorldHasher` - the same capture a desync report, a rewind and a replay digest use. The only
 * thing the input reaches is the `Animator`s, so a hash that follows the input is a hash that
 * follows the animation state.
 *
 * The pilot plays, crossfades and speeds up clips on three foxes, from recorded presses and a
 * recorded axis. Speeds are floats taken straight off that axis, so the replay exercises the
 * float-speed clip time on values nobody chose; and a clip played once hands over to `Walk` the
 * tick `isFinished` says so, which puts the clip-time arithmetic itself on the path to the hash.
 */
class AnimatorReplayTest {

    @Test
    fun `an animated session replays bit-identically, for every pilot`() {
        for (pilot in PILOTS) {
            val recorded = record(TICKS, Random(pilot))
            val recording = ReplayRecording.decode(recorded.recording.encode())

            val verification = ReplayVerifier.verify(recording, worlds(), IDENTITY)

            assertEquals(TICKS, verification.ticksCompared, "pilot $pilot compared the wrong length")
            assertTrue(verification.isBitExact, "pilot $pilot: ${verification.describe()}")
            // A session with nothing happening in it would replay bit-identically too.
            val stats = recorded.stats
            assertTrue(stats.crossfades > MIN_EVENTS, "pilot $pilot crossfaded only ${stats.crossfades} times")
            assertTrue(stats.plays > MIN_EVENTS, "pilot $pilot played only ${stats.plays} clips")
            assertTrue(stats.finishes > MIN_EVENTS, "pilot $pilot saw only ${stats.finishes} clips finish")
            println(
                "[animator-replay] pilot $pilot: ${verification.ticksCompared} ticks bit-exact, " +
                    "${stats.plays} plays, ${stats.crossfades} crossfades, ${stats.finishes} finishes",
            )
        }
    }

    /**
     * The half that shows the hash is about the animation at all.
     *
     * One recorded press moved, at a known tick, from one fox to another. Only an `Animator`
     * changes as a result, and the replay must say so at exactly that tick - the director acts on
     * the tick it reads the input, so there is no latency to allow for.
     */
    @Test
    fun `a replay fed one different press diverges at exactly that tick`() {
        val original = record(TICKS, Random(PILOTS.first())).recording
        val at = original.firstTick + (TICKS / 3).toLong()
        val altered = rerecord(original) { tick, sample ->
            if (tick == at) {
                sample.setAxis(AXIS_PICK, -sample.axisX(AXIS_PICK) + NUDGE, sample.axisY(AXIS_PICK))
                sample.setPressCount(ACTION_RUN, (sample.pressCount(ACTION_RUN) + 1) and PRESS_MASK)
            }
        }

        val verification = ReplayVerifier.verify(ReplayRecording.decode(altered.encode()), worlds(), IDENTITY)

        assertFalse(verification.isBitExact, "a changed crossfade replayed to the original hashes")
        assertEquals(at, verification.firstDivergentTick, verification.describe())
    }

    // ---- the world ------------------------------------------------------------------------

    /** What the director did over a session, so a test can refuse one where nothing happened. */
    private class Stats {
        var plays: Int = 0
        var crossfades: Int = 0
        var finishes: Int = 0
    }

    /** This tick's pilot input, as [DirectorSystem] reads it. */
    private class PilotInput {
        var pick: Float = 0f
        var speed: Float = 0f
        val presses = IntArray(ACTIONS.size)

        fun readFrom(sample: InputSample) {
            pick = sample.axisX(AXIS_PICK)
            speed = sample.axisY(AXIS_PICK)
            for (action in ACTIONS.indices) presses[action] = sample.pressCount(action)
        }
    }

    /**
     * Directs every fox through the clip API and nothing else.
     *
     * A press plays or crossfades a clip on the fox the axis picks, at a speed off the other half
     * of the axis; a fox whose once-through clip has finished crossfades back to walking.
     */
    private class DirectorSystem(private val input: PilotInput, private val stats: Stats) : SimSystem() {

        private val foxes: Family = world.family { all(Animator) }
        private val seen = IntArray(ACTIONS.size)

        override fun onTick() {
            val now = tick
            val entities = foxes.entities
            var index = 0
            while (index < entities.size) {
                val animator = entities[index][Animator]
                if (animator.isFinished(now)) {
                    animator.crossfade(WALK, now, over = SETTLE, loop = Loop.Repeat)
                    stats.finishes++
                }
                index++
            }

            val target: Entity = entities[pickIndex(entities.size)]
            val animator = target[Animator]
            val speed = SPEED_FLOOR + (input.speed + 1f) * SPEED_SPREAD
            if (pressed(ACTION_WALK)) {
                animator.crossfade(WALK, now, over = 8.ticks, speed = speed)
                stats.crossfades++
            }
            if (pressed(ACTION_RUN)) {
                animator.crossfade(RUN, now, over = 5.ticks, speed = speed)
                stats.crossfades++
            }
            if (pressed(ACTION_SURVEY)) {
                animator.play(SURVEY, now, loop = Loop.Once, speed = speed)
                stats.plays++
            }
            input.presses.copyInto(seen)
        }

        private fun pressed(action: Int): Boolean = input.presses[action] != seen[action]

        private fun pickIndex(count: Int): Int =
            ((input.pick + 1f) * HALF * count).toInt().coerceIn(0, count - 1)
    }

    /** Three foxes, one director, and the production capture and hash. */
    private class FoxWorld(firstTick: Tick, val stats: Stats = Stats()) : ReplayWorld {

        private val registry = ComponentRegistry(listOf(Animator.snapshotType()))
        private val netIds = NetIdIndex(capacity = ID_CAPACITY, entityCapacity = ID_CAPACITY)
        private val barrier = SimBarrier()
        private val input = PilotInput()

        private val ctx: GameContext = gameContext {
            config = EngineConfig(seed = SEED)
            rng = DefaultRngService(SEED)
            physics = RecordingPhysicsWorld()
            scenes = QueueingSceneManager(SceneId("fox"))
            cues = RecordingCueSink()
            simBarrier(barrier)
        }

        private val fleks: World = configureWorld {
            injectables { gameContext(ctx) }
            systems { add(DirectorSystem(input, stats)) }
        }

        private val simulation = WorldSimulation(ctx, fleks, barrier)
        private val service = SnapshotService(registry, fleks, ctx, netIds)
        private val buffer: WorldSnapshot = service.newSnapshot()

        init {
            check(ctx.clock.tick == firstTick) { "a fresh FoxWorld is at ${ctx.clock.tick}, not $firstTick" }
            repeat(FOXES) {
                val entity = fleks.entity { it += Animator().apply { play(WALK, firstTick) } }
                netIds.allocate(entity)
            }
        }

        override val tick: Tick get() = ctx.clock.tick

        override fun applyInput(samples: Array<InputSample>) = input.readFrom(samples[0])

        override fun step() = simulation.step()

        override fun hash(): Long {
            service.captureInto(buffer)
            return WorldHasher.hash(buffer)
        }

        override fun snapshot(): WorldSnapshot = service.capture()
    }

    // ---- recording --------------------------------------------------------------------------

    private class Recorded(val recording: ReplayRecording, val stats: Stats)

    private fun worlds() = ReplayWorldFactory { first -> FoxWorld(first) }

    private fun newRecorder() = ReplayRecorder(
        identityWithoutSchema = IDENTITY,
        schema = SCHEMA,
        peerCount = 1,
        gameId = "animator-replay",
        gameVersion = "1",
    )

    /**
     * [ticks] of piloted play. The pilot presses each action about one tick in its [PRESS_ODDS];
     * `Survey` is pressed most, so a once-through clip often runs to its end before the next
     * press on that fox interrupts it.
     */
    private fun record(ticks: Int, pilot: Random): Recorded {
        val world = FoxWorld(Tick.ZERO)
        val recorder = newRecorder()
        val slots = recorder.newSampleSlots()
        val counts = IntArray(ACTIONS.size)
        repeat(ticks) {
            val tick = world.tick
            slots[0].clear()
            slots[0].setAxis(AXIS_PICK, pilot.nextFloat() * 2f - 1f, pilot.nextFloat() * 2f - 1f)
            for (action in ACTIONS.indices) {
                if (pilot.nextInt(PRESS_ODDS[action]) == 0) counts[action] = (counts[action] + 1) and PRESS_MASK
                slots[0].setPressCount(action, counts[action])
            }
            world.applyInput(slots)
            world.step()
            recorder.record(tick, slots, world.hash())
        }
        return Recorded(recorder.seal(), world.stats)
    }

    /**
     * [original] with [edit] applied to each tick's input and the **original** hashes kept - a
     * recording of one session whose input was tampered with afterwards, which is what a replay
     * must refuse to agree with.
     */
    private fun rerecord(original: ReplayRecording, edit: (Tick, InputSample) -> Unit): ReplayRecording {
        val recorder = newRecorder()
        val slots = recorder.newSampleSlots()
        for (index in 0 until original.tickCount) {
            val tick = original.firstTick + index.toLong()
            original.samplesInto(tick, slots)
            edit(tick, slots[0])
            recorder.record(tick, slots, original.hashAt(tick))
        }
        return recorder.seal()
    }

    private companion object {
        const val SEED: Long = 241L
        const val TICKS: Int = 3600
        const val FOXES: Int = 3
        const val ID_CAPACITY: Int = 64
        val PRESS_ODDS: IntArray = intArrayOf(300, 300, 60)
        const val PRESS_MASK: Int = 0xFF
        const val MIN_EVENTS: Int = 10
        const val HALF: Float = 0.5f
        const val NUDGE: Float = 0.01f
        const val SPEED_FLOOR: Float = 1f
        const val SPEED_SPREAD: Float = 1.5f
        const val AXIS_PICK: Int = 0
        const val ACTION_WALK: Int = 0
        const val ACTION_RUN: Int = 1
        const val ACTION_SURVEY: Int = 2

        /** Fixed pilots rather than a wall-clock seed, so a failure names the one that failed. */
        val PILOTS: List<Long> = listOf(1L, 0x241L, 0x5EED_F0C5L)

        val ACTIONS: List<String> = listOf("fox/walk", "fox/run", "fox/survey")
        val SCHEMA: InputSchema = InputSchema(axes = listOf("fox/pick"), actions = ACTIONS)
        val IDENTITY: BuildIdentity = BuildIdentity(
            rootSeed = SEED,
            protoHash = 0x0241,
            assetGraphHash = "animator-replay".toByteArray(Charsets.UTF_8),
            inputSchemaHash = SCHEMA.hash,
        )

        /** The Fox's clips, as the accessor generator writes them for `Fox.glb`. */
        val SURVEY = AnimationClip(index = 0, name = "Survey", length = Ticks(205L))
        val WALK = AnimationClip(index = 1, name = "Walk", length = Ticks(43L))
        val RUN = AnimationClip(index = 2, name = "Run", length = Ticks(70L))
        val SETTLE: Ticks = 10.ticks
    }
}
