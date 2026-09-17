package dev.wildware.udea.agent.harness

import dev.wildware.udea.agent.tools.ToolsetHarness
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM world hash of an existing [SimHarness] scenario, pinned to the value it had before
 * `udea-core` became multiplatform (issue #203).
 *
 * The scenario is `SimHarnessTest`'s Phase 1 workflow - spawn twenty grunts, step 200, snapshot,
 * edit a field, step 100, rewind 100 - driven through the same tool calls. The hash is
 * `WorldHasher.hash` over a whole-world capture, so it folds every captured field, the tick, the
 * random streams and the id allocator. Each checkpoint was recorded on `origin/kmp` at `739deda`,
 * where `udea-core` was still a JVM module, and a port that changed what the JVM simulates, how it
 * captures or how it restores moves at least one of them.
 *
 * JVM only, and deliberately: spec decision D3 makes the desktop JVM the one bit-exact platform.
 */
class SimHarnessWorldHashPinTest {

    @Test
    fun `the Phase 1 workflow hashes on the JVM exactly as it did before the multiplatform port`() {
        val harness = ToolsetHarness()
        val hashes = LinkedHashMap<String, Long>()

        harness.ok("time.pause")
        hashes["paused, empty"] = harness.worldHash()

        val ids = List(20) { index ->
            val spawned = harness.ok(
                "world.spawn_blueprint",
                "blueprint" to "grunt",
                "x" to index.toString(),
                "y" to "0",
            )
            Regex("\"id\":(-?\\d+)").find(spawned)!!.groupValues[1]
        }
        harness.ok("time.step", "ticks" to "200")
        hashes["twenty grunts, 200 ticks"] = harness.worldHash()

        harness.ok("time.snapshot")
        harness.ok(
            "world.set_component_field",
            "id" to ids[7],
            "component" to "Health",
            "field" to "current",
            "value" to "3",
        )
        harness.ok("time.step", "ticks" to "100")
        hashes["one health edited, 100 more ticks"] = harness.worldHash()

        harness.ok("time.rewind", "ticks" to "100")
        hashes["rewound 100"] = harness.worldHash()

        assertEquals(PINNED, hashes, "the JVM world hash moved; see this class's KDoc")
    }

    private companion object {
        /** Recorded on `origin/kmp` at `739deda`, before `udea-core` was multiplatform. */
        val PINNED: Map<String, Long> = linkedMapOf(
            "paused, empty" to -5443109826740889977L,
            "twenty grunts, 200 ticks" to -5421599828835665426L,
            "one health edited, 100 more ticks" to -7922020967447177877L,
            "rewound 100" to -5421599828835665426L,
        )
    }
}
