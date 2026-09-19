package dev.wildware.moba.editor

import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Position
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorTags
import dev.wildware.udea.editor.PlaybackTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Play, Stop, Step and Play standalone, pressed in the editor window over a real `moba` world
 * (issue #196).
 *
 * Wired exactly as `runEditor` wires it - `MobaAgent.attach` with the editor tools, `MobaEditor.session`
 * over it - with the frame loop pumped by hand in place of a render thread. The world is the bundled
 * test level, so Play runs the real lane: waves spawn, walk and fight, and the component objects the
 * world holds are changed in place tick after tick. That is the case the issue's first criterion is
 * about - a Stop that put back Fleks' `snapshot()` map rather than decoding the bytes would hand
 * those same, since-changed objects back to the world.
 */
class MobaEditorPlayTest {

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost =
        MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
    private val session: MobaAgent.Session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)

    /** Every level file the standalone launcher was handed, in order. */
    private val launched = ArrayList<Path>()

    private val editor: EditorSession = MobaEditor.session(host, session, viewport = {}, standalone = { launched.add(it) })

    @AfterTest
    fun close() {
        session.close("test over")
    }

    @Test
    fun `play, a fight, then stop puts back the world hash from before play`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)
            val hashBefore = worldHash()
            val tickBefore = host.ctx.clock.tick
            val hpBefore = hitPoints()

            click(ui, PlaybackTags.PLAY)
            assertFalse(host.time.paused, "Play left the world paused")
            // The lane: the first wave leaves at tick 180 and the waves meet in the middle.
            frames(ui, FIGHT_FRAMES)

            val hpAfter = hitPoints()
            val hurt = hpBefore.keys.count { id -> hpAfter[id].let { it == null || it < hpBefore.getValue(id) } }
            assertTrue(hurt > 0, "nothing took damage or died in $FIGHT_FRAMES frames of play, so there was no fight to undo")
            assertNotEquals(hashBefore, worldHash(), "play changed nothing, so the test proves nothing")

            click(ui, PlaybackTags.STOP)

            assertTrue(host.time.paused, "Stop left the world running")
            assertEquals(tickBefore, host.ctx.clock.tick, "Stop did not put the clock back")
            assertEquals(hashBefore, worldHash(), "the world after Stop is not the world from before Play")
        }
    }

    @Test
    fun `step advances the tick counter by exactly one`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)
            val before = host.ctx.clock.tick

            click(ui, PlaybackTags.STEP)
            // More frames after the step's answer, so a step that set the world running would show.
            frames(ui)

            assertEquals(before + 1, host.ctx.clock.tick, "one press of Step")
            assertTrue(host.time.paused, "Step left the world running")

            click(ui, PlaybackTags.STEP)
            assertEquals(before + 2, host.ctx.clock.tick, "a second press of Step")
        }
    }

    @Test
    fun `an edit made during play is thrown away at stop, with its undo entry, and one made before stays`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)
            click(ui, EditorTags.SPAWN)
            val kept = historyAsAnAgent().single().second
            val hashWithEdit = worldHash()

            click(ui, PlaybackTags.PLAY)
            frames(ui, PLAY_FRAMES)
            click(ui, EditorTags.SPAWN)
            val history = historyAsAnAgent()
            assertEquals(2, history.size, "the spawn during play was not recorded: $history")
            val playTime = NetId.ofRaw(history.first().second)

            click(ui, PlaybackTags.STOP)

            assertEquals(hashWithEdit, worldHash(), "Stop did not put back the world the pre-play spawn left")
            assertEquals(listOf("editor.spawn" to kept), historyAsAnAgent(), "the undo entry made during play survived Stop")
            assertTrue("#$kept" in ui.text(EditorTags.HISTORY), "the history panel shows \"${ui.text(EditorTags.HISTORY)}\"")
            assertNull(positionOrNull(playTime), "the unit spawned during play is still there")

            // The edit from before Play is still the editor's to undo.
            click(ui, EditorTags.UNDO)
            assertNull(positionOrNull(NetId.ofRaw(kept)), "undo after Stop did not remove the unit spawned before Play")
            assertEquals(emptyList(), historyAsAnAgent())
        }
    }

    @Test
    fun `an edit session left open at stop is cancelled`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)
            click(ui, PlaybackTags.PLAY)
            val author = wiring.sessions.intern(MobaEditor.AUTHOR)
            val begun = agent("editor.begin_edit", "entities" to "${session.player.raw}", "fields" to "Position.x")
            val sessionId = Json.parseToJsonElement(begun).jsonObject.getValue("sessionId").jsonPrimitive.int

            click(ui, PlaybackTags.STOP)

            val update = AgentCommand("editor.update_edit", mapOf("sessionId" to "$sessionId", "values" to "Position.x=1"), session = author)
            val answer = run(update)
            val refused = assertIs<AgentResult.Failed>(answer, "the edit session was still open after Stop")
            assertEquals("no_such_edit", refused.error.kind.id)
        }
    }

    @Test
    fun `play standalone saves the level and hands the launcher a file that loads as this world`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)

            click(ui, PlaybackTags.PLAY_STANDALONE)

            val file = assertNotNull(launched.singleOrNull(), "the launcher was handed $launched")
            assertTrue(Files.size(file) > 0, "$file is empty")
            val level = host.game.levels.read(Files.readAllBytes(file))
            assertEquals(host.ctx.clock.tick, level.tick, "the level handed over is not this world's")
            assertContentEquals(host.game.levels.saveNow(), Files.readAllBytes(file), "the file is not the world as it stands")
            assertTrue(host.time.paused, "Play standalone set the editor's own world running")
        }
    }

    /**
     * Review round 1's repro: delete, Play, undo, ticks, Stop, undo. An undone delete hands the
     * world the very component objects the history held; had the undo during play been allowed, the
     * running game would have changed those objects and the unit would come back after Stop with
     * play-time values.
     */
    @Test
    fun `a delete from before play comes back after stop exactly as it was deleted`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)
            val unit = someUnit()
            val beforeDelete = worldHash()
            agent("editor.delete", "id" to "${unit.raw}")

            click(ui, PlaybackTags.PLAY)
            val duringPlay = run(AgentCommand("editor.undo", emptyMap(), session = wiring.sessions.intern(MobaEditor.AUTHOR)))
            frames(ui, FIGHT_FRAMES)
            click(ui, PlaybackTags.STOP)
            agent("editor.undo")

            assertEquals(beforeDelete, worldHash(), "the unit deleted before Play came back after Stop with values it did not have")
            val refused = assertIs<AgentResult.Failed>(duringPlay, "an undo of an edit from before Play ran while playing")
            assertEquals("undo_before_play", refused.error.kind.id)
        }
    }

    /**
     * Review round 1: Stop moves the clock back past everything the snapshot ring captured during
     * play. A step after it must not be refused by the ring for being older than its newest frame,
     * and the ring must no longer offer a frame from the play Stop threw away.
     */
    @Test
    fun `steps after stopping a long play run, and the ring holds nothing newer than the tick stop returned to`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)
            val start = host.ctx.clock.tick
            click(ui, PlaybackTags.PLAY)
            frames(ui, FIGHT_FRAMES)
            click(ui, PlaybackTags.STOP)

            repeat(3) { click(ui, PlaybackTags.STEP) }

            assertEquals(start + 3, host.ctx.clock.tick, "three Steps after Stop")
            val newest = host.time.listSnapshots().maxOfOrNull { it.tick }
            assertTrue(newest == null || newest <= start + 3, "the ring still holds $newest, from the play Stop discarded")
        }
    }

    /**
     * Review round 1: play, stop, play again, rewind. The second play must capture its own frames,
     * so a rewind lands in the second play's world and not in a frame the first play left behind.
     */
    @Test
    fun `a rewind during a second play lands in that play and not in the one before it`() {
        uiTest { editor.window.content() }.use { ui ->
            frames(ui)
            click(ui, PlaybackTags.PLAY)
            frames(ui, FIGHT_FRAMES)
            click(ui, PlaybackTags.STOP)

            click(ui, PlaybackTags.PLAY)
            // A different second play: the player walks, so the two plays' worlds part at once.
            agent("editor.set_field", "id" to "${session.player.raw}", "component" to "Position", "field" to "x", "value" to "123")
            agent("time.step", "ticks" to "$SECOND_PLAY_TICKS")
            val mark = worldHash()
            agent("time.step", "ticks" to "$REWIND_TICKS")
            agent("time.rewind", "ticks" to "$REWIND_TICKS")

            assertEquals(mark, worldHash(), "the rewind landed in a world other than this play's")
        }
    }

    /** Clicks [tag] and pumps until the click's call has run and been answered. */
    private fun click(ui: UiTest, tag: String) {
        assertTrue(ui.click(tag), "$tag took no click:\n${ui.dump()}")
        frames(ui)
    }

    /** Frames in the order `runWithGl` runs them: pump the loop, then the editor's frame. */
    private fun frames(ui: UiTest, count: Int = FRAMES) {
        repeat(count) {
            session.loop.pump(1f / 60f)
            editor.frame()
            ui.settle()
        }
    }

    /** `WorldHasher.hash` over a whole-world capture: fields, tick, random streams and the id allocator. */
    private fun worldHash(): Long =
        WorldHasher.hash(SnapshotService(MobaGame.componentRegistry(), host.world, host.ctx, host.ctx[CoreModule.NET_IDS]).capture())

    /** Every live unit's hit points, by `NetId`. */
    private fun hitPoints(): Map<NetId, Float> {
        val hp = LinkedHashMap<NetId, Float>()
        host.ctx[CoreModule.NET_IDS].forEachLive { id, entity ->
            with(host.world) { entity.getOrNull(Position)?.let { hp[id] = it.hp } }
        }
        return hp
    }

    /** `editor.history` as an outside agent calls it, under the session label `editor`. */
    private fun historyAsAnAgent(): List<Pair<String, Int>> =
        Json.parseToJsonElement(agent("editor.history", "limit" to "20")).jsonObject.getValue("edits").jsonArray.map {
            val edit = it.jsonObject
            edit.getValue("tool").jsonPrimitive.content to edit.getValue("id").jsonPrimitive.int
        }

    /** [tool] called as the `editor` author, asserting it succeeded. */
    private fun agent(tool: String, vararg args: Pair<String, String>): String {
        val answer = run(AgentCommand(tool, args.toMap(), session = wiring.sessions.intern(MobaEditor.AUTHOR)))
        return assertIs<AgentResult.Ok>(answer, "$tool failed: $answer").json
    }

    /**
     * Submits [command] and pumps until it has completed. More than one pump only for the tools that
     * answer after the tick (`time.step`, `time.rewind`), and never more than [MAX_PUMPS].
     */
    private fun run(command: AgentCommand): AgentResult {
        wiring.bridge.submit(command)
        var pumps = 0
        while (wiring.bridge.completedCommandId() < command.id) {
            check(pumps++ < MAX_PUMPS) { "${command.name} did not complete in $MAX_PUMPS frames" }
            session.loop.pump(1f / 60f)
        }
        return wiring.bridge.commandResults().single { it.id == command.id }.result
    }

    /** A live unit that is not the player, so deleting it leaves the match and the HUD alone. */
    private fun someUnit(): NetId {
        var found: NetId? = null
        host.ctx[CoreModule.NET_IDS].forEachLive { id, entity ->
            if (found == null && id != session.player && with(host.world) { entity.getOrNull(Position) } != null) found = id
        }
        return checkNotNull(found) { "the level has no unit but the player" }
    }

    private fun positionOrNull(id: NetId): Position? {
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(id) ?: return null
        return with(host.world) { entity.getOrNull(Position) }
    }

    private companion object {
        /** Enough for a click to be sent, run and answered, and for the history read it causes to come back. */
        const val FRAMES = 6

        /** Ticks of play for the lane to spawn its first waves and for them to fight. */
        const val FIGHT_FRAMES = 1200

        /** A little play: enough for the world to have moved on. */
        const val PLAY_FRAMES = 60

        /** How far the second play of the rewind test runs before its mark. */
        const val SECOND_PLAY_TICKS = 150

        /** How far past the mark it runs, and rewinds. */
        const val REWIND_TICKS = 60

        /** Frames [run] waits for a command before calling the wiring broken. */
        const val MAX_PUMPS = 10
    }
}
