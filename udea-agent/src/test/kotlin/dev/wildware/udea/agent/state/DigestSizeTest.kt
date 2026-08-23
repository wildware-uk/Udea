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

    /** Scalars `MutableGameState` publishes before `extraScalars` starts. */
    private val BASE_GAME_SCALARS = 3

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
    fun `every cap saturated at once still fits the ceiling`() {
        // The case the ceiling was never checked against, and the one that broke it: each
        // section's cap is a *count*, and an event message, a UI label and a game scalar name
        // are all game-authored text. Saturating all four with realistic names produced a
        // 2027-character document - 21 characters of margin - and one game publishing
        // `objectiveRespawnTick` went straight through with nothing noticing.
        val fixture = DigestFixture(entityCount = DigestBudgets.ENTITIES)
        fixture.bridge.publishTick(48_110)
        repeat(6) { fixture.bridge.advanceFrame() }
        repeat(DigestBudgets.EVENT_LIMIT) {
            fixture.bridge.event("champion_died:blue_team_carry:$it:killed_by_tower_2_dive")
        }
        repeat(DigestBudgets.LABEL_LIMIT) { fixture.ui.labels += "Ability $it on cooldown" to true }
        repeat(DigestBudgets.RESULT_LIMIT) {
            fixture.bridge.complete(
                it.toLong(),
                dev.wildware.udea.agent.AgentResult.ok { put("spawnedEntityId", 131_073 + it) },
            )
        }
        fixture.game.extraScalarNamePrefix = "objectiveRespawnTick"
        fixture.game.extraScalars = DigestBudgets.GAME_SCALAR_LIMIT - BASE_GAME_SCALARS

        val document = fixture.build()

        assertTrue(
            document.length <= DigestBudgets.MAX_BYTES,
            "digest was ${document.length} chars with every cap saturated: $document",
        )
        // Enforced rather than merely fitting: the game block is rendered last and refuses a
        // scalar that would not leave room to close the document, so the overflow is reported.
        assertTrue(
            document.contains("\"gameTruncated\":true"),
            "the game block absorbed the ceiling silently, which is the failure this replaces",
        )
    }

    @Test
    fun `the reported length matches the document`() {
        val fixture = DigestFixture(entityCount = 42)

        val document = fixture.build()

        assertTrue(fixture.digest.lastLength == document.length)
    }
}
