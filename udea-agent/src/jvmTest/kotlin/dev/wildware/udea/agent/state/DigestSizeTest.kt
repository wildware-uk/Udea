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

    /**
     * `ui.elements` never leaves the document past its own ceiling, at any label size.
     *
     * ## The defect this pins
     *
     * [DigestBudgets.LABEL_CEILING] is not a size limit on the UI section for its own sake. It
     * is *defined* as `MAX_BYTES - TAIL - GAME_MIN - EVENT_MIN - RESULT_MIN`, which is to say:
     * the promise that whatever labels do, `commandResults` still has
     * [DigestBudgets.RESULT_MIN_BYTES] to spend. `ResultPage` sizes every page against exactly
     * that number, so the promise is the whole basis of "every page lands".
     *
     * `LabelSink` checked each element against `LABEL_CEILING` and then wrote
     * `,"labelsTruncated":true` - 23 further characters - *after* the check. The overrun is
     * therefore only ever paid in the one situation the guarantee exists for, a label section
     * that filled its ceiling, and it is paid out of the bytes the command answer was promised.
     *
     * ## Why this sweeps label sizes instead of picking one
     *
     * Whether the mark actually pushes past the ceiling depends on alignment: `LabelSink` costs
     * a label at `min(chars, LABEL_CHARS) * 2 + ELEMENT_OVERHEAD` and writes slightly less, so
     * where the section stops relative to the ceiling moves with the label length. One
     * hand-picked fixture lands wherever it lands and proves nothing about the others - a
     * 40-character label stops 56 characters short and hides the bug completely. The sweep is
     * the assertion: for **every** label width, the section must end inside its ceiling.
     */
    @Test
    fun `the label section never overruns its ceiling, at any label width`() {
        // Quotes, because a character that escapes to two is what makes the charged cost and the
        // written cost the same number - and therefore what lets the section finish *at* the
        // ceiling rather than short of it.
        for (width in 1..DigestBudgets.LABEL_CHARS) {
            val fixture = DigestFixture(entityCount = DigestBudgets.ENTITIES)
            fixture.bridge.publishTick(48_110)
            repeat(DigestBudgets.LABEL_LIMIT * 3) {
                fixture.ui.labels += "\"".repeat(width) to true
            }

            val document = fixture.build()
            val resultsBegin = document.indexOf("\"commandResults\"")

            assertTrue(
                resultsBegin in 1..DigestBudgets.LABEL_CEILING,
                "at label width $width the document reached $resultsBegin characters before " +
                    "commandResults, past the ${DigestBudgets.LABEL_CEILING} ceiling that is the " +
                    "only reason a command answer is guaranteed ${DigestBudgets.RESULT_MIN_BYTES} " +
                    "bytes: $document",
            )
        }
    }

    @Test
    fun `the reported length matches the document`() {
        val fixture = DigestFixture(entityCount = 42)

        val document = fixture.build()

        assertTrue(fixture.digest.lastLength == document.length)
    }
}
