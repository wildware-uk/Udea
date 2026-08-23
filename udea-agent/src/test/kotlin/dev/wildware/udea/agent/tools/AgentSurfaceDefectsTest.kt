package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.query.EntityQueryEngine
import dev.wildware.udea.agent.query.EntityQueryParser
import dev.wildware.udea.agent.state.DigestBudgets
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.CueSink
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three defects a play-agent found by driving the surface through a whole battle.
 *
 * Each was invisible from inside the engine and obvious from outside it, which is the reason
 * these are asserted from the tool call rather than from the class under it:
 *
 * 1. `world.query_entities` **silently lost its answer** above about twenty rows, so "every
 *    unit's HP" in a 27-unit level was unreadable - the first question anybody asks about a
 *    fight;
 * 2. nothing in any game ever recorded an event, so `events.recent_events` reported
 *    `totalRecorded: 1` after a full battle and that one entry was the agent's own rewind;
 * 3. an argument the tool does not declare was **dropped**, and the call answered `ok:true` -
 *    a wrong answer wearing a success, against a schema that publishes
 *    `additionalProperties: false`.
 */
class AgentSurfaceDefectsTest {

    /** A recording [TextSpill], standing where `AgentArtifacts.textSpill()` stands in a host. */
    private class Recorder : TextSpill {
        val filed: MutableList<String> = ArrayList()

        override fun spill(text: String): String? {
            filed.add(text)
            return "cap_%04d".format(filed.size)
        }
    }

    // --- 1. the answer that used to vanish -------------------------------------------------

    /**
     * The old answer does not reach the agent and the new one does, through the real digest path.
     *
     * Both halves are rendered into a document padded to exactly the bytes `commandResults` is
     * *guaranteed* - [DigestBudgets.RESULT_MIN_BYTES], what is left once `ui.elements` has spent
     * everything [DigestBudgets.LABEL_CEILING] allows. That padding is the whole point:
     * [DigestBudgets.RESULT_CEILING] is only the leftovers of a quiet frame, so measuring against
     * it would test a 27-row answer against five times the budget it has to survive, and the old
     * answer would look fine right up until a frame with a HUD on it.
     */
    @Test
    fun `the old twenty-seven row answer never arrived and the paged one does`() {
        val harness = ToolsetHarness()
        repeat(UNITS) { index -> harness.place(x = index.toFloat(), health = 40f + index) }

        // The document the tool used to answer with, measured rather than estimated: the engine
        // still renders it, and it is exactly what `AgentResult.Ok` used to carry.
        val engine = EntityQueryEngine(harness.components, harness.netIds, harness.world)
        val unpaged = engine.render(
            EntityQueryParser.parse(
                index = harness.components,
                with = "Health",
                where = null,
                near = null,
                fields = "health.current",
                limit = 200,
                offset = 0,
            ),
        )
        assertEquals(
            UNITS,
            Regex("\"id\":").findAll(unpaged).count(),
            "the premise is $UNITS matched rows: $unpaged",
        )

        val old = AgentBridge()
        old.complete(1L, AgentResult.Ok(unpaged))
        val oldDocument = paddedToGuarantee()
        old.renderCommandResults(
            oldDocument,
            "commandResults",
            DigestBudgets.RESULT_LIMIT,
            DigestBudgets.RESULT_CEILING,
        )
        oldDocument.endObject()
        assertTrue(
            !oldDocument.toString().contains("health.current"),
            "the ${unpaged.length}-character answer must not reach the agent on the bytes it is " +
                "guaranteed, or the defect this test is named after did not exist: $oldDocument",
        )

        val paged = AgentBridge()
        paged.complete(
            1L,
            AgentResult.Ok(
                harness.ok(
                    "world.query_entities",
                    "with" to "Health",
                    "fields" to "health.current",
                    "limit" to "200",
                ),
            ),
        )
        val pagedDocument = paddedToGuarantee()
        val truncated = paged.renderCommandResults(
            pagedDocument,
            "commandResults",
            DigestBudgets.RESULT_LIMIT,
            DigestBudgets.RESULT_CEILING,
        )
        pagedDocument.endObject()
        val delivered = pagedDocument.toString()
        assertTrue(!truncated, "the paged answer was dropped on the bytes it is guaranteed: $delivered")
        assertTrue(delivered.contains("health.current"), "rows must arrive inline: $delivered")
        assertTrue(delivered.contains("nextOffset"), "and a way to the rest of them: $delivered")
    }

    /**
     * A document already at the point where the guarantee is all `commandResults` has left.
     *
     * The same fixture `ResultPageTest` uses, and for the same reason: a page rendered into an
     * empty document is measured against a budget it never has to live inside.
     */
    private fun paddedToGuarantee(): Json {
        val target = DigestBudgets.RESULT_CEILING - DigestBudgets.RESULT_MIN_BYTES
        val probe = Json()
        probe.beginObject()
        probe.put("filler", "")
        val json = Json()
        json.beginObject()
        json.put("filler", "x".repeat(target - probe.length))
        check(json.length == target) { "padding produced ${json.length} characters, not $target" }
        return json
    }

