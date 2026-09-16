package dev.wildware.udea.replay

import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.WorldSnapshot
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The record-replay-verify loop, on a simulation small enough to reason about completely.
 *
 * ## Why a toy simulation and not `moba`
 *
 * Because the two questions are different. `moba`'s `MobaReplayProofTest` asks "does the real
 * game, with its twenty-seven AI units and its ability system, reproduce". This asks "does the
 * machinery report the truth", and it has to be able to *make the machinery wrong on purpose* -
 * feed a replay one altered input, one extra random draw, one dropped tick - and see the exact
 * tick come back. A real game cannot be perturbed that precisely.
 *
 * [Toy] is deliberately sensitive: its state depends on the input **and** on a draw from a
 * seeded [RngStream], so a replay that ignored the recorded input and a replay that ran the RNG
 * a different number of times are both caught, and they are caught on the tick they happened.
 */
class ReplayEngineTest {

    /**
     * A one-line simulation: fold this tick's input and one random draw into a running value.
     *
     * It is not a Fleks world and does not pretend to be. [snapshot] returns `null`, which is a
     * legal [ReplayWorld] and is exactly the case `ReplayVerification.fieldsAvailable` exists to
     * report honestly - a caller gets the tick and both hashes and is told, in words, that no
     * baseline could name the fields.
     */
    private class Toy(private val seed: Long, override var tick: Tick) : ReplayWorld {

        private val rng = DefaultRngService(seed)
        private var state: Long = OFFSET_BASIS
        private var pending: Long = 0L

        override fun applyInput(samples: Array<InputSample>) {
            var folded = 0L
            for (sample in samples) {
                for (axis in 0 until sample.schema.axisCount) {
                    folded = folded * PRIME xor sample.axisX(axis).toRawBits().toLong()
                    folded = folded * PRIME xor sample.axisY(axis).toRawBits().toLong()
                }
                for (action in 0 until sample.schema.actionCount) {
                    folded = folded * PRIME xor (if (sample.isPressed(action)) 1L else 0L)
                    folded = folded * PRIME xor sample.pressCount(action).toLong()
                }
            }
            pending = folded
        }

        override fun step() {
            state = (state xor pending) * PRIME
            state = (state xor rng.nextLong(RngStream.Combat)) * PRIME
            tick += 1L
        }

        override fun hash(): Long = state

        override fun snapshot(): WorldSnapshot? = null

        private companion object {
            const val OFFSET_BASIS: Long = -0x340d631b7bdddcdbL
            const val PRIME: Long = 0x100000001b3L
        }
    }

    private val schema = InputSchema(
        axes = listOf("toy/move"),
        actions = listOf("toy/fire", "toy/jump"),
    )

    private val identity = BuildIdentity(
        rootSeed = SEED,
        protoHash = 0x1234,
        assetGraphHash = ByteArray(8) { 7 },
        inputSchemaHash = schema.hash,
    )

    private fun worlds() = ReplayWorldFactory { firstTick -> Toy(SEED, firstTick) }

    /**
     * Records [ticks] ticks of [Toy] driven by wall-clock-seeded input.
     *
     * The pilot is a `java.util.Random` on `System.nanoTime()` for the same reason `moba`'s is:
     * an input stream a replay could reconstruct from the seed would make the recording itself
     * redundant, and every assertion below would pass with the file deleted.
     */
    private fun record(ticks: Int, pilot: Random = Random(System.nanoTime())): ReplayRecording {
        val recorder = ReplayRecorder(
            identityWithoutSchema = identity,
            schema = schema,
            peerCount = 1,
            gameId = "toy",
            gameVersion = "1",
        )
        val world = Toy(SEED, FIRST)
        val slots = recorder.newSampleSlots()
        repeat(ticks) {
            val tick = world.tick
            slots[0].clear()
            slots[0].setAxis(0, pilot.nextInt(3) - 1f, pilot.nextInt(3) - 1f)
            slots[0].setPressed(0, pilot.nextBoolean())
            slots[0].setPressCount(1, pilot.nextInt(3))
            world.applyInput(slots)
            world.step()
            recorder.record(tick, slots, world.hash())
        }
        return recorder.seal()
    }

