package dev.wildware.udea.replay.tools

import dev.wildware.udea.replay.BaselineSnapshots
import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplayWorldFactory
import java.nio.file.Path

/**
 * The three things a game must supply before its recordings can be bisected by an agent.
 *
 * ## Why the toolset takes a port rather than a `GameHost`
 *
 * `udea-replay` must not know what game it is replaying - it does not know how a world is built,
 * what a scene is, or where an input goes - and it must stay headless, so it cannot name
 * `udea-render`'s `Intent` either. Everything game-shaped is therefore behind this interface,
 * exactly as `RenderControl` puts everything GL-shaped behind one for the render toolset.
 *
 * A game implements it in about twenty lines; `moba`'s is `MobaReplayHost`.
 *
 * ## Recordings are resolved under a root, not from an absolute path
 *
 * [recordingRoot] is the one directory `replay.load` will read from, and [resolve] refuses a
 * path that escapes it. The agent surface is debug-only and `UDEA-REL-001` keeps it out of every
 * shipped artifact, so this is not a security boundary and is not claimed as one - it is there
 * because a tool that takes an arbitrary filesystem path is a tool an agent will eventually
 * point at the wrong tree, and `..\..\..\Windows` failing by name beats it failing as a
 * `.udearep` magic mismatch.
 */
public interface ReplayHost {

    /**
     * This build's seed, protocol hash, asset graph hash and input schema hash.
     *
     * What a recording is refused against. See [BuildIdentity] for why each of the four makes a
     * replay structurally impossible rather than merely different.
     */
    public val identity: BuildIdentity

    /** The directory `replay.load` resolves a name against. */
    public val recordingRoot: Path

    /**
     * A factory that builds a world at the recording's first tick.
     *
     * Handed the recording because a game may need its header - the seed it names, the peer
     * count, the scene - to build a world that starts where the recording starts.
     */
    public fun worlds(recording: ReplayRecording): ReplayWorldFactory

    /**
     * Where the record-time world at a given tick comes from, when this host can supply one.
     *
     * The default is [BaselineSnapshots.NONE], which is the honest answer for a recording that
     * arrived from another machine: without it a divergence is reported as a tick and two
     * hashes, which is what issue #148 asks for, and *with* it the differing fields are named
     * too. A host that still owns the recording run - one with a snapshot ring it can rewind -
     * overrides this.
     */
    public fun baseline(recording: ReplayRecording): BaselineSnapshots = BaselineSnapshots.NONE

    /**
     * [name] resolved under [recordingRoot], with `.udearep` appended when it has no extension.
     *
     * @throws IllegalArgumentException when the result escapes [recordingRoot].
     */
    public fun resolve(name: String): Path {
        require(name.isNotBlank()) { "a recording name must not be blank" }
        val root = recordingRoot.toAbsolutePath().normalize()
        val withExtension =
            if (name.endsWith(dev.wildware.udea.replay.ReplayFormat.EXTENSION)) name
            else name + dev.wildware.udea.replay.ReplayFormat.EXTENSION
        val resolved = root.resolve(withExtension).normalize()
        require(resolved.startsWith(root)) {
            "'$name' resolves to $resolved, which is outside the recording root $root"
        }
        return resolved
    }
}
