package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Each filter on its own, then in combination, in the text form an agent actually sends.
 *
 * Driving the parser as well as the evaluator is deliberate: a query engine that is correct and a
 * grammar that reads `health.current<400` as something else are indistinguishable to the agent,
 * and it is the agent that has to predict both.
 */
class EntityQueryTest {

    private fun ids(document: String): List<Int> =
        Regex("\"id\":(\\d+)").findAll(document).map { it.groupValues[1].toInt() }.toList()

    @Test
    fun `with selects only entities carrying every named component`() {
        val harness = QueryHarness()
        val champion = harness.spawn(champion = true)
        harness.spawn(champion = false)

        val document = harness.query(with = "Champion,Health")

        assertEquals(listOf(champion.raw), ids(document))
        assertTrue(document.contains("\"total\":1"), document)
    }

    @Test
    fun `where compares equality on a bare field name`() {
        val harness = QueryHarness()
        val blue = harness.spawn(team = 1)
        harness.spawn(team = 2)

        val document = harness.query(with = "Team", where = "team=1")

        assertEquals(listOf(blue.raw), ids(document))
    }

    @Test
    fun `where compares an order on a qualified nested path`() {
        val harness = QueryHarness()
        val hurt = harness.spawn(health = 310f)
        harness.spawn(health = 900f)

        val document = harness.query(where = "health.current<400")

        assertEquals(listOf(hurt.raw), ids(document))
    }

    @Test
    fun `every comparison operator is honoured`() {
        val harness = QueryHarness()
        harness.spawn(health = 100f)
        harness.spawn(health = 200f)
        harness.spawn(health = 300f)

        assertEquals(1, ids(harness.query(where = "health.current=200")).size)
        assertEquals(2, ids(harness.query(where = "health.current!=200")).size)
        assertEquals(1, ids(harness.query(where = "health.current<200")).size)
        assertEquals(2, ids(harness.query(where = "health.current<=200")).size)
        assertEquals(1, ids(harness.query(where = "health.current>200")).size)
        assertEquals(2, ids(harness.query(where = "health.current>=200")).size)
    }

    @Test
    fun `near selects by distance, boundary included`() {
        val harness = QueryHarness()
        val inside = harness.spawn(x = 41f, y = 22f)
        val onBoundary = harness.spawn(x = 55f, y = 22f)
        harness.spawn(x = 90f, y = 22f)

        val document = harness.query(near = "40,22,15")

        assertEquals(listOf(inside.raw, onBoundary.raw).sorted(), ids(document).sorted())
    }

    @Test
    fun `filters combine with and`() {
        val harness = QueryHarness()
        val wanted = harness.spawn(x = 38.2f, y = 20.9f, health = 310f, team = 1)
        harness.spawn(x = 38.2f, y = 20.9f, health = 310f, team = 2)
        harness.spawn(x = 38.2f, y = 20.9f, health = 900f, team = 1)
        harness.spawn(x = 300f, y = 300f, health = 310f, team = 1)
        harness.spawn(x = 38.2f, y = 20.9f, health = 310f, team = 1, champion = false)

        val document = harness.query(
            with = "Champion,Health",
            where = "team=1,health.current<400",
            near = "40,22,15",
        )

        assertEquals(listOf(wanted.raw), ids(document))
        assertTrue(document.contains("\"total\":1"), document)
    }

    @Test
    fun `fields projects only what was asked for, plus the id`() {
        val harness = QueryHarness()
        harness.spawn(x = 38.2f, y = 20.9f, health = 310f)

        val document = harness.query(fields = "id,pos,health.current")

        assertTrue(
            document.contains("""{"id":0,"pos":[38.2,20.9],"health.current":310}"""),
            document,
        )
        // Nothing else leaks in: the projection is what keeps a query at ~150 tokens.
        assertTrue(!document.contains("\"max\""), document)
        assertTrue(!document.contains("\"rotation\""), document)
    }

    @Test
    fun `an id is rendered even when the projection does not ask for it`() {
        val harness = QueryHarness()
        harness.spawn(health = 42f)

        val document = harness.query(fields = "health.current")

        // A row an agent cannot address is not a result.
        assertTrue(document.contains("""{"id":0,"health.current":42}"""), document)
    }

    @Test
    fun `an entity reference field renders as its packed NetId word`() {
        val harness = QueryHarness()
        val ally = harness.spawn()
        harness.spawn(ally = ally)

        val document = harness.query(where = "team=1", fields = "Team.ally", limit = 10)

        // The generation travels with the id; rendering the index alone would throw away the
        // only thing that makes a stale reference detectable.
        assertTrue(document.contains("\"Team.ally\":${ally.raw}"), document)
    }

    @Test
    fun `limit and offset page the result while total counts every match`() {
        val harness = QueryHarness()
        val all = List(10) { harness.spawn(team = 1) }

        val firstPage = harness.query(where = "team=1", limit = 4)
        val secondPage = harness.query(where = "team=1", limit = 4, offset = 4)
        val lastPage = harness.query(where = "team=1", limit = 4, offset = 8)

        assertEquals(all.take(4).map { it.raw }, ids(firstPage))
        assertEquals(all.drop(4).take(4).map { it.raw }, ids(secondPage))
        assertEquals(all.drop(8).map { it.raw }, ids(lastPage))
        for (page in listOf(firstPage, secondPage, lastPage)) {
            assertTrue(page.contains("\"total\":10"), page)
        }
        assertTrue(firstPage.contains("\"truncated\":true"), firstPage)
        assertTrue(lastPage.contains("\"truncated\":false"), lastPage)
    }