    @Test
    fun `a recording replays bit-identically, five separate matches`() {
        var passed = 0
        val failures = ArrayList<String>()
        repeat(RUNS) { run ->
            val recording = ReplayRecording.decode(record(TICKS).encode())
            val verification = ReplayVerifier.verify(recording, worlds(), identity)
            assertEquals(TICKS, verification.ticksCompared, "run $run compared the wrong length")
            if (verification.isBitExact) passed++ else failures += "run $run: ${verification.describe()}"
        }
        println("[toy] PASS RATE $passed/$RUNS over $TICKS ticks each")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The mutation that proves the verifier is comparing anything at all.
     *
     * One recorded axis, at a known tick, changed. The divergence must be reported at **exactly**
     * that tick - the toy folds its input into the very next hash, so unlike the game there is no
     * latency to allow for, and "exactly" is assertable.
     */
    @Test
    fun `an altered input diverges at exactly the tick it was altered`() {
        val original = record(TICKS)
        val at = original.firstTick + (TICKS / 3).toLong()
        val altered = rerecord(original) { tick, slots ->
            if (tick == at) slots[0].setAxis(0, 0.5f, -0.25f)
        }

        val verification = ReplayVerifier.verify(
            ReplayRecording.decode(altered.encode()),
            worlds(),
            identity,
        )
        assertFalse(verification.isBitExact, "the altered input replayed to the original hashes")
        assertEquals(
            at,
            verification.firstDivergentTick,
            "the divergence was reported at ${verification.firstDivergentTick}, not at the tick " +
                "that was altered",
        )
        assertFalse(
            verification.fieldsAvailable,
            "Toy has no snapshot registry, so no baseline can exist and the report must say so",
        )
        assertTrue(
            "no baseline world was available" in verification.describe(),
            "the report must state why the fields are unnamed rather than leave them empty: " +
                verification.describe(),
        )
    }

    /**
     * The other mutation: the world, not the recording.
     *
     * A world that draws one extra random value on one tick is exactly the nondeterminism the
     * whole phase is guarding against, and it changes no *field* at all - only the RNG state,
     * which `WorldHasher.hash(WorldSnapshot)` folds and the field-store overload does not. The
     * toy's hash folds the same thing, so this asserts the shape of the guarantee.
     */
    @Test
    fun `a world that draws one extra random value diverges on that tick`() {
        val recording = record(TICKS)
        val stray = recording.firstTick + (TICKS / 2).toLong()
        val factory = ReplayWorldFactory { firstTick ->
            object : ReplayWorld {
                private val inner = Toy(SEED, firstTick)
                private val extra = DefaultRngService(SEED)
                override val tick: Tick get() = inner.tick
                override fun applyInput(samples: Array<InputSample>) = inner.applyInput(samples)
                override fun step() {
                    // The stray draw comes off the *same* stream the world uses, through the
                    // world's own step, by stepping twice on one tick. Nothing else changes.
                    if (inner.tick == stray) {
                        val before = inner.tick
                        inner.step()
                        inner.tick = before
                    }
                    inner.step()
                }
                override fun hash(): Long = inner.hash()
                override fun snapshot(): WorldSnapshot? = null
            }
        }
        val verification = ReplayVerifier.verify(recording, factory, identity)
        assertEquals(
            stray,
            verification.firstDivergentTick,
            "an extra draw on $stray was reported at ${verification.firstDivergentTick}",
        )
    }

    @Test
    fun `a build that cannot replay a recording is refused naming the field`() {
        val recording = record(32)

        val otherSeed = identity.copy(rootSeed = identity.rootSeed + 1)
        val bySeed = assertFailsWith<ReplayRefusedException> {
            ReplayVerifier.refuseIfMismatched(recording, otherSeed)
        }
        assertEquals(listOf("rootSeed"), bySeed.mismatches.map { it.field })

        val otherAssets = identity.copy(assetGraphHash = ByteArray(8) { 9 })
        val byAssets = assertFailsWith<ReplayRefusedException> {
            ReplayVerifier.refuseIfMismatched(recording, otherAssets)
        }
        assertEquals(listOf("assetGraphHash"), byAssets.mismatches.map { it.field })

        val otherProto = identity.copy(protoHash = identity.protoHash + 1)
        val byProto = assertFailsWith<ReplayRefusedException> {
            ReplayVerifier.refuseIfMismatched(recording, otherProto)
        }
        assertEquals(listOf("protoHash"), byProto.mismatches.map { it.field })

        val otherSchema = identity.copy(inputSchemaHash = identity.inputSchemaHash + 1)
        val bySchema = assertFailsWith<ReplayRefusedException> {
            ReplayVerifier.refuseIfMismatched(recording, otherSchema)
        }
        assertEquals(listOf("inputSchemaHash"), bySchema.mismatches.map { it.field })

        // Every field at once, most-fundamental first, so a reader acts on the cause.
        val nothingMatches = BuildIdentity(0L, 0, ByteArray(1), 0L)
        val all = assertFailsWith<ReplayRefusedException> {
            ReplayVerifier.refuseIfMismatched(recording, nothingMatches)
        }
        assertEquals(
            listOf("rootSeed", "assetGraphHash", "protoHash", "inputSchemaHash"),
            all.mismatches.map { it.field },
        )

        // And the one that must NOT be refused: the same identity.
        ReplayVerifier.refuseIfMismatched(recording, identity)
    }

    @Test
    fun `a session seeks, steps and rewinds exactly, and rebuilds only going backwards`() {
        val recording = record(TICKS)
        ReplaySession.load(recording, worlds(), identity).use { session ->
            assertEquals(FIRST, session.tick)

            val landing = FIRST + 120L
            val forward = session.seek(landing)
            assertEquals(landing, forward.tickAfter)
            assertEquals(120, forward.ticksStepped)
            assertFalse(forward.rebuilt)
            assertTrue(forward.matchesRecording)

            val stepped = session.step(1)
            assertEquals(landing + 1L, stepped.tickAfter)
            assertEquals(1, stepped.ticksStepped)

            val back = session.rewind(101)
            assertEquals(landing - 100L, back.tickAfter)
            assertTrue(back.rebuilt, "a backwards seek must rebuild")
            assertEquals(1, session.rebuilds)
            assertTrue(back.matchesRecording, "the rebuilt world is not the same world")

            // The end tick is reachable; one past it is not.
            session.seek(recording.endTick)
            assertEquals(recording.endTick, session.tick)
            assertFailsWith<IllegalArgumentException> { session.seek(recording.endTick + 1L) }
            assertFailsWith<IllegalArgumentException> { session.seek(FIRST - 1L) }

            assertNull(session.firstDivergentTick, "a clean recording produced a divergence")
        }
    }

    @Test
    fun `a session records where it first saw a divergence and does not forget it on a rebuild`() {
        val original = record(TICKS)
        val at = original.firstTick + 40L
        val altered = rerecord(original) { tick, slots ->
            // A value the pilot cannot have produced: it only ever writes -1, 0 or 1, so an
            // altered sample that happened to match the original would make this test pass for
            // the wrong reason one run in nine.
            if (tick == at) slots[0].setAxis(0, 0.125f, -0.375f)
        }
        ReplaySession.load(altered, worlds(), identity).use { session ->
            session.seek(at + 20L)
            assertEquals(at, session.firstDivergentTick)
            session.rewind(15)
            assertEquals(
                at,
                session.firstDivergentTick,
                "a rebuild cleared a fact the session had already learned",
            )
        }
    }

    @Test
    fun `a replay world that starts on the wrong tick is refused rather than silently offset`() {
        val recording = record(16)
        val offset = ReplayWorldFactory { firstTick -> Toy(SEED, firstTick + 1L) }
        val failure = assertFailsWith<IllegalStateException> {
            ReplayVerifier.verify(recording, offset, identity)
        }
        assertTrue("starts at" in failure.message!!, failure.message!!)
    }

    /** Rebuilds a recording tick by tick, letting [alter] change a sample on the way past. */
    private fun rerecord(
        recording: ReplayRecording,
        alter: (Tick, Array<InputSample>) -> Unit,
    ): ReplayRecording {
        val recorder = ReplayRecorder(
            identityWithoutSchema = recording.header.identity,
            schema = recording.schema,
            peerCount = recording.peerCount,
            gameId = recording.header.gameId,
            gameVersion = recording.header.gameVersion,
            tickRateHz = recording.header.tickRateHz,
        )
        val slots = recording.newSampleSlots()
        for (index in 0 until recording.tickCount) {
            val tick = recording.firstTick + index.toLong()
            recording.samplesInto(tick, slots)
            alter(tick, slots)
            recorder.record(tick, slots, recording.hashAt(tick))
        }
        return recorder.seal()
    }

    private companion object {
        val FIRST: Tick = Tick(1)
        const val SEED: Long = 0x5EEDL
        const val TICKS: Int = 300
        const val RUNS: Int = 5
    }
}
