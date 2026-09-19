package dev.wildware.moba.editor

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.moba.MobaGame
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.Team
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorTags
import dev.wildware.udea.editor.InspectorTags
import dev.wildware.udea.editor.MIXED
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Inspector (issue #235) against the real `EditorToolset`, in a real `moba` world: two units on
 * different teams show **Mixed** for `GameUnit.team`; typing a team into it gives both that team
 * while it is typed, Enter files it as one edit, and one Undo takes it back from both.
 *
 * `InspectorTest` in `udea-editor` answers the panel's calls by hand; this is the other half, wired
 * as `runEditor` wires it, with the frame loop pumped by hand as `MobaEditorTest` pumps it. The two
 * units are selected by an agent's `editor.select` under the editor's author, so this also shows the
 * window following a selection it did not make.
 */
class MobaInspectorTest {

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost =
        MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
    private val session: MobaAgent.Session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)

    @AfterTest
    fun close() {
        session.close("test over")
    }

    @Test
    fun `two units on different teams show Mixed for their team, and typing one team gives it to both as one undoable edit`() {
        val editor = MobaEditor.session(host, session)
        val (first, second) = unitsOnTwoTeams()
        val firstTeam = teamOf(first)
        val secondTeam = teamOf(second)
        assertNotEquals(firstTeam, secondTeam, "the level has no two units on different teams to select")
        // A team neither has, so the one edit changes both and the one undo has both to put back.
        val shared = (0..TEAMS).first { it != firstTeam && it != secondTeam }

        uiTest { editor.window.content() }.use { ui ->
            frames(ui, editor)
            asAnAgent("editor.select", "entities" to "${first.raw},${second.raw}")
            frames(ui, editor)
            assertEquals(listOf(MIXED), ui.texts(InspectorTags.field(TEAM)), "the teams differ, and the field shows otherwise")
            val editsBefore = historyAsAnAgent()

            assertTrue(ui.click(InspectorTags.field(TEAM)), "no box for $TEAM:\n${ui.dump()}")
            ui.type("$shared")
            frames(ui, editor)
            // Live while typing: both units hold the typed team before anything is committed.
            assertEquals(shared, teamOf(first), "typing did not change the first unit")
            assertEquals(shared, teamOf(second), "typing did not change the second unit")
            assertEquals(editsBefore, historyAsAnAgent(), "typing filed an edit before it was committed")

            ui.key(Key.Enter)
            frames(ui, editor)
            assertEquals(shared, teamOf(first), "Enter lost the first unit's team")
            assertEquals(shared, teamOf(second), "Enter lost the second unit's team")
            assertEquals(listOf("$shared"), ui.texts(InspectorTags.field(TEAM)), "the field does not show the one team they now share")
            val edits = historyAsAnAgent()
            assertEquals(listOf("editor.commit_edit") + editsBefore, edits, "one typed value is not one edit in the editor's history")

            assertTrue(ui.click(EditorTags.UNDO), "the undo button took no click")
            frames(ui, editor)
            assertEquals(firstTeam, teamOf(first), "one undo did not put the first unit back")
            assertEquals(secondTeam, teamOf(second), "one undo did not put the second unit back")
            assertEquals(listOf(MIXED), ui.texts(InspectorTags.field(TEAM)), "after the undo the teams differ again")
        }
    }

    /** One unit on a team, and one on another. */
    private fun unitsOnTwoTeams(): Pair<NetId, NetId> {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val units = ArrayList<Pair<NetId, Int>>()
        with(host.world) {
            family { all(GameUnit) }.forEach { entity ->
                val team = entity[GameUnit].team
                val id = netIds.netIdOf(entity)
                if (team != Team.NONE && !id.isNone) units += id to team
            }
        }
        val first = units.first()
        val second = units.first { it.second != first.second }
        return first.first to second.first
    }

    private fun teamOf(id: NetId): Int {
        val entity = checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is not in the world" }
        return with(host.world) { entity[GameUnit].team }
    }

    /** A few frames in the order `runWithGl` runs them: pump the loop, then the editor's frame. */
    private fun frames(ui: UiTest, editor: EditorSession) {
        repeat(FRAMES) {
            session.loop.pump(1f / 60f)
            editor.frame()
            ui.settle()
        }
    }

    /** [tool] as an outside agent calls it, under the editor's author. */
    private fun asAnAgent(tool: String, vararg args: Pair<String, String>): String {
        val command = AgentCommand(tool, args.toMap(), session = wiring.sessions.intern(MobaEditor.AUTHOR))
        wiring.bridge.submit(command)
        session.loop.pump(1f / 60f)
        val answer = wiring.bridge.commandResults().single { it.id == command.id }.result
        check(answer is AgentResult.Ok) { "$tool failed: $answer" }
        return answer.json
    }

    /** The tools of the editor author's history, newest first, as an agent reads them. */
    private fun historyAsAnAgent(): List<String> =
        Json.parseToJsonElement(asAnAgent("editor.history", "limit" to "20")).jsonObject.getValue("edits").jsonArray
            .map { it.jsonObject.getValue("tool").jsonPrimitive.content }

    private companion object {
        const val TEAM = "GameUnit.team"

        /** More team numbers than two units can be on, so one of them is on neither. */
        const val TEAMS = 3

        /** Enough for a click to be sent, run and answered, and for the reads it causes to come back. */
        const val FRAMES = 6
    }
}