    /**
     * All 27 rows come back from one call, through the handle the answer publishes.
     *
     * The page itself is small by design - `MAX_PAGE_BYTES` comes from the bytes a result is
     * guaranteed, not the bytes it usually gets - so the whole answer travels as an artifact and
     * the page carries its id. That is one round trip for 27 rows, which is the property the
     * tool was failing.
     */
    @Test
    fun `all twenty-seven rows arrive from one call through resultRef`() {
        val store = Recorder()
        val harness = ToolsetHarness(spill = store)
        val ids = List(UNITS) { index -> harness.place(x = index.toFloat(), health = 40f + index) }

        val page = harness.ok(
            "world.query_entities",
            "with" to "Health",
            "fields" to "health.current",
            "limit" to "200",
        )

        assertTrue(page.contains("\"total\":$UNITS"), "every match must be counted: $page")
        val reference = assertNotNull(
            Regex("\"resultRef\":\"(cap_\\d+)\"").find(page)?.groupValues?.get(1),
            "an answer too large for a page must publish the handle to the whole of it: $page",
        )
        assertEquals(1, store.filed.size, "one call must file one artifact")

        // What `GET /artifact?id=<resultRef>` would serve.
        val whole = store.filed.single()
        assertEquals(
            UNITS,
            Regex("\"id\":").findAll(whole).count(),
            "the filed answer must carry every row, not a copy of the truncated page: $whole",
        )
        assertTrue(whole.contains("\"returned\":$UNITS"), whole)
        for (netId in ids) {
            assertTrue(whole.contains("\"id\":${netId.raw}"), "row for ${netId.raw} is missing: $whole")
        }
        assertTrue(reference.startsWith("cap_"), reference)
    }

    /**
     * Following `nextOffset` walks the whole match with no artifact store at all.
     *
     * The degraded path, and it has to be a complete one: a `SimHarness`, a unit test and a
     * headless process have nowhere to file bytes, and each of them still has to be able to read
     * 27 rows. Paging is what makes that true without a host.
     */
    @Test
    fun `nextOffset walks every row when no store is wired`() {
        val harness = ToolsetHarness()
        repeat(UNITS) { index -> harness.place(x = index.toFloat(), health = 40f + index) }

        val seen = LinkedHashSet<String>()
        var offset = 0
        var calls = 0
        while (true) {
            calls++
            val page = harness.ok(
                "world.query_entities",
                "with" to "Health",
                "fields" to "health.current",
                "limit" to "200",
                "offset" to offset.toString(),
            )
            assertTrue(page.contains("\"total\":$UNITS"), page)
            assertTrue(
                !page.contains("resultRef"),
                "no store was wired, so there is nothing to hand back a handle to: $page",
            )
            Regex("\"id\":(-?\\d+)").findAll(page).forEach { seen.add(it.groupValues[1]) }
            val next = Regex("\"nextOffset\":(\\d+)").find(page)?.groupValues?.get(1)?.toInt() ?: break
            assertTrue(next > offset, "a page that advertises more must advance the offset: $page")
            offset = next
            assertTrue(calls < UNITS + 2, "paging must terminate; it has made $calls calls")
        }
        assertEquals(UNITS, seen.size, "every matched row must be reachable by following nextOffset")
        assertTrue(calls > 1, "a $UNITS-row answer cannot fit one page; this test proved nothing")
    }

    // --- 2. gameplay events -----------------------------------------------------------------

    /**
     * A battle's cues reach `events.recent_events` as deaths and hits, in the ring's own shape.
     *
     * The mirror is what a game's *agent* entry point installs over `GameContext.cues`; the
     * simulation keeps emitting exactly the cues it already emits, and the mixer keeps receiving
     * every one of them.
     */
    @Test
    fun `deaths and hits emitted as cues are readable through recent_events`() {
        val harness = ToolsetHarness()
        val played = CueQueue()
        val mirror = CueEventMirror(played, harness.bridge, MirroredCues::nameOf)
        val victim = harness.place(health = 0f)
        val attacker = harness.place(health = 90f)

        // What a fight does: swings, hits, a heal, and a death.
        mirror.emit(Cue(CueId(MirroredCues.MELEE_HIT), Tick(11L), attacker))
        mirror.emit(Cue(CueId(MirroredCues.MELEE_HIT), Tick(12L), attacker))
        mirror.emit(Cue(CueId(MirroredCues.HEAL), Tick(13L), attacker))
        mirror.emit(Cue(CueId(MirroredCues.DEATH), Tick(14L), victim))

        assertEquals(4, harness.bridge.events.totalRecorded, "every cue must reach the ring")
        assertEquals(
            4,
            played.size,
            "the mixer must still receive every cue; a mirror that consumed them would " +
                "silence the game",
        )

        val deaths = harness.ok("events.recent_events", "contains" to "game:death")
        assertTrue(deaths.contains("\"total\":1"), deaths)
        assertTrue(
            deaths.contains("#${victim.index}@${victim.generation}"),
            "a death must name who died, in the spelling world.describe_entity takes: $deaths",
        )
        assertTrue(deaths.contains("\"tick\":14"), "the cue's own tick, not the clock's: $deaths")

        val hits = harness.ok("events.recent_events", "contains" to "game:melee_hit")
        assertTrue(hits.contains("\"total\":2"), hits)

        // And the whole battle is one filter away from the agent's own audit lines.
        val everything = harness.ok("events.recent_events", "contains" to "game:")
        assertTrue(everything.contains("\"total\":4"), everything)
    }

