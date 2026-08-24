package dev.wildware.udea.replay.tools

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.ReplayFormatException
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplayRefusedException
import dev.wildware.udea.replay.ReplaySession
import dev.wildware.udea.replay.ReplayVerification
import dev.wildware.udea.replay.ReplayVerifier
import dev.wildware.udea.replay.SeekOutcome
import java.nio.file.NoSuchFileException

/**
 * `replay.*`: load a recording, land on a tick, step one, go back. The bisect surface.
 *
 * ## The workflow, in the order an agent runs it
 *
 * 1. `replay.load` - open a `.udearep` and refuse it now if this build cannot reproduce it,
 *    naming the identity field that differs.
 * 2. `replay.verify` - replay the whole thing against the recorded hash stream. Either it is
 *    bit-exact, or the answer is the first tick and the fields that differ there.
 * 3. `replay.seek` - land exactly on the tick before the divergence.
 * 4. `replay.step 1` - walk into it, reading `world.*` between steps.
 * 5. `replay.rewind 1` - go back and do it again, because a bisect is a loop.
 *
 * That is the loop issue #149 describes and the reason the fixed timestep, the seeded named RNG
 * streams and the input-only client-to-server vocabulary were built the way they were.
 *
 * ## These tools drive a *second* simulation
 *
 * The world a `replay.*` tool steps is not the one the host is running: it is a separate world
 * built by [ReplayHost.worlds] from the recording. So `world.query_entities` and the rest of the
 * live surface do **not** see it, and this is stated rather than implied because it is the first
 * thing an agent will get wrong. Every replay answer that matters - the tick, both hashes, the
 * divergent fields - is therefore in the tool results themselves.
 *
 * The corollary is a cost worth naming: a `replay.seek` runs the replay simulation inside the
 * host's tick, so seeking two thousand ticks stalls the host for as long as two thousand ticks
 * take. That is the same bargain `time.fast_forward` makes. It is safe - the two simulations
 * have different `SimBarrier`s, so nothing re-enters a drain - but an agent watching a windowed
 * instance will see the window stop.
 */
