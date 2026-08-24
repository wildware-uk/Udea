package dev.wildware.moba.replay

import dev.wildware.moba.MobaControls
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.loop.RewindResult
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState
import dev.wildware.udea.replay.BaselineSnapshots
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.ReplayRecorder
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplaySession
import dev.wildware.udea.replay.ReplayVerification
import dev.wildware.udea.replay.ReplayVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The Phase 7 proof.** Record a real `moba` match, write it to a file, replay the file, and
 * compare the two hash streams tick for tick.
 *
 * ## Why the pilot is seeded from the wall clock
 *
 * This is the part that makes the test mean anything. A recording of an *idle* player, or of a
 * player whose input is itself a function of the simulation seed, proves nothing about
 * recording: a replay would reproduce it from the seed alone and the `.udearep` file could be
 * deleted without the assertion changing. So [Pilot] draws from a `java.util.Random` seeded from
 * `System.nanoTime()`, which is the one thing in this tree a replay categorically cannot
 * reconstruct. Every run therefore records a **different** match, and the only route from the
 * recording run to the replay run is the file.
 *
 * That also makes this five separate proofs rather than one proof run five times, which matters:
 * a wave once shipped a headline that failed six of nine reruns.
 *
 * The pilot is *inside* the simulation's input seam, not inside the simulation. `IntentSource`
 * is sampled once per tick at `SimPhase.Intent` by `IntentSampleSystem`, so the wall clock
 * reaches a tick exactly the way a human's fingers do and no further - which is the whole reason
 * that seam exists.
 *
 * ## What is being compared
 *
 * `WorldHasher.hash(WorldSnapshot)` at the end of every tick, on both runs. That overload folds
 * the entity roster, the component-presence words, every lowered field of every replicated
 * component in canonical order, **and** the RNG state and the id allocator - so a run that
 * reached the same world by drawing a different number of random values fails on the tick it
 * drew them rather than seconds later when the difference finally moved something.
 *
 * ## When it fails
 *
 * [rewindBaseline] hands the verifier the record-time world at the divergent tick, by rewinding
 * the recording host's own snapshot ring to it. So a failure here is not a bare hash mismatch:
 * it is a tick, both hashes, and the exact `netId.Component.field` values the two runs disagree
 * about. That is issue #148's requirement, and `a corrupted recording is caught at the tick it
 * was corrupted` below is the mutation test that proves the machinery reports it.
 */
class MobaReplayProofTest {

    /**
     * A stand-in for a human: hold a direction for a while, change it, hit things.
     *
     * Deliberately coarse. What it must be is *unreproducible without the recording* and
     * *materially different every run*; what it must not be is a source of nondeterminism inside
     * the simulation, which is why it only ever writes into the `Intent` it is handed.
     */
    private class Pilot(private val rng: Random) : IntentSource {

        private var moveX = 0f
        private var moveY = 0f
        private var holdFor = 0

        /** Ticks this pilot has produced input for. */
        var ticks: Long = 0L
            private set

        /** Ticks on which the pilot was pushing the stick. Printed, so an idle run is visible. */
        var movingTicks: Long = 0L
            private set

        /** Attacks it asked for. Printed for the same reason. */
        var attacks: Long = 0L
            private set

        override fun sample(into: Intent) {
            if (holdFor <= 0) {
                moveX = rng.nextInt(3) - 1f
                moveY = rng.nextInt(3) - 1f
                holdFor = MIN_HOLD + rng.nextInt(HOLD_SPREAD)
            }
            holdFor--
            into.setAxis(MobaControls.MOVE_AXIS, moveX, moveY)
            if (moveX != 0f || moveY != 0f) movingTicks++

            val primary = rng.nextInt(ATTACK_ONE_IN) == 0
            val secondary = rng.nextInt(ATTACK_TWO_IN) == 0
            into.setPressed(MobaControls.ATTACK_ACTION, primary)
            into.setPressed(MobaControls.ATTACK_2_ACTION, secondary)
            if (primary) into.setPressCount(MobaControls.ATTACK_ACTION, 1)
            if (secondary) into.setPressCount(MobaControls.ATTACK_2_ACTION, 1)
            if (primary || secondary) attacks++
            ticks++
        }

        override fun toString(): String =
            "Pilot($ticks tick(s), moving $movingTicks, attacks $attacks)"

        private companion object {
            const val MIN_HOLD: Int = 12
            const val HOLD_SPREAD: Int = 36
            const val ATTACK_ONE_IN: Int = 25
            const val ATTACK_TWO_IN: Int = 60
        }
    }

    /** A recording, plus the host that made it, so a failure can be explained field by field. */
    private class Recorded(
        val recording: ReplayRecording,
        val host: GameHost,
        val pilot: Pilot,
    )

    /**
     * Runs [ticks] ticks of the real level with [pilot] driving the champion, recording as it goes.
     *
     * The order inside the loop is the contract [ReplayRecorder.record] documents: the tick about
     * to run is read first, the tick runs (which is what samples the pilot), and the world hash
     * is taken **after**. A hash taken before would describe the previous tick and the whole
     * stream would be off by one, which for a bisect is the difference between landing on the
     * cause and landing on its first consequence.
     */
    private fun record(ticks: Int, pilot: Pilot): Recorded {
        val host = MobaReplay.bootHeadless()
        val source = RecordingIntentSource(pilot)
        host.ctx[IntentState.KEY].source = source

        val recorder = MobaReplay.recorder(host)
        val slots = recorder.newSampleSlots()
        val service = snapshotServiceFor(host)
        val buffer = service.newSnapshot()

        repeat(ticks) {
            val tick = host.tick
            host.run(1)
            slots[0].copyFrom(source.sample)
            service.captureInto(buffer)
            recorder.record(tick, slots, WorldHasher.hash(buffer))
        }
        assertEquals(
            ticks.toLong(),
            source.sampleCount,
            "the recording source was sampled once per tick or the recording is not what ran",
        )
        return Recorded(recorder.seal(), host, pilot)
    }

    /**
     * A capture service over [MobaReplay.REGISTRY] - the *same object* the replay world uses.
     *
     * Not `MobaGame.componentRegistry()`: `WorldFieldStore.diffInto` compares registries by
     * identity, so a baseline captured through an equal-but-separate registry cannot be diffed
     * against the replay's snapshot at all. That is exactly what the mutation test below caught.
     */
    private fun snapshotServiceFor(host: GameHost) = MobaReplay.snapshots(host)

    /**
     * The record-time world at an arbitrary tick, by rewinding the recording host's own ring.
     *
     * Only reached when a divergence has already been found, and `null` when the ring cannot
     * reach the tick - the default sparse window is 3600 ticks, so a [PROOF_TICKS]-tick match is
     * inside it, but saying so in a comment is not the same as handling the case.
     */
    private fun rewindBaseline(recorded: Recorded): BaselineSnapshots = BaselineSnapshots { tick ->
        val host = recorded.host
        val distance = host.tick.value - tick.value
        if (distance < 0 || distance > Int.MAX_VALUE) return@BaselineSnapshots null
        val outcome = host.time.rewind(distance.toInt())
        if (outcome !is RewindResult.Rewound || host.tick != tick) return@BaselineSnapshots null
        snapshotServiceFor(host).capture()
    }

    /** Records, writes, reads back and replays. The whole loop, once. */
    private fun proveOnce(directory: Path, run: Int, ticks: Int): ReplayVerification {
        val pilot = Pilot(Random(System.nanoTime() * 31 + run))
        val recorded = record(ticks, pilot)
        val path = directory.resolve("moba-run$run.udearep")
        recorded.recording.writeTo(path)

        val reloaded = ReplayRecording.readFrom(path)
        assertEquals(
            recorded.recording.hashStream().toList(),
            reloaded.hashStream().toList(),
            "the hash stream did not survive the round trip through the file",
        )

        val verification = ReplayVerifier.verify(
            recording = reloaded,
            factory = MobaReplay.worlds(),
            identity = MobaReplay.identityOf(recorded.host),
            baseline = rewindBaseline(recorded),
        )
        println(
            "[replay] run $run: $pilot, ${path.fileSize()} bytes on disk, " +
                "${verification.describe()}",
        )
        return verification
    }

    /**
     * A 2000-tick match, recorded and replayed bit-identically, five separate times.
     *
     * Five *different* matches, because the pilot is wall-clock seeded. The pass rate is printed
     * as a fraction rather than asserted one run at a time, so a partial failure reports "3/5"
     * with the diverging tick of the two that failed instead of stopping at the first.
     */
    @Test
    fun `a recorded moba match replays bit-identically`() {
        val directory = Files.createTempDirectory("udea-replay-proof")
        val failures = ArrayList<ReplayVerification>()
        repeat(RUNS) { run ->
            val verification = proveOnce(directory, run, PROOF_TICKS)
            assertEquals(
                PROOF_TICKS,
                verification.ticksCompared,
                "run $run replayed ${verification.ticksCompared} ticks, not $PROOF_TICKS; a " +
                    "verification that compared fewer ticks than were recorded proves less than " +
                    "it looks like it does",
            )
            if (!verification.isBitExact) failures += verification
        }
        val passed = RUNS - failures.size
        println("[replay] PASS RATE $passed/$RUNS over $PROOF_TICKS ticks each")
        assertTrue(
            failures.isEmpty(),
            "$passed/$RUNS runs replayed bit-identically. The failures:\n" +
                failures.joinToString("\n") { it.describe() },
        )
    }

    /**
     * The mutation test for the whole machinery: corrupt one recorded axis and watch it caught.
     *
     * Without this, every assertion above could be passing because the verifier compares
     * something that cannot differ. One float of one tick's move axis is flipped in the encoded
     * file - a change a player could have made by pressing a different key - and the verifier
     * must report a divergence at or after that tick and, because a baseline is available, name
     * the fields.
     *
     * "At or after" and not "exactly at": a champion whose stick was already pointing that way,
     * or who is mid-respawn, may not move differently on the very tick the input changed. The
     * assertion is that the first divergence is **not before** the corruption, which is the
     * property that would break if the verifier were reporting noise.
     */
    @Test
    fun `a corrupted recording is caught at the tick it was corrupted`() {
        val directory = Files.createTempDirectory("udea-replay-mutation")
        val pilot = Pilot(Random(System.nanoTime()))
        val recorded = record(MUTATION_TICKS, pilot)

        val corruptedAt = recorded.recording.firstTick + (MUTATION_TICKS / 2).toLong()
        val corrupted = corruptAxisAt(recorded.recording, corruptedAt)
        val path = directory.resolve("corrupted.udearep")
        corrupted.writeTo(path)

        val verification = ReplayVerifier.verify(
            recording = ReplayRecording.readFrom(path),
            factory = MobaReplay.worlds(),
            identity = MobaReplay.identityOf(recorded.host),
            baseline = rewindBaseline(recorded),
        )
        println("[replay] mutation at $corruptedAt -> ${verification.describe()}")
        assertFalse(
            verification.isBitExact,
            "a recording whose input was altered at $corruptedAt replayed to the ORIGINAL hash " +
                "stream, which means the replay is not reading the recorded input at all",
        )
        val first = verification.firstDivergentTick!!
        assertTrue(
            first.value >= corruptedAt.value,
            "the divergence was reported at $first, BEFORE the tick $corruptedAt that was " +
                "altered; the verifier is reporting noise rather than the change",
        )
        assertTrue(
            verification.fieldsAvailable,
            "the recording host's ring should have reached $first to name the differing fields",
        )
        assertTrue(
            verification.fields.isNotEmpty(),
            "a divergence caused by a movement input must show up in a field; it showed none, " +
                "so it is in the clock, the RNG or the id allocator, none of which a move axis " +
                "touches",
        )
    }

    /**
     * Rebuilds [recording] with the move axis at [tick] pushed hard left.
     *
     * Done by decoding the file, re-recording it tick by tick through a fresh [ReplayRecorder]
     * and altering one sample on the way past. That is deliberately not a byte poke: a byte poke
     * would also have to fix the CRC and the frame index, and a test that reimplements half the
     * format is a test that can pass against a format it has misunderstood.
     */
    private fun corruptAxisAt(recording: ReplayRecording, tick: Tick): ReplayRecording {
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
            val current = recording.firstTick + index.toLong()
            recording.samplesInto(current, slots)
            if (current == tick) {
                slots[0].setAxis(MobaControls.MOVE_AXIS.value, -1f, 0f)
            }
            recorder.record(current, slots, recording.hashAt(current))
        }
        return recorder.seal()
    }

    /**
     * `replay.seek` lands exactly, `replay.step` steps exactly, `replay.rewind` goes back exactly.
     *
     * The bisect surface of issue #149, on a real recording rather than a fixture. What is being
     * asserted is *exactness at every distance*, including the backwards case that has to rebuild
     * the world - because a bisect over an approximate seek converges on the wrong tick and then
     * reports it with confidence.
     */
    @Test
    fun `a session seeks, steps and rewinds a real recording exactly`() {
        val pilot = Pilot(Random(System.nanoTime()))
        val recorded = record(SESSION_TICKS, pilot)
        val recording = recorded.recording

        ReplaySession.load(
            recording,
            MobaReplay.worlds(),
            MobaReplay.identityOf(recorded.host),
        ).use { session ->
            assertEquals(recording.firstTick, session.tick, "a fresh session starts at the first tick")

            val landing = recording.firstTick + (SESSION_TICKS / 2).toLong()
            val forward = session.seek(landing)
            assertEquals(landing, forward.tickAfter, "seek did not land on $landing")
            assertEquals(
                (SESSION_TICKS / 2),
                forward.ticksStepped,
                "seek to $landing ran a different number of ticks than the distance",
            )
            assertFalse(forward.rebuilt, "a forward seek must not rebuild the world")
            assertTrue(forward.matchesRecording, "the world at $landing left the recorded stream")

            repeat(SINGLE_STEPS) {
                val before = session.tick
                val stepped = session.step(1)
                assertEquals(before + 1L, stepped.tickAfter, "step(1) did not advance one tick")
                assertEquals(1, stepped.ticksStepped, "step(1) ran ${stepped.ticksStepped} ticks")
                assertTrue(
                    stepped.matchesRecording,
                    "stepping into ${stepped.tickAfter} left the recorded hash stream",
                )
            }

            val back = session.rewind(SINGLE_STEPS)
            assertEquals(landing, back.tickAfter, "rewind did not land back on $landing")
            assertTrue(back.rebuilt, "a backwards seek rebuilds; this one claimed it did not")
            assertTrue(back.matchesRecording, "the rebuilt world at $landing is not the same world")
            assertEquals(1, session.rebuilds, "exactly one rebuild should have happened")
            assertEquals(
                null,
                session.firstDivergentTick,
                "a clean recording produced a divergence somewhere in the session",
            )
        }
    }

    /** Reads back one sample, so the round trip is asserted on real input and not only on hashes. */
    @Test
    fun `every recorded input survives the file`() {
        val pilot = Pilot(Random(System.nanoTime()))
        val recorded = record(ROUND_TRIP_TICKS, pilot)
        val directory = Files.createTempDirectory("udea-replay-roundtrip")
        val path = directory.resolve("roundtrip.udearep")
        recorded.recording.writeTo(path)

        val reloaded = ReplayRecording.readFrom(path)
        assertEquals(recorded.recording.header, reloaded.header, "the header did not round trip")

        val original: Array<InputSample> = recorded.recording.newSampleSlots()
        val copy: Array<InputSample> = reloaded.newSampleSlots()
        var moving = 0
        for (index in 0 until recorded.recording.tickCount) {
            val tick = recorded.recording.firstTick + index.toLong()
            recorded.recording.samplesInto(tick, original)
            reloaded.samplesInto(tick, copy)
            assertTrue(
                original[0].contentEquals(copy[0]),
                "the sample at $tick changed on the way through the file: " +
                    "${original[0]} became ${copy[0]}",
            )
            if (!original[0].isIdle()) moving++
        }
        assertTrue(
            moving > ROUND_TRIP_TICKS / 4,
            "only $moving of $ROUND_TRIP_TICKS recorded ticks carried any input at all; a " +
                "recording of an idle player would round-trip perfectly and prove nothing",
        )
    }

    private companion object {

        /** The brief's figure: a 2000-plus tick match, which is over half a minute at 60Hz. */
        const val PROOF_TICKS: Int = 2_000

        /** Separate matches the pass rate is over. */
        const val RUNS: Int = 5

        /** Shorter, because the mutation test is about the report and not about the length. */
        const val MUTATION_TICKS: Int = 600

        /** Long enough for a seek, a walk and a rewind to all be non-trivial distances. */
        const val SESSION_TICKS: Int = 400

        /** Single steps taken after the seek, then rewound. */
        const val SINGLE_STEPS: Int = 5

        /** Enough ticks for the pilot to have held, released and re-pressed everything. */
        const val ROUND_TRIP_TICKS: Int = 300
    }
}
