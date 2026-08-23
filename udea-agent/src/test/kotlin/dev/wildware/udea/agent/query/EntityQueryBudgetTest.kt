package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.AllocationProbe
import dev.wildware.udea.agent.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A query over 500 entities returning 20, under a millisecond and with bounded allocation.
 *
 * Run by `udeaQueryBudget` on `check`, alongside the digest gate. It is a budget rather than a
 * benchmark because of *where* it runs: a query is a tool call, and a tool call is applied
 * inside a `SimBarrier` drain at the top of a tick. A query that takes ten milliseconds is not a
 * slow tool, it is a dropped frame - and an agent debugging a game with `query_entities` calls
 * it constantly.
 *
 * "Bounded" rather than "zero" for allocation, honestly: `Replicator.getField` boxes, which the
 * frozen contract permits precisely here, so the walk allocates one box per predicate per
 * entity. The ceiling asserts it is proportional to that and not to anything worse - no
 * per-entity result object, no intermediate list, no string built and thrown away.
 */
class EntityQueryBudgetTest {

    private fun harnessOf(population: Int): QueryHarness {
        val harness = QueryHarness()
        for (index in 0 until population) {
            harness.spawn(
                x = (index % 100).toFloat(),
                y = (index / 100).toFloat(),
                health = (index % 1000).toFloat(),
                team = if (index % 2 == 0) 1 else 2,
                champion = index % 3 == 0,
            )
        }
        return harness
    }

    @Test
    fun `a query over 500 entities returning 20 stays under a millisecond`() {
        val harness = harnessOf(ENTITIES)
        val query = EntityQueryParser.parse(
            harness.index,
            with = "Champion,Health",
            where = "team=1,health.current<400",
            near = "40,2,15",
            fields = "id,pos,health.current",
            limit = 20,
        )
        val json = Json()

        repeat(WARMUPS) {
            json.reset()
            harness.engine.run(query, json)
        }

        val samples = LongArray(SAMPLES)
        for (index in 0 until SAMPLES) {
            json.reset()
            val startedAt = System.nanoTime()
            harness.engine.run(query, json)
            samples[index] = System.nanoTime() - startedAt
        }
        samples.sort()
        val median = samples[SAMPLES / 2]

        println("query over $ENTITIES entities: median ${median}ns (budget ${BUDGET_NANOS}ns)")
        assertTrue(
            median <= BUDGET_NANOS,
            "query median was ${median}ns, over the ${BUDGET_NANOS}ns budget",
        )
    }

    @Test
    fun `the page is the requested size and the total is the whole match`() {
        val harness = harnessOf(ENTITIES)
        val query = EntityQueryParser.parse(harness.index, where = "team=1", limit = 20)
        val json = Json()

        val summary = harness.engine.run(query, json)

        assertEquals(ENTITIES / 2, summary.total)
        assertEquals(20, summary.returned)
        assertTrue(summary.truncated)
    }

    @Test
    fun `allocation is bounded by the boxed field reads, not by the world`() {
        if (!AllocationProbe.isSupported) return
        val harness = harnessOf(ENTITIES)
        val query = EntityQueryParser.parse(
            harness.index,
            where = "team=1,health.current<400",
            fields = "id,health.current",
            limit = 20,
        )
        val json = Json()

        val bytes = AllocationProbe.bytesAllocated {
            json.reset()
            harness.engine.run(query, json)
        }

        println("query allocation at $ENTITIES entities: ${bytes}B")
        assertTrue(
            bytes <= ALLOCATION_CEILING,
            "a query allocated ${bytes}B, over the ${ALLOCATION_CEILING}B ceiling; something " +
                "on the walk is building objects per entity",
        )
    }

    private companion object {
        const val ENTITIES: Int = 500
        const val WARMUPS: Int = 2_000
        const val SAMPLES: Int = 201
        const val BUDGET_NANOS: Long = 1_000_000L

        /**
         * Two boxed floats and one boxed int per entity, generously: 500 entities x 3 x 16
         * bytes is 24KB, and the ceiling sits just above it. A per-entity *result* object would
         * push straight through it.
         */
        const val ALLOCATION_CEILING: Long = 32_768L
    }
}
