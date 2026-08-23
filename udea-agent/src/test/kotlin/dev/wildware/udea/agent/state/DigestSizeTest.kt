package dev.wildware.udea.agent.state

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 2KB ceiling, at the population the budget is quoted at.
 *
 * Not a byte count for its own sake: 2KB is about 500 tokens, and the bridge re-reads this
 * document every time it waits for a command to confirm. The document it replaces was ~80KB and
 * ~20 000 tokens per poll, which is the difference between an agent finishing a debugging
 * session in one context window and not.
 */
class DigestSizeTest {

    @Test
    fun `a full document stays under 2KB at 500 entities`() {
        val fixture = DigestFixture(entityCount = DigestBudgets.ENTITIES)
        fixture.bridge.publishTick(48_110)
        repeat(6) { fixture.bridge.advanceFrame() }
        repeat(DigestBudgets.EVENT_LIMIT) { fixture.bridge.event("champion_died:blue:${it}:tower_2") }
        repeat(DigestBudgets.LABEL_LIMIT) { fixture.ui.labels += "Ability $it" to true }
        repeat(DigestBudgets.RESULT_LIMIT) {
            fixture.bridge.complete(it.toLong(), dev.wildware.udea.agent.AgentResult.ok { put("ok", it) })
        }

        val document = fixture.build()

        assertTrue(
            document.length <= DigestBudgets.MAX_BYTES,
            "digest was ${document.length} chars, over the ${DigestBudgets.MAX_BYTES} budget:\n$document",
        )
    }

    @Test
    fun `a full document stays under 2KB at 5000 entities too`() {
        val fixture = DigestFixture(entityCount = 5000)
        repeat(DigestBudgets.EVENT_LIMIT) { fixture.bridge.event("wave_spawned:${it}") }

        val document = fixture.build()

        assertTrue(
            document.length <= DigestBudgets.MAX_BYTES,
            "digest was ${document.length} chars at 5000 entities",
        )
    }

    @Test
    fun `an over-long event cannot push the document over budget`() {
        val fixture = DigestFixture(entityCount = DigestBudgets.ENTITIES)
        repeat(DigestBudgets.EVENT_LIMIT * 2) { fixture.bridge.event("y".repeat(4096)) }

        val document = fixture.build()

        // A game that logs a stack trace into the ring must not be able to make /state
        // unaffordable to read.
        assertTrue(
            document.length <= DigestBudgets.MAX_BYTES,
            "digest was ${document.length} chars with oversized events",
        )
    }

    @Test
    fun `the reported length matches the document`() {
        val fixture = DigestFixture(entityCount = 42)

        val document = fixture.build()

        assertTrue(fixture.digest.lastLength == document.length)
    }
}
