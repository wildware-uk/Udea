package dev.wildware.moba.agent

import dev.wildware.moba.replay.MobaReplay
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplayWorldFactory
import dev.wildware.udea.replay.tools.ReplayHost
import java.nio.file.Files
import java.nio.file.Path

/**
 * `moba`'s [ReplayHost]: what `replay.*` loads recordings from, and what it builds a world with.
 *
 * ## Why this is in the agent source set and not beside `MobaReplay`
 *
 * `MobaReplay` is on `main`, because recording is a property of the game and `MobaReplayProofTest`
 * records a match with no agent surface anywhere near it. This class is the *tool* half, and
 * `ReplayToolset` - the only thing that consumes it - names `udea-agent` types that `:udea-replay`
 * takes `compileOnly` precisely so `UDEA-REL-002` still holds. Putting this on `main` would
 * compile today and would be an invitation to put the toolset there tomorrow, which is the edit
 * that puts an agent surface in a shipped jar. The `agent` source set is the one classpath in
 * this project that resolves `:udea-agent-host`, so this is where it belongs.
 *
 * ## The baseline is deliberately absent
 *
 * [baseline] is left at its default of `BaselineSnapshots.NONE`, and that is an honest limit
 * rather than an oversight. Naming the *fields* that differ at a divergence needs the record-time
 * world at that tick, and a `.udearep` carries one hash per tick and no fields. `MobaReplayProof`
 * gets field names by rewinding the host that made the recording, in the same process; an agent
 * loading a file through `replay.load` generally did not make it, and this host would have to
 * invent a baseline to pretend otherwise. So `replay.verify` here answers with the tick and both
 * hashes, plus the sentence `udea-replay` writes saying why the fields are unnamed.
 */
public class MobaReplayHost(
    /** The live game. Read for its seed only; the replay steps a world of its own. */
    host: GameHost,
    /** Where `replay.load` resolves a name against. Created if it does not exist. */
    override val recordingRoot: Path = DEFAULT_ROOT,
) : ReplayHost {

    override val identity: BuildIdentity = MobaReplay.identityOf(host)

    init {
        Files.createDirectories(recordingRoot)
    }

    override fun worlds(recording: ReplayRecording): ReplayWorldFactory = MobaReplay.worlds()

    override fun toString(): String = "MobaReplayHost($recordingRoot)"

    public companion object {

        /**
         * `build/udea/recordings`, under the working directory.
         *
         * Under `build/` because a recording is a build artifact and nothing should ever commit
         * one; `-Dudea.replay.root` overrides it for a process pointed at a corpus elsewhere.
         */
        public val DEFAULT_ROOT: Path
            get() = Path.of(
                System.getProperty("udea.replay.root") ?: "build/udea/recordings",
            ).toAbsolutePath().normalize()
    }
}
