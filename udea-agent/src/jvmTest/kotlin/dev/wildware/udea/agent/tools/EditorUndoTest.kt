package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Health
import dev.wildware.udea.agent.Team
import dev.wildware.udea.agent.Transform
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.core.identity.NetId
import kotlinx.io.files.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `editor.*` tools and their undo history, driven only through `SimHarness` with no graphics
 * (issue #193).
 *
 * Every call here crosses the bridge, is drained onto the `SimBarrier` and dispatched the way an
 * HTTP command is, so the author a call is recorded under is the `AgentSessionId` the command
 * carried - exactly what `AgentHost` stamps on a command from `?session=`.
 */
class EditorUndoTest {

    @Test
    fun `a sequence of edits then the same number of undos returns the world hash to its start`() {
        val harness = ToolsetHarness(withEditor = true)
        val hero = harness.place(x = 1f, y = 2f, health = 100f, team = 1)
        val bystander = harness.place(x = 5f, y = 5f, health = 80f, team = 2)
        val start = harness.fieldHash()

        val author = harness.author("designer")
        // Five different kinds of edit, including a field `world.set_component_field` refuses.
        edit(harness, author, "editor.set_field", "id" to hero.raw.toString(), "component" to "Health", "field" to "max", "value" to "250")
        edit(harness, author, "editor.move", "id" to hero.raw.toString(), "x" to "40", "y" to "-3")
        val spawned = edit(harness, author, "editor.spawn", "blueprint" to "grunt", "x" to "9", "y" to "9")
        edit(harness, author, "editor.delete", "id" to bystander.raw.toString())
        edit(harness, author, "editor.set_field", "id" to hero.raw.toString(), "component" to "Team", "field" to "team", "value" to "3")

        assertNotEquals(start, harness.fieldHash(), "five edits left the world hash unchanged, so the test proves nothing")
        assertNull(harness.netIds.resolveOrNull(bystander), "a deleted entity still resolves")
        assertTrue(spawned.contains("\"id\":"), spawned)

        repeat(5) { edit(harness, author, "editor.undo") }

        assertEquals(start, harness.fieldHash())
        // The deleted entity came back under the very id it had, generation included.
        val restored = assertNotNull(harness.netIds.resolveOrNull(bystander), "undo of a delete did not restore the NetId")
        with(harness.world) {
            assertEquals(80f, restored[Health].current)
            assertEquals(2, restored[Team].team)
        }
    }

    @Test
    fun `an undo is refused when another author changed the field since, naming them, and overwrite succeeds`() {
        val harness = ToolsetHarness(withEditor = true)
        val hero = harness.place(health = 100f)
        val alice = harness.author("alice")
        val bob = harness.author("bob")

        edit(harness, alice, "editor.set_field", "id" to hero.raw.toString(), "component" to "Health", "field" to "current", "value" to "10")
        edit(harness, bob, "editor.set_field", "id" to hero.raw.toString(), "component" to "Health", "field" to "current", "value" to "20")

        val refused = assertIs<AgentResult.Failed>(harness.sim.call("editor.undo", emptyMap(), alice))
        assertEquals(EditorToolset.EDIT_CONFLICT, refused.error.kind)
        assertTrue(refused.error.message.contains("bob"), refused.error.message)
        assertTrue(refused.error.message.contains("overwrite=true"), refused.error.message)
        assertEquals(20f, health(harness, hero), "a refused undo must not write")

        edit(harness, alice, "editor.undo", "overwrite" to "true")

        assertEquals(100f, health(harness, hero))
    }

    @Test
    fun `each author undoes only their own edits`() {
        val harness = ToolsetHarness(withEditor = true)
        val hero = harness.place(x = 0f, y = 0f, health = 100f)
        val alice = harness.author("alice")
        val bob = harness.author("bob")

        edit(harness, alice, "editor.move", "id" to hero.raw.toString(), "x" to "7", "y" to "7")
        edit(harness, bob, "editor.set_field", "id" to hero.raw.toString(), "component" to "Health", "field" to "current", "value" to "1")

        edit(harness, alice, "editor.undo")

        with(harness.world) {
            val entity = assertNotNull(harness.netIds.resolveOrNull(hero))
            assertEquals(0f, entity[Transform].position.x, "alice's move was not undone")
            assertEquals(1f, entity[Health].current, "alice's undo reverted bob's edit")
        }
        val history = edit(harness, bob, "editor.history")
        assertTrue(history.contains("editor.set_field"), history)
        assertTrue(!history.contains("editor.move"), "bob's history shows alice's move: $history")
    }

    @Test
    fun `undoing a spawn another author has since edited is refused naming them`() {
        val harness = ToolsetHarness(withEditor = true)
        val alice = harness.author("alice")
        val bob = harness.author("bob")
        val spawned = edit(harness, alice, "editor.spawn", "blueprint" to "grunt", "x" to "1", "y" to "1")
        val id = ID.find(spawned)!!.groupValues[1]

        edit(harness, bob, "editor.move", "id" to id, "x" to "4", "y" to "4")

        val refused = assertIs<AgentResult.Failed>(harness.sim.call("editor.undo", emptyMap(), alice))
        assertEquals(EditorToolset.EDIT_CONFLICT, refused.error.kind)
        assertTrue(refused.error.message.contains("bob"), refused.error.message)

        edit(harness, alice, "editor.undo", "overwrite" to "true")
        assertNull(harness.netIds.resolveOrNull(NetId.ofRaw(id.toInt())))
    }

    @Test
    fun `every edit answers with its reverse and the value it left behind`() {
        val harness = ToolsetHarness(withEditor = true)
        val hero = harness.place(x = 1f, y = 2f)
        val author = harness.author("designer")

        val moved = edit(harness, author, "editor.move", "id" to hero.raw.toString(), "x" to "30", "y" to "40")

        assertTrue(moved.contains("\"value\":{\"x\":30,\"y\":40}"), moved)
        assertTrue(
            moved.contains("\"reverse\":{\"tool\":\"editor.move\",\"args\":{\"id\":\"${hero.raw}\",\"x\":\"1.0\",\"y\":\"2.0\"}}"),
            moved,
        )
    }

    @Test
    fun `a drag reports where it started, so the reverse returns there rather than to the release point`() {
        val harness = ToolsetHarness(withEditor = true)
        // The window has already moved the sprite to 30,40 while it was dragged; the edit is
        // recorded once, on release, with the point the drag began.
        val hero = harness.place(x = 30f, y = 40f)
        val author = harness.author("designer")

        edit(harness, author, "editor.move", "id" to hero.raw.toString(), "x" to "30", "y" to "40", "fromX" to "1", "fromY" to "2")
        edit(harness, author, "editor.undo")

        with(harness.world) {
            val transform = assertNotNull(harness.netIds.resolveOrNull(hero))[Transform]
            assertEquals(1f, transform.position.x)
            assertEquals(2f, transform.position.y)
        }
    }

    @Test
    fun `history keeps the newest thousand edits per author and drops the oldest`() {
        val harness = ToolsetHarness(withEditor = true)
        val hero = harness.place(health = 0f)
        val author = harness.author("designer")

        for (value in 1..EditorToolset.HISTORY_CAPACITY + 1) {
            edit(harness, author, "editor.set_field", "id" to hero.raw.toString(), "component" to "Health", "field" to "current", "value" to "$value")
        }
        repeat(EditorToolset.HISTORY_CAPACITY) { edit(harness, author, "editor.undo") }

        val empty = assertIs<AgentResult.Failed>(harness.sim.call("editor.undo", emptyMap(), author))
        assertEquals(EditorToolset.NOTHING_TO_UNDO, empty.error.kind)
        // The first edit, 0 -> 1, fell off the end: undoing everything left lands on its result.
        assertEquals(1f, health(harness, hero))
    }

    @Test
    fun `save refuses a name that would leave the level directory`() {
        val harness = ToolsetHarness(withEditor = true, levelDirectory = Path(createTempDirectory("editor-save").toString()))

        val refused = assertIs<AgentResult.Failed>(harness.sim.call("editor.save", mapOf("name" to "../escape"), harness.author("designer")))

        assertEquals(EditorToolset.BAD_LEVEL_NAME, refused.error.kind)
    }

    @Test
    fun `the editor writes a field world set_component_field refuses`() {
        val harness = ToolsetHarness(withEditor = true)
        val hero = harness.place()

        val refused = harness.failure("world.set_component_field", "id" to hero.raw.toString(), "component" to "Health", "field" to "max", "value" to "5")
        assertEquals(WorldToolset.FIELD_NOT_WRITABLE, refused.kind)

        edit(harness, harness.author("designer"), "editor.set_field", "id" to hero.raw.toString(), "component" to "Health", "field" to "max", "value" to "5")
        with(harness.world) {
            assertEquals(5f, assertNotNull(harness.netIds.resolveOrNull(hero))[Health].max)
        }
    }

    private fun edit(harness: ToolsetHarness, author: AgentSessionId, tool: String, vararg args: Pair<String, String>): String {
        val result = harness.sim.call(tool, args.toMap(), author)
        return assertIs<AgentResult.Ok>(result, "$tool failed: $result").json
    }

    private fun health(harness: ToolsetHarness, netId: NetId): Float =
        with(harness.world) { assertNotNull(harness.netIds.resolveOrNull(netId))[Health].current }

    private companion object {
        val ID = Regex("\"id\":(-?\\d+)")
    }
}