    /**
     * A filtered mirror records only what a game asked for, and still plays everything.
     *
     * The knob that keeps this off a hot path: a game that fires a cue per tick per unit passes
     * the handful of ids that answer a question rather than paying a string for all of them.
     */
    @Test
    fun `a filtered mirror records only the cues a game named`() {
        val bridge = AgentBridge()
        val played = ArrayList<Cue>()
        val sink = object : CueSink {
            override fun emit(cue: Cue) {
                played.add(cue)
            }
        }
        val mirror = CueEventMirror(
            delegate = sink,
            bridge = bridge,
            nameOf = MirroredCues::nameOf,
            include = { it == MirroredCues.DEATH },
        )

        mirror.emit(Cue(CueId(MirroredCues.MELEE_HIT), Tick(1L), NetId.NONE))
        mirror.emit(Cue(CueId(MirroredCues.DEATH), Tick(2L), NetId.NONE))

        assertEquals(1L, mirror.mirrored)
        assertEquals(1, bridge.events.totalRecorded)
        assertEquals(2, played.size, "filtering the ring must not filter the game")
    }

    // --- 3. the typo that used to succeed ----------------------------------------------------

    @Test
    fun `a mistyped argument is a typed error naming it and the accepted names`() {
        val harness = ToolsetHarness()
        harness.place(health = 40f)

        val error = harness.failure(
            "world.query_entities",
            "with" to "Health",
            // One letter from `limit`, which is exactly how this arrives in practice.
            "limits" to "200",
        )

        assertEquals(ArgumentCheck.UNKNOWN_ARGUMENT, error.kind)
        assertTrue(error.message.contains("limits"), "the offender must be named: ${error.message}")
        assertTrue(
            error.message.contains("Did you mean limit?"),
            "a one-edit typo must be fixable without a round trip: ${error.message}",
        )
        assertTrue(
            error.message.contains("with, where, near, fields, limit, offset"),
            "the accepted set must be in the answer: ${error.message}",
        )
    }

    @Test
    fun `an argument invented from another tool gets no misleading suggestion`() {
        val harness = ToolsetHarness()

        val error = harness.failure("events.recent_events", "blueprint" to "grunt")

        assertEquals(ArgumentCheck.UNKNOWN_ARGUMENT, error.kind)
        assertTrue(
            !error.message.contains("Did you mean"),
            "nothing here is a misspelling of blueprint, and a wrong guess costs a call to " +
                "disprove: ${error.message}",
        )
        assertTrue(error.message.contains("limit, offset, contains"), error.message)
    }

    @Test
    fun `a tool that takes no arguments says so rather than listing nothing`() {
        val harness = ToolsetHarness()

        val error = harness.failure("world.list_components", "with" to "Health")

        assertEquals(ArgumentCheck.UNKNOWN_ARGUMENT, error.kind)
        assertTrue(error.message.contains("takes no arguments at all"), error.message)
    }

    @Test
    fun `a correctly spelled call is untouched`() {
        val harness = ToolsetHarness()
        val netId = harness.place(health = 40f)

        val found = harness.ok("world.query_entities", "with" to "Health", "fields" to "health.current")

        assertTrue(found.contains("\"id\":${netId.raw}"), found)
    }

    /** A stand-in for a game's cue table - the shape `MobaCues` already has. */
    private object MirroredCues {
        const val MELEE_HIT: Int = 2
        const val HEAL: Int = 5
        const val DEATH: Int = 9

        fun nameOf(id: Int): String = when (id) {
            MELEE_HIT -> "melee_hit"
            HEAL -> "heal"
            DEATH -> "death"
            else -> "cue:$id"
        }
    }

    private companion object {
        /** The unit count of the level the play-agent drove. */
        const val UNITS: Int = 27

    }
}