public class ReplayToolset(
    private val host: ReplayHost,
) {

    private var session: ReplaySession? = null
    private var loadedName: String? = null

    /**
     * Opens a recording and validates it against this build before anything is stepped.
     *
     * Validation happens here rather than at the first `seek` because a recording that cannot be
     * valid must be refused "naming the mismatched field rather than silently diverging" (issue
     * #147). An agent told at load time that `assetGraphHash` differs rebuilds assets; the same
     * agent told at tick 1,412 that two hashes disagree bisects a fight that was never the same
     * fight.
     */
    @AgentTool(
        name = "replay.load",
        description = "Open a .udearep recording and make it the one replay.seek, replay.step, " +
            "replay.rewind and replay.verify act on. It is refused here, before anything runs, " +
            "if this build cannot reproduce it - the result then names which of rootSeed, " +
            "assetGraphHash, protoHash or inputSchemaHash differs and what both sides hold.",
    )
    public fun load(
        @Arg(description = "Recording name, resolved under the host's recording root. The .udearep extension is optional.")
        name: String,
    ): AgentResult {
        val path = try {
            host.resolve(name)
        } catch (bad: IllegalArgumentException) {
            return AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, bad.message ?: "bad name")
        }
        val recording = try {
            ReplayRecording.readFrom(path)
        } catch (missing: NoSuchFileException) {
            return AgentResult.failed(
                NO_SUCH_RECORDING,
                "there is no recording at $path; replay.load resolves names under " +
                    "${host.recordingRoot}",
            )
        } catch (corrupt: ReplayFormatException) {
            return AgentResult.failed(BAD_RECORDING, corrupt.message ?: "corrupt recording")
        }
        val opened = try {
            ReplaySession.load(recording, host.worlds(recording), host.identity)
        } catch (refused: ReplayRefusedException) {
            return AgentResult.Failed(
                dev.wildware.udea.agent.AgentError(REFUSED, refused.message ?: "refused"),
            )
        }
        session?.close()
        session = opened
        loadedName = name
        return AgentResult.ok {
            put("loaded", name)
            put("path", path.toString())
            describe(opened)
        }
    }

    /** What is loaded and where the replay world currently stands. */
    @AgentTool(
        name = "replay.info",
        description = "Report the loaded recording - seed, asset hash, protocol hash, tick " +
            "range, peers - and where the replay world currently stands, including the first " +
            "tick this session has seen disagree with the recorded hash stream.",
    )
    public fun info(): AgentResult {
        val current = session ?: return notLoaded("replay.info")
        return AgentResult.ok {
            put("loaded", loadedName)
            describe(current)
        }
    }

    /**
     * Replays the whole recording and compares every tick's hash.
     *
     * Runs on a **fresh** world rather than the session's, so calling it does not move the tick
     * an agent has carefully sought to. That costs one extra world build and is worth it: a
     * verify that silently reset the bisect position would make the two tools impossible to use
     * together, which is the only way they are useful at all.
     */
    @AgentTool(
        name = "replay.verify",
        description = "Replay the whole loaded recording on a fresh world and compare its " +
            "world hash against the recorded one on every tick. Either it is bit-exact, or the " +
            "answer is the FIRST tick that differs and, when a baseline world can be " +
            "reconstructed, the exact fields. Does not move where replay.seek has left you.",
    )
    public fun verify(): AgentResult {
        val current = session ?: return notLoaded("replay.verify")
        val recording = current.recording
        val verification = ReplayVerifier.verify(
            recording = recording,
            factory = host.worlds(recording),
            identity = null,
            baseline = host.baseline(recording),
        )
        return AgentResult.ok { render(verification) }
    }

    /** Lands the replay world exactly on a tick. */
    @AgentTool(
        name = "replay.seek",
        description = "Land the replay world exactly on this tick of the recording. Seeking " +
            "backwards rebuilds the world from the first tick and fast-forwards, because a " +
            "simulation cannot be run in reverse; the result says whether it did.",
    )
    public fun seek(
        @Arg(description = "The absolute tick to land on. Must be within the recording's range.")
        tick: Long,
    ): AgentResult {
        val current = session ?: return notLoaded("replay.seek")
        return moved(current) { it.seek(Tick(tick)) }
    }

    /** Single-step, which is what a bisect walks a divergence with. */
    @AgentTool(
        name = "replay.step",
        description = "Advance the replay world by exactly this many ticks of the recording " +
            "and report the tick either side, both hashes and whether they still agree. One " +
            "tick at a time is how you walk into a divergence after replay.seek lands you " +
            "before it.",
    )
    public fun step(
        @Arg(
            description = "Ticks to advance. One tick is one 1/60s simulation step.",
            required = false,
            default = "1",
        )
        ticks: Int,
    ): AgentResult {
        val current = session ?: return notLoaded("replay.step")
        return moved(current) { it.step(ticks) }
    }

    /** Backwards, by rebuilding. See [ReplaySession] for why there is no other honest answer. */
    @AgentTool(
        name = "replay.rewind",
        description = "Move the replay world back this many ticks. It is exact at any " +
            "distance, and it is done by rebuilding from the recording's first tick and " +
            "fast-forwarding, so a long rewind costs proportionally to how far into the " +
            "recording you are - the result reports both the distance and the rebuild.",
    )
    public fun rewind(
        @Arg(description = "How many ticks back from the current position to land on.")
        ticks: Int,
    ): AgentResult {
        val current = session ?: return notLoaded("replay.rewind")
        return moved(current) { it.rewind(ticks) }
    }

    override fun toString(): String =
        "ReplayToolset(${loadedName ?: "<nothing loaded>"}, ${session?.toString() ?: "no session"})"

    private inline fun moved(current: ReplaySession, seek: (ReplaySession) -> SeekOutcome): AgentResult {
        val outcome = try {
            seek(current)
        } catch (bad: IllegalArgumentException) {
            return AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, bad.message ?: "bad seek")
        }
        return AgentResult.ok { render(outcome, current) }
    }

    private fun notLoaded(tool: String): AgentResult = AgentResult.failed(
        NOT_LOADED,
        "$tool needs a recording; call replay.load first. Recordings are resolved under " +
            "${host.recordingRoot}",
    )

    private fun Json.describe(current: ReplaySession) {
        val header = current.recording.header
        obj("recording") {
            put("gameId", header.gameId)
            put("gameVersion", header.gameVersion)
            put("firstTick", header.firstTick.value)
            put("endTick", header.endTick.value)
            put("tickCount", header.tickCount)
            put("peerCount", header.peerCount)
            put("tickRateHz", header.tickRateHz)
            put("durationSeconds", header.durationSeconds.toFloat())
            put("rootSeed", header.identity.rootSeed)
            put("protoHash", header.identity.protoHash)
            put("inputSchemaHash", header.identity.inputSchemaHash)
            put("assetGraphHash", header.identity.assetGraphHash.joinToString("") { "%02x".format(it) })
            arr("axes") { for (name in header.schema.axes) value(name) }
            arr("actions") { for (name in header.schema.actions) value(name) }
        }
        obj("position") {
            put("tick", current.tick.value)
            put("rebuilds", current.rebuilds)
            put("ticksRun", current.ticksRun)
            put("firstDivergentTick", current.firstDivergentTick?.value ?: -1L)
        }
    }

    private fun Json.render(outcome: SeekOutcome, current: ReplaySession) {
        put("tickBefore", outcome.tickBefore.value)
        put("tickAfter", outcome.tickAfter.value)
        put("ticksStepped", outcome.ticksStepped)
        put("rebuilt", outcome.rebuilt)
        put("recordedHash", outcome.recordedHash)
        put("replayedHash", outcome.replayedHash)
        put("matchesRecording", outcome.matchesRecording)
        put("firstDivergentTick", outcome.firstDivergentTick?.value ?: -1L)
        put("endTick", current.recording.endTick.value)
    }

    private fun Json.render(verification: ReplayVerification) {
        put("bitExact", verification.isBitExact)
        put("ticksCompared", verification.ticksCompared)
        put("matchingTicks", verification.matchingTicks)
        put("firstDivergentTick", verification.firstDivergentTick?.value ?: -1L)
        put("recordedHash", verification.recordedHash)
        put("replayedHash", verification.replayedHash)
        put("fieldsAvailable", verification.fieldsAvailable)
        arr("fields") {
            for (field in verification.fields.take(MAX_REPORTED_FIELDS)) {
                element {
                    put("netId", field.netId.raw)
                    put("component", field.componentName)
                    put("field", field.fieldName)
                    put("recorded", field.expected)
                    put("replayed", field.actual)
                }
            }
        }
        put("fieldsTotal", verification.fields.size)
        put("summary", verification.describe())
    }

    public companion object {

        /** No `.udearep` at the resolved path. */
        public val NO_SUCH_RECORDING: AgentErrorKind = AgentErrorKind("no_such_recording")

        /** The bytes are not a well-formed recording: wrong magic, wrong version, bad CRC. */
        public val BAD_RECORDING: AgentErrorKind = AgentErrorKind("bad_recording")

        /**
         * The recording is well-formed and this build cannot reproduce it.
         *
         * A separate kind from [BAD_RECORDING] on purpose: one means "the file is damaged" and
         * the other means "you are on the wrong build", and an agent that could not tell them
         * apart would try to re-download a recording that was never damaged.
         */
        public val REFUSED: AgentErrorKind = AgentErrorKind("replay_refused")

        /** A `replay.*` tool other than `load` was called with nothing loaded. */
        public val NOT_LOADED: AgentErrorKind = AgentErrorKind("no_replay_loaded")

        /** Fields published per verification. The same screenful cap `DivergenceReport` uses. */
        public const val MAX_REPORTED_FIELDS: Int = 25
    }
}
