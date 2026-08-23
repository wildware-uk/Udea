package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AllocationProbe
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Phase 1 exit gate: **digest <0.3ms at 500 entities**, and a build that allocates nothing
 * but the document it publishes.
 *
 * Run by `udeaDigestBudget` and wired into `check`, not into `test` - the same shape as the
 * Phase 0 budgets in `udea-core`, and for the same reason: an advisory print is a budget nobody
 * notices breaking. The numbers live in [DigestBudgets]; if this fails on slower hardware the
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

        repeat(WARMUP_BUILDS) { fixture.digest.publish() }

        val samples = LongArray(SAMPLES)
        for (index in 0 until SAMPLES) {
            val startedAt = System.nanoTime()
            fixture.digest.publish()
            samples[index] = System.nanoTime() - startedAt
        }
        samples.sort()
        val median = samples[SAMPLES / 2]

        println(
            "digest build at ${DigestBudgets.ENTITIES} entities: median ${median}ns " +
                "(budget ${DigestBudgets.BUILD_NANOS}ns), ${fixture.digest.lastLength} chars",
        )
        assertTrue(
            median <= DigestBudgets.BUILD_NANOS,
            "digest build median was ${median}ns, over the ${DigestBudgets.BUILD_NANOS}ns budget",
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
    }
}
