package dev.wildware.moba.editor

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Position
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.moba.lane.LaneGeometry
import dev.wildware.moba.lane.Tower
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Play edits and Keep (issue #238), through the tools alone, as an agent tunes a running `moba` and
 * keeps what it tuned.
 *
 * The world is the bundled level, and every edit here is to a tower's `attackRange`, the number the
 * editor's range ring drags: a tower is in the level before Play, so an edit to one is keepable, and
 * the game reads the field every tick.
 */
class MobaPlayKeepTest {

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost =
        MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
    private val session: MobaAgent.Session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)

    @AfterTest
    fun close() {
        session.close("test over")
    }

    @Test
    fun `a kept play edit survives stop with its final value and is undoable, and an unkept one is gone`() {
        runUntilTowers()
        val (kept, dropped) = towers().take(2)
        agent("editor.play")
        pump(PLAY_TICKS)

        val keptEdit = setRange(kept, 240f)
        val droppedEdit = setRange(dropped, 60f)
        pump(PLAY_TICKS)
        val listed = playEdits()
        assertEquals(listOf(keptEdit, droppedEdit), listed.map { it.getValue("editId").jsonPrimitive.long }, "the play edits listed")
        assertTrue(listed.all { it.getValue("keepable").jsonPrimitive.boolean }, "an edit to a tower from before Play is keepable: $listed")
        assertTrue(listed.none { it.getValue("kept").jsonPrimitive.boolean }, "an edit is kept before anybody kept it: $listed")

        val answer = agent("editor.keep", "editId" to "$keptEdit")
        assertTrue(fields(answer).getValue("kept").jsonPrimitive.boolean, answer)
        assertEquals(listOf(true, false), playEdits().map { it.getValue("kept").jsonPrimitive.boolean })

        agent("editor.stop")

        assertEquals(240f, rangeOf(kept), "the kept edit's value did not survive Stop")
        assertEquals(LaneGeometry.TOWER_RANGE, rangeOf(dropped), "the edit nobody kept survived Stop")
        assertEquals(listOf("editor.set_field" to kept.raw), history(), "the kept edit is not one normal entry in its author's history")
        assertTrue(playEdits().isEmpty(), "Stop left play edits listed")

        agent("editor.undo")
        assertEquals(LaneGeometry.TOWER_RANGE, rangeOf(kept), "undoing the kept edit did not put the range back")
        assertTrue(history().isEmpty())
    }

    @Test
    fun `keep stores the value the edit left, not the change it made`() {
        runUntilTowers()
        val tower = towers().first()
        agent("editor.play")
        // An edit nobody keeps, then one on top of it that is kept: 200, then 230.
        setRange(tower, 200f)
        val kept = setRange(tower, 230f)
        agent("editor.keep", "editId" to "$kept")

        agent("editor.stop")

        // The value is 230. A kept change of +30 would have left 150 + 30.
        assertEquals(230f, rangeOf(tower))
    }

    @Test
    fun `unkeep takes a kept edit back, so stop drops it`() {
        runUntilTowers()
        val tower = towers().first()
        agent("editor.play")
        val edit = setRange(tower, 90f)
        agent("editor.keep", "editId" to "$edit")
        val answer = agent("editor.unkeep", "editId" to "$edit")
        assertFalse(fields(answer).getValue("kept").jsonPrimitive.boolean, answer)

        agent("editor.stop")

        assertEquals(LaneGeometry.TOWER_RANGE, rangeOf(tower))
        assertTrue(history().isEmpty(), "an unkept edit left an entry: ${history()}")
    }

    @Test
    fun `an edit to an entity spawned during play is not keepable, and stop drops it with no error`() {
        runUntilTowers()
        agent("editor.play")
        val spawned = NetId.ofRaw(fields(agent("editor.spawn", "blueprint" to "skeleton", "x" to "10", "y" to "10")).getValue("id").jsonPrimitive.int)
        val moved = fields(agent("editor.set_field", "id" to "${spawned.raw}", "component" to "Position", "field" to "x", "value" to "40"))
        assertEquals(40f, moved.getValue("value").jsonPrimitive.content.toFloat())

        val listed = playEdits()
        assertEquals(listOf("editor.spawn", "editor.set_field"), listed.map { it.getValue("tool").jsonPrimitive.content })
        for (edit in listed) {
            assertFalse(edit.getValue("keepable").jsonPrimitive.boolean, "an edit to a unit spawned during Play is keepable: $edit")
            assertTrue("spawned during Play" in edit.getValue("reason").jsonPrimitive.content, "$edit")
        }
        val refused = failure("editor.keep", "editId" to "${listed.last().getValue("editId").jsonPrimitive.long}")
        assertEquals("not_keepable", refused.error.kind.id, refused.toString())

        val stopped = run(AgentCommand("editor.stop", emptyMap(), session = author()))
        assertIs<AgentResult.Ok>(stopped, "Stop refused with an edit to a spawned unit listed")
        assertNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(spawned), "the unit spawned during Play survived Stop")
        assertTrue(history().isEmpty(), "Stop kept an edit to a unit that no longer exists: ${history()}")
    }

    @Test
    fun `keep and unkeep are refused with nothing playing, and for an edit that is not a play edit`() {
        runUntilTowers()
        val tower = towers().first()
        val before = setRange(tower, 120f)

        assertEquals("not_playing", failure("editor.keep", "editId" to "$before").error.kind.id)
        assertEquals("not_playing", failure("editor.unkeep", "editId" to "$before").error.kind.id)
        assertEquals(false, fields(agent("editor.play_edits")).getValue("playing").jsonPrimitive.boolean)

        agent("editor.play")
        // Made before Play: Stop keeps it anyway, so there is nothing to keep.
        assertEquals("no_such_play_edit", failure("editor.keep", "editId" to "$before").error.kind.id)
        assertEquals("no_such_play_edit", failure("editor.keep", "editId" to "999999").error.kind.id)
    }

    // --- fixture ----------------------------------------------------------------------------------

    /** `editor.set_field` on [tower]'s range, answering the edit's id: its place in `editor.history`. */
    private fun setRange(tower: NetId, range: Float): Long {
        agent("editor.set_field", "id" to "${tower.raw}", "component" to "Tower", "field" to "attackRange", "value" to "$range")
        return Json.parseToJsonElement(agent("editor.history", "limit" to "1")).jsonObject.getValue("edits").jsonArray
            .single().jsonObject.getValue("sequence").jsonPrimitive.long
    }

    private fun playEdits(): List<JsonObject> =
        fields(agent("editor.play_edits")).getValue("edits").jsonArray.map { it.jsonObject }

    private fun history(): List<Pair<String, Int>> =
        fields(agent("editor.history", "limit" to "20")).getValue("edits").jsonArray.map {
            val edit = it.jsonObject
            edit.getValue("tool").jsonPrimitive.content to edit.getValue("id").jsonPrimitive.int
        }

    private fun fields(answer: String): JsonObject = Json.parseToJsonElement(answer).jsonObject

    private fun runUntilTowers() {
        repeat(TOWER_BUDGET) {
            if (towers().size >= 2) return
            session.loop.pump(1f / 60f)
        }
        check(towers().size >= 2) { "fewer than two towers were placed within $TOWER_BUDGET frames" }
    }

    private fun towers(): List<NetId> {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val found = ArrayList<NetId>()
        with(host.world) { family { all(Tower, Position) }.forEach { found += netIds.netIdOf(it) } }
        return found
    }

    private fun rangeOf(id: NetId): Float = with(host.world) {
        checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is not in the world" }[Tower].attackRange
    }

    private fun pump(ticks: Int) = repeat(ticks) { session.loop.pump(1f / 60f) }

    private fun author() = wiring.sessions.intern(MobaEditor.AUTHOR)

    /** [tool] as the `editor` author, asserting it succeeded. */
    private fun agent(tool: String, vararg args: Pair<String, String>): String {
        val answer = run(AgentCommand(tool, args.toMap(), session = author()))
        return assertIs<AgentResult.Ok>(answer, "$tool failed: $answer").json
    }

    /** [tool] as the `editor` author, asserting it was refused. */
    private fun failure(tool: String, vararg args: Pair<String, String>): AgentResult.Failed {
        val answer = run(AgentCommand(tool, args.toMap(), session = author()))
        return assertIs<AgentResult.Failed>(answer, "$tool was not refused: $answer")
    }

    private fun run(command: AgentCommand): AgentResult {
        wiring.bridge.submit(command)
        var pumps = 0
        while (wiring.bridge.completedCommandId() < command.id) {
            check(pumps++ < MAX_PUMPS) { "${command.name} did not complete in $MAX_PUMPS frames" }
            session.loop.pump(1f / 60f)
        }
        return wiring.bridge.commandResults().single { it.id == command.id }.result
    }

    private companion object {
        const val TOWER_BUDGET = 60
        const val PLAY_TICKS = 30
        const val MAX_PUMPS = 10
    }
}
