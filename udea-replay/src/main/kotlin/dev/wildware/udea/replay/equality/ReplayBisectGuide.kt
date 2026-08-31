package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick

/**
 * The "how do I reproduce this" half of a `replay-equality` job summary. Issue #165.
 *
 * ## There is no `replay.bisect` tool, and this is what there is instead
 *
 * The issue asks the summary to link "the `replay.bisect` MCP tool". No such tool exists: issue
 * #149 built a bisect *surface* rather than a bisect command, because a bisect is a loop a reader
 * drives - land on a tick, walk one step into it, read the world, go back and do it again - and
 * every step of that loop is a decision only the reader can make. [TOOLS] is that loop in the
 * order it is called, and `ReplayBisectGuideTest` checks every name in it against the generated
 * `replay.*` module, so this document cannot come to name a tool nobody can call.
 *
 * ## Why it is rendered here rather than written in `ci.yml`
 *
 * The same reason everything else in this gate is: nobody can run GitHub Actions locally, so a
 * block of markdown assembled in a workflow step is unverifiable until it has already gone wrong
 * on a branch somebody merged. Rendered here, the tick arithmetic, the fixture name and the tool
 * names are all covered by tests that run on every push.
 *
 * ## Why a green summary gets one too
 *
 * Because a green summary is read - by somebody about to push, who wants to know how to run the
 * gate first. A block that appears only on a red run appears only when nobody has time to read
 * it, and `docs/engineering-standards.md` makes the same argument about publishing a budget's
 * headroom on a passing run.
 */
public object ReplayBisectGuide {

    /**
     * The `replay.*` loop, in the order a bisect calls it.
     *
     * A list rather than a count, and checked against `ReplayToolModules.Replay` rather than
     * restated: adding a tool to the loop is an addition here and a test that already knows how
     * to check it.
     */
    public val TOOLS: List<String> = listOf(
        "replay.load",
        "replay.verify",
        "replay.seek",
        "replay.step",
        "replay.rewind",
    )

    /**
     * The block, for a run of [fixture] over legs that diverged at [divergentTicks] or did not.
     *
     * @param gradleProject the project that owns [fixture], from
     *   [ReplayDigestHeader.gradleProject]. Read off the stream rather than written down here,
     *   because since issue #172 two projects each register a `udeaReplayDigest` over their own
     *   fixtures and neither can resolve the other's name: this block used to say `:udea-replay`
     *   unconditionally, so the instruction for reproducing a red `moba` gate was a command that
     *   fails with `no fixture is called 'moba-3600.udearep'`.
     * @param divergentTicks one entry per pair compared: the tick that pair first disagreed at,
     *   or `null` where it agreed. The **earliest** is the one a reader is sent to, and taking
     *   the minimum here rather than at the call site is what makes it testable: two legs can
     *   diverge from the reference at different ticks, in either order, and every later one may
     *   be a consequence of the earlier.
     */
    public fun render(
        fixture: String,
        gradleProject: String,
        divergentTicks: List<Tick?>,
    ): String = buildString {
        val divergentTick = divergentTicks.filterNotNull().minOrNull()
        append("\n\n--- reproducing this locally ---\n")
        append("Both halves of the gate, in five processes on one machine:\n")
        append("  ./gradlew ").append(gradleProject).append(":udeaReplayEqualityProof\n")
        append("This leg on its own, against the same recording:\n")
        append("  ./gradlew ").append(gradleProject).append(":udeaReplayDigest")
        append(" -Pudea.replay.fixture=").append(fixture)
        append(" -Pudea.replay.label=mine\n")

        if (divergentTick == null) {
            append("There is no divergence to bisect: every leg folded the same cells on every ")
            append("tick, so the loop below has no tick to land on.\n")
            return@buildString
        }

        // `replay.seek` lands *on* the tick it is given, and the step a reader wants to watch is
        // the one *into* the divergence - so the loop starts on the last tick the legs agreed on.
        // Clamped at the recording's own first tick, because a divergence on tick one has no
        // preceding tick and `ReplaySession.seek` refuses a tick outside the range.
        val land = maxOf(0L, divergentTick.value - 1L)
        append("The divergence is at ").append(divergentTick).append(", so walk into it. There is ")
        append("no single bisect tool: the surface is the ").append(TOOLS.size)
        append(" calls below, and issue #149 is where the loop is described.\n")
        append("  ").append(TOOLS[0]).append("    {\"name\": \"").append(fixture).append("\"}\n")
        append("  ").append(TOOLS[1]).append("  {}\n")
        append("  ").append(TOOLS[2]).append("    {\"tick\": ").append(land).append("}\n")
        append("  ").append(TOOLS[3]).append("    {\"ticks\": 1}\n")
        append("  ").append(TOOLS[4]).append("  {\"ticks\": 1}\n")
        append("Read the world between the last two with `world.*`; they are a loop, and the ")
        append("recording is bit-exact in both directions.\n")
    }
}
