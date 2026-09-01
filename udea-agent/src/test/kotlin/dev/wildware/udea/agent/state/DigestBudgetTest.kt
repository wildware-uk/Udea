package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AllocationProbe
import dev.wildware.udea.diagnostics.bench.LatencyBudget
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Phase 1 exit gate: **digest <0.3ms at 500 entities**, and a build that allocates nothing
 * but the document it publishes.
 *
 * Run by `udeaDigestBudget`, which hangs off the root's `udeaLatencyBudgets` and not off `check`
 * - the same shape as the Phase 0 budgets in `udea-core`, and for the same two reasons: an
 * advisory print is a budget nobody notices breaking, and a wall-clock number measured inside a
 * parallel build is a number about the build (issue #175). The numbers live in [DigestBudgets]; if this fails on slower hardware the
 * remedy is to build the digest less often, never to widen the number.
 *
 * Timing here uses a real clock deliberately, unlike every other test in the module. It is
 * measuring wall time, which is the one thing a `ManualClock` cannot tell you.
 */
class DigestBudgetTest {

    @Test
    fun `a build at 500 entities stays under the time budget`() {
        val fixture = DigestFixture(entityCount = DigestBudgets.ENTITIES)
        fill(fixture)

        val median = medianBuildNanos(fixture)

        println(
            "digest build at ${DigestBudgets.ENTITIES} entities: median ${median}ns " +
                "(budget ${DigestBudgets.BUILD_NANOS}ns), ${fixture.digest.lastLength} chars",
        )
        assertTrue(
            median <= DigestBudgets.BUILD_NANOS,
            "digest build median was ${median}ns, over the ${DigestBudgets.BUILD_NANOS}ns budget. " +
                LatencyBudget.contentionNote(":udea-agent:udeaDigestBudget"),
        )
    }

    /**
     * The assertion the wall-clock number cannot make: **population does not enter the build.**
     *
     * The 0.3ms gate above is quoted "at 500 entities" and the fixture obliges by calling
     * `ArchetypeCensus.spawned()` 500 times, which increments four `Int` counters. The render
     * then reads one field and walks four archetypes, so the measured work is identical at 0,
     * 500 and 5 000 000 - which means the parameter the budget is quoted against does not
     * influence the measurement at all, and a regression making the digest a hundred times
     * slower still passes with room to spare.
     *
     * That is not an argument for a tighter number; it is an argument for measuring the thing
     * that can actually regress. The one failure the budget exists to catch is *something
     * starting to walk the world*, and a walk is visible as a **difference** between an empty
     * census and a full one, whatever the absolute numbers are on the machine of the day. So
     * this compares the two directly. A build that touched even one entity per entity would put
     * 500 units of work on one side and none on the other and fail here immediately, on a
     * laptop under load exactly as in CI.
     */
    @Test
    fun `the build costs the same at 500 entities as at none`() {
        val empty = DigestFixture(entityCount = 0)
        fill(empty)
        val populated = DigestFixture(entityCount = DigestBudgets.ENTITIES)
        fill(populated)

        // Interleaved rather than one after the other: whichever ran second would otherwise
        // carry the other's JIT state and its thermal luck.
        medianBuildNanos(empty)
        medianBuildNanos(populated)
        val emptyMedian = medianBuildNanos(empty)
        val populatedMedian = medianBuildNanos(populated)

        val ratio = populatedMedian.toDouble() / emptyMedian.toDouble()
        println(
            "digest build: ${emptyMedian}ns at 0 entities, ${populatedMedian}ns at " +
                "${DigestBudgets.ENTITIES} - ratio ${"%.2f".format(ratio)}",
        )
        assertTrue(
            ratio <= POPULATION_RATIO_CEILING,
            "the build cost ${ratio}x more at ${DigestBudgets.ENTITIES} entities than at none; " +
                "the digest is walking the world, which is what EntityCensus exists to prevent",
        )
    }

    @Test
    fun `rendering allocates nothing across a thousand consecutive builds`() {
        if (!AllocationProbe.isSupported) return
        val fixture = DigestFixture(entityCount = DigestBudgets.ENTITIES)
        fill(fixture)

        val bytes = AllocationProbe.bytesAllocated {
            repeat(1000) { fixture.digest.renderInto() }
        }

        println("digest render: ${bytes}B across 1000 builds")
        assertTrue(
            bytes <= DigestBudgets.RENDER_ALLOCATED_BYTES,
            "rendering allocated ${bytes}B across 1000 builds; the budget is " +
                "${DigestBudgets.RENDER_ALLOCATED_BYTES}B",
        )
    }

    @Test
    fun `publishing allocates the document and nothing else`() {
        if (!AllocationProbe.isSupported) return
        val fixture = DigestFixture(entityCount = DigestBudgets.ENTITIES)
        fill(fixture)
        fixture.digest.publish()
        val documentChars = fixture.digest.lastLength

        val bytes = AllocationProbe.bytesAllocated { fixture.digest.publish() }

        // One String has to cross to the HTTP thread, so this cannot be zero. It can be *only*
        // that: a Latin-1 String is one header plus one byte per character, so anything much
        // over that is a second allocation somebody added to the build.
        val ceiling = documentChars.toLong() + STRING_OVERHEAD_BYTES
        println("digest publish: ${bytes}B for a $documentChars-char document")
        assertTrue(
            bytes <= ceiling,
            "publishing allocated ${bytes}B for a $documentChars-char document; expected at " +
                "most ${ceiling}B, which is the document itself",
        )
    }

    private fun medianBuildNanos(fixture: DigestFixture): Long {
        repeat(WARMUP_BUILDS) { fixture.digest.publish() }
        val samples = LongArray(SAMPLES)
        for (index in 0 until SAMPLES) {
            val startedAt = System.nanoTime()
            fixture.digest.publish()
            samples[index] = System.nanoTime() - startedAt
        }
        samples.sort()
        return samples[SAMPLES / 2]
    }

    private fun fill(fixture: DigestFixture) {
        fixture.bridge.publishTick(48_110)
        repeat(DigestBudgets.EVENT_LIMIT) { fixture.bridge.event("champion_died:blue:$it:tower_2") }
        repeat(DigestBudgets.LABEL_LIMIT) { fixture.ui.labels += "Ability $it" to true }
        repeat(DigestBudgets.RESULT_LIMIT) {
            fixture.bridge.complete(it.toLong(), dev.wildware.udea.agent.AgentResult.ok { put("ok", it) })
        }
        // The budget is measured on the path a watched game actually takes.
        fixture.bridge.snapshot()
    }

    private companion object {
        const val WARMUP_BUILDS: Int = 2_000
        const val SAMPLES: Int = 501

        /** A `String` object header plus its `byte[]` header, generously. */
        const val STRING_OVERHEAD_BYTES: Long = 96L

        /**
         * How much slower a build at 500 entities may be than one at none.
         *
         * Wide, because the two documents are not byte-identical - a populated census writes
         * three `counts` members an empty one omits - and because a median of 501 samples on a
         * shared CI box still moves. Wide is fine: the regression it is watching for is
         * per-entity work, which is 500 units against nothing and shows up as a ratio in the
         * tens or hundreds, not as 2.1 against 2.0.
         */
        const val POPULATION_RATIO_CEILING: Double = 4.0
    }
}
