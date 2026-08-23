package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Tier-0 document says, and - the load-bearing half - what it never says.
 *
 * The invariant under test is one sentence: **no entity list, ever, at any world size.** The
 * reference implementation inlined every entity every frame, which is 80KB and about twenty
 * thousand tokens per poll at MOBA scale, and an agent polls in a loop while it waits for a
 * command to confirm. So the size test is not about bytes for their own sake - it is about
 * whether a debugging session fits in one context window.
 */
class DigestContentTest {

    @Test
    fun `the document carries the Tier-0 fields`() {
        val fixture = DigestFixture(entityCount = 20)
        fixture.bridge.advanceFrame()
        fixture.bridge.publishTick(412)
        fixture.bridge.complete(18L, AgentResult.ok { put("spawned", 3) })
        fixture.bridge.event("merge:cherry")
        fixture.ui.labels += "Restart" to true

        val document = fixture.build()

        assertContainsAll(
            document,
            """"ready":true""",
            """"frame":1""",
            """"tick":412""",
            """"paused":false""",
            """"timeScale":1""",
            """"fps":60""",
            """"completedCommandId":18""",
            """"entityCount":20""",
            """"counts":{"champion":2,"minion":14,"projectile":4}""",
            """"net":{"role":"Server","clients":2,"inKbps":12.5,"outKbps":48.25}""",
            """"ui":{"screen":"ArenaScreen","elements":[{"label":"Restart","visible":true}]}""",
            """"commandResults":[{"id":18,"ok":true,"result":{"spawned":3}}]""",
            """"events":["merge:cherry"]""",
            """"game":{"score":1280,"phase":"RUNNING","deferredRan":false}""",
        )
    }

    @Test
    fun `no array of entities appears at 5, 500 or 5000 entities`() {
        for (population in listOf(5, 500, 5000)) {
            val fixture = DigestFixture(entityCount = population)

            val document = fixture.build()

            assertFalse(
                document.contains("\"entities\""),
                "the Tier-0 digest must never carry an entity list (population $population)",
            )
            assertTrue(document.contains("\"entityCount\":$population"), document)
        }
    }

    @Test
    fun `the document length does not grow with the world`() {
        val small = DigestFixture(entityCount = 500).build()
        val large = DigestFixture(entityCount = 5000).build()

        // Both populations carry the same three archetypes, so the only difference a tenfold
        // world may make is one extra digit in each of the four counts. Anything more means
        // something in there is per-entity.
        assertTrue(
            large.length - small.length <= 4,
            "digest grew by ${large.length - small.length} chars between 500 and 5000 entities",
        )
    }

    @Test
    fun `a zero-count archetype is omitted rather than reported as zero`() {
        val fixture = DigestFixture(entityCount = 10)

        val document = fixture.build()

        // "ward":0 is a token an agent reads past on every poll for no information.
        assertFalse(document.contains("\"ward\""), document)
    }

    @Test
    fun `a failed command answer renders with its kind`() {
        val fixture = DigestFixture()
        fixture.bridge.complete(19L, AgentResult.failed(AgentErrorKind.NO_SUCH_ENTITY, "gone"))

        val document = fixture.build()

        assertTrue(
            document.contains("""{"id":19,"ok":false,"error":{"kind":"no_such_entity","message":"gone"}}"""),
            document,
        )
    }

    @Test
    fun `only the newest events are rendered, and long ones are truncated not dropped`() {
        val fixture = DigestFixture()
        repeat(30) { fixture.bridge.event("event-$it") }
        fixture.bridge.event("x".repeat(200))

        val document = fixture.build()

        assertFalse(document.contains("event-9\""), "expected the oldest events to be dropped")
        assertTrue(document.contains("event-29"), document)
        val truncated = "x".repeat(DigestBudgets.EVENT_CHARS - 1) + "~"
        assertTrue(document.contains(truncated), "a long event must be truncated, not dropped")
    }

    @Test
    fun `the game block is capped and says so`() {
        val fixture = DigestFixture()
        fixture.game.extraScalars = DigestBudgets.GAME_SCALAR_LIMIT

        val document = fixture.build()

        assertTrue(document.contains("\"truncated\":true"), document)
        assertFalse(
            document.contains("\"extra${DigestBudgets.GAME_SCALAR_LIMIT}\""),
            "scalars past the cap must not be written",
        )
    }

    @Test
    fun `the ui block is capped and says so`() {
        val fixture = DigestFixture()
        repeat(DigestBudgets.LABEL_LIMIT + 3) { fixture.ui.labels += "label-$it" to true }

        val document = fixture.build()

        assertTrue(document.contains("\"labelsTruncated\":true"), document)
        assertFalse(document.contains("label-${DigestBudgets.LABEL_LIMIT}"), document)
    }

    @Test
    fun `a paused, scaled simulation reports both`() {
        val fixture = DigestFixture()
        fixture.loop.paused = true
        fixture.loop.timeScale = 0.25f

        val document = fixture.build()

        assertContainsAll(document, """"paused":true""", """"timeScale":0.25""")
    }

    private fun assertContainsAll(document: String, vararg fragments: String) {
        for (fragment in fragments) {
            assertTrue(document.contains(fragment), "expected $fragment in\n$document")
        }
        assertEquals('{', document.first())
        assertEquals('}', document.last())
    }
}
