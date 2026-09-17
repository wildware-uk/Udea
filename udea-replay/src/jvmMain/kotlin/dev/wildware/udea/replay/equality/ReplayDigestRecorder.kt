package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplayWorld
import dev.wildware.udea.replay.ReplayWorldFactory
import dev.wildware.udea.core.snapshot.ComponentRegistry
import java.nio.file.Path

/**
 * What one replay run produced, beside the file it wrote.
 *
 * The two hash streams are kept apart on purpose. [recordedMismatches] counts the ticks where
 * this run disagreed with the hashes stored *inside* the `.udearep`, and that is **not** the gate:
 * those hashes were produced by whichever machine recorded the fixture, so a cross-platform
 * difference would make one leg of the matrix red for a reason the other leg is equally
 * responsible for, and the failure would be attributed to whoever did not record the file. The
 * gate is `ReplayEquality` over two peers' streams, which is symmetric. This number is reported
 * so a reader can see it, and nothing branches on it.
 */
public class ReplayDigestRun(
    /** Where the `.udeaeq` was written. */
    public val output: Path,
    /** How many ticks were replayed and written. */
    public val ticks: Int,
    /** The first tick at which this run disagreed with the recording's own hashes, or `null`. */
    public val firstRecordedMismatch: Tick?,
    /** How many ticks disagreed with the recording's own hashes. */
    public val recordedMismatches: Int,
    /** Wall-clock milliseconds the replay took. A build measurement, never simulation input. */
    public val elapsedMillis: Long,
) {

    /** The line a CI job summary prints. */
    public fun describe(): String = buildString {
        append("replayed ").append(ticks).append(" tick(s) in ").append(elapsedMillis)
            .append("ms into ").append(output.fileName)
        if (recordedMismatches > 0) {
            append("\n  note: ").append(recordedMismatches)
                .append(" tick(s) differ from the hash stream stored in the recording, first at ")
                .append(firstRecordedMismatch)
                .append(". That is not this job's verdict - the recording's hashes are one ")
                .append("machine's answer, and the gate is the comparison against the other leg.")
        }
    }

    override fun toString(): String = describe()
}

/**
 * Replays a recording and writes the per-tick digest stream a cross-OS comparison consumes.
 *
 * ## Why this is not `ReplayVerifier`
 *
 * `ReplayVerifier` answers "does this recording still reproduce **here**", by comparing against
 * the hashes the recording carries. That is the right question inside one machine and the wrong
 * one across two: it makes the recording machine the authority, so a genuine cross-platform
 * float difference is reported as the *other* platform's fault. This asks a different question -
 * "what did this machine produce" - and writes the answer down for somebody else to compare.
 *
 * Everything game-shaped stays behind [ReplayWorldFactory] and [ReplayWorld.snapshot], exactly as
 * it does for `ReplayVerifier`, so any game whose replay world can capture a snapshot gets a
 * digest stream without a line of new code. `MobaReplayWorld` already can.
 */
public object ReplayDigestRecorder {

    /**
     * Replays [recording] into a world from [factory], writing every tick's cells to [output].
     *
     * @param gradleProject the project whose `udeaReplayDigest` this is, e.g. `:moba`. Recorded
     *   in the header so the join step can print a reproduce command that names the right module -
     *   see [ReplayDigestHeader.gradleProject].
     * @param registry the registry the replay world captures through. It supplies the component
     *   table the join step renders names from, and it must be the same registry *object* the
     *   world's `SnapshotService` was built over - see `MobaReplay.REGISTRY` for what goes wrong
     *   when two equal-but-distinct registries meet.
     * @throws IllegalStateException when the replay world cannot capture a snapshot. A `null`
     *   snapshot is legal for a `ReplayWorld` in general and useless here: a stream of hashes
     *   with no cells is exactly the bare hash mismatch this whole file exists to prevent.
     */
    public fun record(
        recording: ReplayRecording,
        factory: ReplayWorldFactory,
        registry: ComponentRegistry,
        output: Path,
        label: String,
        fixture: String,
        gradleProject: String,
    ): ReplayDigestRun {
        val header = ReplayDigestHeader(
            label = label,
            fixture = fixture,
            gameId = recording.header.gameId,
            gameVersion = recording.header.gameVersion,
            firstTick = recording.firstTick,
            tickCount = recording.tickCount,
            jvm = describeJvm(),
            os = describeOs(),
            gradleProject = gradleProject,
            components = ReplayDigestIo.componentsOf(registry),
        )

        val startedAt = System.nanoTime()
        var mismatches = 0
        var firstMismatch: Tick? = null
        val world = factory.create(recording.firstTick)
        try {
            check(world.tick == recording.firstTick) {
                "the replay world was built at ${world.tick} and the recording starts at " +
                    "${recording.firstTick}; a digest that starts one tick out disagrees with " +
                    "every other leg from its first cell"
            }
            ReplayDigestIo.writer(output, header).use { writer ->
                val slots = recording.newSampleSlots()
                for (index in 0 until recording.tickCount) {
                    val tick = recording.firstTick + index.toLong()
                    recording.samplesInto(tick, slots)
                    world.applyInput(slots)
                    world.step()
                    val hash = world.hash()
                    if (hash != recording.hashAt(tick)) {
                        if (firstMismatch == null) firstMismatch = tick
                        mismatches++
                    }
                    val snapshot = checkNotNull(world.snapshot()) {
                        "$world captured no snapshot at $tick. A digest stream needs the values " +
                            "behind the hash, so a ReplayWorld whose snapshot() is null cannot " +
                            "take part in cross-OS equality - it could only ever report a hash."
                    }
                    writer.writeTick(snapshot)
                }
            }
        } finally {
            world.close()
        }
        return ReplayDigestRun(
            output = output,
            ticks = recording.tickCount,
            firstRecordedMismatch = firstMismatch,
            recordedMismatches = mismatches,
            elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
        )
    }

    /** The JVM, as the header records it. Vendor and version, because both can move the last bit. */
    private fun describeJvm(): String = buildString {
        append(System.getProperty("java.vm.vendor", "unknown-vendor"))
        append(' ').append(System.getProperty("java.vm.name", "unknown-vm"))
        append(' ').append(System.getProperty("java.version", "unknown-version"))
    }

    /** The operating system, as the header records it. */
    private fun describeOs(): String =
        System.getProperty("os.name", "unknown-os") + " " + System.getProperty("os.arch", "unknown-arch")

    private const val NANOS_PER_MILLI: Long = 1_000_000L
}