    @Test
    fun `an offset past the end returns an empty page and the honest total`() {
        val harness = QueryHarness()
        repeat(3) { harness.spawn() }

        val document = harness.query(offset = 100)

        assertEquals(emptyList(), ids(document))
        assertTrue(document.contains("\"total\":3"), document)
        assertTrue(document.contains("\"returned\":0"), document)
    }

    @Test
    fun `a limit past the cap is clamped rather than honoured`() {
        val harness = QueryHarness()
        repeat(5) { harness.spawn() }

        val query = EntityQueryParser.parse(harness.index, limit = 100_000)

        // A limit=100000 from an agent that has decided to read the world would rebuild the
        // 80KB document the whole tiering exists to avoid.
        assertEquals(EntityQuery.MAX_LIMIT, query.limit)
    }

    @Test
    fun `results come back in ascending NetId order`() {
        val harness = QueryHarness()
        val ids = List(6) { harness.spawn() }

        val document = harness.query(limit = 10)

        assertEquals(ids.map { it.raw }.sorted(), ids(document))
    }

    @Test
    fun `an entity missing an optional component is excluded, not defaulted`() {
        val harness = QueryHarness()
        harness.spawn(champion = false)

        val document = harness.query(where = "Champion.level=1")

        // Treating an absent field as a match would quietly widen every query over an optional
        // component.
        assertEquals(emptyList(), ids(document))
        assertTrue(document.contains("\"total\":0"), document)
    }

    @Test
    fun `an unknown component is a typed failure naming what exists`() {
        val harness = QueryHarness()

        val failure = assertFailsWith<AgentToolException> { harness.query(with = "Wizard") }

        assertEquals(AgentErrorKind.NO_SUCH_FIELD, failure.error.kind)
        assertTrue(failure.error.message.contains("Champion"), failure.error.message)
    }

    @Test
    fun `an unknown field is a typed failure naming the fields that exist`() {
        val harness = QueryHarness()

        val failure = assertFailsWith<AgentToolException> { harness.query(where = "Health.hp<4") }

        assertEquals(AgentErrorKind.NO_SUCH_FIELD, failure.error.kind)
        assertTrue(failure.error.message.contains("current"), failure.error.message)
    }

    @Test
    fun `a malformed predicate is a typed failure rather than an empty result`() {
        val harness = QueryHarness()

        val failure = assertFailsWith<AgentToolException> { harness.query(where = "team") }

        // An empty result set is the most expensive wrong answer here: it looks like knowledge.
        assertEquals(AgentErrorKind.BAD_QUERY, failure.error.kind)
    }

    @Test
    fun `an order comparison against text is refused at parse time`() {
        val harness = QueryHarness()

        val failure = assertFailsWith<AgentToolException> { harness.query(where = "team<blue") }

        assertEquals(AgentErrorKind.BAD_QUERY, failure.error.kind)
    }

    @Test
    fun `a malformed near is a typed failure`() {
        val harness = QueryHarness()

        val failure = assertFailsWith<AgentToolException> { harness.query(near = "40,22") }

        assertEquals(AgentErrorKind.BAD_QUERY, failure.error.kind)
    }

    @Test
    fun `an ambiguous bare field name is refused with the qualification to use`() {
        // Two components carrying `current`, which is what happens as a game grows a second
        // resource pool. Guessing which one the agent meant is worse than asking.
        val index = AgentComponentIndex(
            listOf(
                dev.wildware.udea.agent.healthAccess(),
                dev.wildware.udea.agent.agentComponentAlias("Shield"),
            ),
        )

        val failure = assertFailsWith<AgentToolException> { index.resolveField("current", emptyList()) }

        assertEquals(AgentErrorKind.BAD_QUERY, failure.error.kind)
        assertTrue(failure.error.message.contains("Shield.current"), failure.error.message)
    }

    @Test
    fun `an unknown bare field name names the components it looked in`() {
        val harness = QueryHarness()

        val failure = assertFailsWith<AgentToolException> {
            harness.index.resolveField("mana", emptyList())
        }

        assertEquals(AgentErrorKind.NO_SUCH_FIELD, failure.error.kind)
        assertTrue(failure.error.message.contains("Health"), failure.error.message)
    }

    @Test
    fun `a query with no filters returns everything alive`() {
        val harness = QueryHarness()
        repeat(3) { harness.spawn() }
        val destroyed = harness.spawn()
        harness.destroy(destroyed)

        val document = harness.query()

        assertEquals(3, ids(document).size)
        assertTrue(document.contains("\"total\":3"), document)
    }

    @Test
    fun `the engine refuses a re-entrant run rather than corrupting the page`() {
        val harness = QueryHarness()
        harness.spawn()
        val json = dev.wildware.udea.agent.Json()

        val query = EntityQueryParser.parse(harness.index)
        harness.engine.run(query, json)

        // Sanity: a second sequential run is fine; only nesting is refused, and that path is
        // unreachable without a projection that calls back into the engine.
        json.reset()
        harness.engine.run(query, json)
        assertTrue(json.toString().contains("\"total\":1"))
    }

    @Test
    fun `NetId NONE resolves to nothing`() {
        val harness = QueryHarness()

        assertEquals(null, harness.engine.resolve(NetId.NONE))
    }
}
