package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Health
import dev.wildware.udea.agent.Transform
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Edit sessions, multi-entity `set_field`, selection and `common_fields` (issue #232), driven only
 * through `SimHarness` the way an agent or the editor window calls them.
 *
 * Every call crosses the bridge and runs inside a `SimBarrier` drain, so what is asserted here is
 * what an HTTP caller gets. The idle timeout reads [ManualClock], which only a test moves, so a
 * session expires on the pump after the clock passes 30 seconds and at no other time. A running
 * host pumps every frame; a test pumps with `step(0)` where a frame would have gone by.
 */
class EditSessionTest {

    private val clock = ManualClock()

    private val harness = ToolsetHarness(withEditor = true, editorClock = clock)

    @Test
    fun `begin then several updates then commit is one undo entry, and undo restores the starting values`() {
        val a = harness.place(x = 1f, y = 2f, health = 100f)
        val b = harness.place(x = 5f, y = 6f, health = 80f)
        val start = harness.fieldHash()
        val designer = harness.author("designer")

        val session = begin(designer, listOf(a, b), "Transform.position.x", "Transform.position.y", "Health.current")
        for (step in 1..5) {
            edit(designer, "editor.update_edit", "sessionId" to "$session", "values" to "${a.raw}:Transform.position.x=${10 + step},${b.raw}:Transform.position.x=${20 + step},Health.current=$step")
        }
        assertEquals(0, historySize(designer), "an update put an entry in the history; only a commit may")
        assertEquals(15f, position(a).first)
        assertEquals(25f, position(b).first)

        edit(designer, "editor.commit_edit", "sessionId" to "$session")

        assertEquals(1, historySize(designer), "a committed session is exactly one undo entry")
        edit(designer, "editor.undo")
        assertEquals(start, harness.fieldHash(), "undoing the session did not restore the starting values")
        assertEquals(0, historySize(designer))
    }

    @Test
    fun `cancel restores every field exactly and leaves no undo entry`() {
        val a = harness.place(x = 1.25f, y = -2.5f, health = 33.3f)
        val start = harness.fieldHash()
        val designer = harness.author("designer")
        val session = begin(designer, listOf(a), "Transform.position.x", "Transform.position.y", "Health.current")
        edit(designer, "editor.update_edit", "sessionId" to "$session", "values" to "Transform.position.x=99,Transform.position.y=98,Health.current=1")
        // Another author writes a session field mid-session: the last write wins until the cancel.
        edit(harness.author("bob"), "editor.set_field", "id" to "${a.raw}", "component" to "Health", "field" to "current", "value" to "7")
        assertEquals(7f, health(a))

        edit(designer, "editor.cancel_edit", "sessionId" to "$session")

        assertEquals(start, harness.fieldHash(), "cancel left a field away from its starting value")
        assertEquals(0, historySize(designer), "cancel put an entry in the history")
        val gone = assertIs<AgentResult.Failed>(harness.sim.call("editor.update_edit", mapOf("sessionId" to "$session", "values" to "Health.current=2"), designer))
        assertEquals(EditorToolset.NO_SUCH_EDIT, gone.error.kind, "a cancelled session still accepts updates")
    }

    @Test
    fun `a session idle for thirty seconds is cancelled and restored, and one updated in time is not`() {
        val a = harness.place(x = 1f, y = 2f)
        val start = harness.fieldHash()
        val designer = harness.author("designer")
        val session = begin(designer, listOf(a), "Transform.position.x")
        edit(designer, "editor.update_edit", "sessionId" to "$session", "values" to "Transform.position.x=50")
        // A host pumps every frame; the sweep stamps the update on the next pump, here.
        harness.sim.step(0)

        clock.advanceSeconds(29)
        harness.sim.step(0)
        assertEquals(50f, position(a).first, "the session was cancelled before its 30 seconds were up")
        // An update restarts the idle count.
        edit(designer, "editor.update_edit", "sessionId" to "$session", "values" to "Transform.position.x=60")
        harness.sim.step(0)
        clock.advanceSeconds(29)
        harness.sim.step(0)
        assertEquals(60f, position(a).first, "an update did not restart the idle count")

        clock.advanceSeconds(1)
        harness.sim.step(0)

        assertEquals(start, harness.fieldHash(), "an idle session was not cancelled and restored")
        assertEquals(0, historySize(designer), "an expired session left an undo entry")
        assertTrue(harness.sim.events().any { it.contains("editor_expired") }, harness.sim.events().toString())
    }

    @Test
    fun `an author who leaves has their open session cancelled and restored`() {
        val a = harness.place(health = 100f)
        val start = harness.fieldHash()
        val designer = harness.author("designer")
        val session = begin(designer, listOf(a), "Health.max")
        edit(designer, "editor.update_edit", "sessionId" to "$session", "values" to "Health.max=500")

        edit(designer, "editor.leave")

        assertEquals(start, harness.fieldHash())
        assertEquals(0, historySize(designer))
    }

    @Test
    fun `beginning a second session commits the first`() {
        val a = harness.place(health = 100f)
        val designer = harness.author("designer")
        val first = begin(designer, listOf(a), "Health.current")
        edit(designer, "editor.update_edit", "sessionId" to "$first", "values" to "Health.current=40")

        begin(designer, listOf(a), "Health.max")

        assertEquals(1, historySize(designer), "the first session was not committed when the second began")
        assertEquals(40f, health(a))
        val closed = assertIs<AgentResult.Failed>(harness.sim.call("editor.commit_edit", mapOf("sessionId" to "$first"), designer))
        assertEquals(EditorToolset.NO_SUCH_EDIT, closed.error.kind)
    }

    @Test
    fun `another author cannot update or close a session that is not theirs`() {
        val a = harness.place(health = 100f)
        val alice = harness.author("alice")
        val session = begin(alice, listOf(a), "Health.current")

        val refused = assertIs<AgentResult.Failed>(harness.sim.call("editor.update_edit", mapOf("sessionId" to "$session", "values" to "Health.current=1"), harness.author("bob")))

        assertEquals(EditorToolset.NOT_YOUR_EDIT, refused.error.kind)
        assertTrue(refused.error.message.contains("alice"), refused.error.message)
        assertEquals(100f, health(a))
    }

    @Test
    fun `an update to a field the session did not open is refused and writes nothing`() {
        val a = harness.place(x = 3f, health = 100f)
        val designer = harness.author("designer")
        val session = begin(designer, listOf(a), "Health.current")

        val refused = assertIs<AgentResult.Failed>(harness.sim.call("editor.update_edit", mapOf("sessionId" to "$session", "values" to "Health.current=5,Transform.position.x=9"), designer))

        assertEquals(EditorToolset.NOT_IN_EDIT, refused.error.kind)
        assertEquals(100f, health(a), "a refused update wrote its other values")
        assertEquals(3f, position(a).first)
    }

    @Test
    fun `a committed session whose field another author changed since is refused on undo, naming them`() {
        val a = harness.place(health = 100f)
        val alice = harness.author("alice")
        val session = begin(alice, listOf(a), "Health.current")
        edit(alice, "editor.update_edit", "sessionId" to "$session", "values" to "Health.current=10")
        edit(alice, "editor.commit_edit", "sessionId" to "$session")
        edit(harness.author("bob"), "editor.set_field", "id" to "${a.raw}", "component" to "Health", "field" to "current", "value" to "20")

        val refused = assertIs<AgentResult.Failed>(harness.sim.call("editor.undo", emptyMap(), alice))

        assertEquals(EditorToolset.EDIT_CONFLICT, refused.error.kind)
        assertTrue(refused.error.message.contains("bob"), refused.error.message)
        edit(alice, "editor.undo", "overwrite" to "true")
        assertEquals(100f, health(a), "the undo entry did not record the session's starting value")
    }

    @Test
    fun `a set_field on several entities is one write and one undo entry`() {
        val a = harness.place(health = 10f)
        val b = harness.place(health = 20f)
        val c = harness.place(health = 30f)
        val designer = harness.author("designer")

        edit(designer, "editor.set_field", "id" to "${a.raw},${b.raw},${c.raw}", "component" to "Health", "field" to "current", "value" to "5")

        assertEquals(listOf(5f, 5f, 5f), listOf(health(a), health(b), health(c)))
        assertEquals(1, historySize(designer), "a multi-entity set_field was not exactly one undo entry")
        edit(designer, "editor.undo")
        assertEquals(listOf(10f, 20f, 30f), listOf(health(a), health(b), health(c)))
    }

    @Test
    fun `a malformed id in a list is refused by name, and nothing is written`() {
        val a = harness.place(health = 10f)

        val refused = assertIs<AgentResult.Failed>(harness.sim.call("editor.set_field", mapOf("id" to "${a.raw},4o", "component" to "Health", "field" to "current", "value" to "5"), harness.author("designer")))

        assertEquals(AgentErrorKind.BAD_ARGUMENT, refused.error.kind)
        assertTrue(refused.error.message.contains("id=4o"), refused.error.message)
        assertEquals(10f, health(a), "a refused multi-entity set_field wrote the entity before the bad id")
    }

    @Test
    fun `a set_field on one id answers exactly as it did before several ids were allowed`() {
        val a = harness.place(health = 10f)

        val answer = edit(harness.author("designer"), "editor.set_field", "id" to "${a.raw}", "component" to "Health", "field" to "current", "value" to "5")

        assertEquals(
            "{\"id\":${a.raw},\"component\":\"Health\",\"field\":\"current\",\"value\":5," +
                "\"reverse\":{\"tool\":\"editor.set_field\",\"args\":{\"id\":\"${a.raw}\",\"component\":\"Health\",\"field\":\"current\",\"value\":\"10.0\"}}}",
            answer,
        )
    }

    @Test
    fun `common_fields reports a shared value and Mixed where the values differ`() {
        val a = harness.place(x = 4f, y = 1f, health = 50f, team = 2)
        val b = harness.place(x = 4f, y = 9f, health = 50f, team = 3)

        val common = edit(harness.author("designer"), "editor.common_fields", "entities" to "${a.raw},${b.raw}")

        assertTrue(common.contains("{\"component\":\"Transform\",\"field\":\"position.x\",\"mixed\":false,\"value\":4}"), common)
        assertTrue(common.contains("{\"component\":\"Transform\",\"field\":\"position.y\",\"mixed\":true}"), common)
        assertTrue(common.contains("{\"component\":\"Health\",\"field\":\"current\",\"mixed\":false,\"value\":50}"), common)
        assertTrue(common.contains("{\"component\":\"Team\",\"field\":\"team\",\"mixed\":true}"), common)
    }

    @Test
    fun `common_fields leaves out a component one of the entities does not carry`() {
        val placed = harness.place(health = 50f)
        val spawned = edit(harness.author("designer"), "editor.spawn", "blueprint" to "grunt")
        val grunt = ID.find(spawned)!!.groupValues[1]

        val common = edit(harness.author("designer"), "editor.common_fields", "entities" to "${placed.raw},$grunt")

        assertFalse(common.contains("\"Champion\""), "Champion is on one entity only: $common")
        assertTrue(common.contains("\"Health\""), common)
    }

    @Test
    fun `two authors' selections are independent and each reads the other's`() {
        val a = harness.place()
        val b = harness.place()
        val c = harness.place()
        val alice = harness.author("alice")
        val bob = harness.author("bob")

        edit(alice, "editor.select", "entities" to "${a.raw},${b.raw}")
        edit(bob, "editor.select", "entities" to "${c.raw}")
        edit(alice, "editor.select", "entities" to "${c.raw}", "mode" to "add")
        edit(alice, "editor.select", "entities" to "${a.raw}", "mode" to "remove")

        val seenByBob = edit(bob, "editor.selection")
        val seenByAlice = edit(alice, "editor.selection")
        assertTrue(seenByBob.contains("{\"author\":\"alice\",\"ids\":[${b.raw},${c.raw}]}"), seenByBob)
        assertTrue(seenByBob.contains("{\"author\":\"bob\",\"ids\":[${c.raw}]}"), seenByBob)
        assertTrue(seenByAlice.contains("{\"author\":\"bob\",\"ids\":[${c.raw}]}"), seenByAlice)
        assertTrue(seenByAlice.contains("\"you\":\"alice\""), seenByAlice)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun begin(author: AgentSessionId, entities: List<NetId>, vararg fields: String): Int {
        val opened = edit(author, "editor.begin_edit", "entities" to entities.joinToString(",") { "${it.raw}" }, "fields" to fields.joinToString(","))
        return assertNotNull(SESSION.find(opened), opened).groupValues[1].toInt()
    }

    private fun edit(author: AgentSessionId, tool: String, vararg args: Pair<String, String>): String {
        val result = harness.sim.call(tool, args.toMap(), author)
        return assertIs<AgentResult.Ok>(result, "$tool failed: $result").json
    }

    private fun historySize(author: AgentSessionId): Int {
        val history = edit(author, "editor.history")
        return assertNotNull(SIZE.find(history), history).groupValues[1].toInt()
    }

    private fun health(netId: NetId): Float =
        with(harness.world) { assertNotNull(harness.netIds.resolveOrNull(netId))[Health].current }

    private fun position(netId: NetId): Pair<Float, Float> = with(harness.world) {
        val position = assertNotNull(harness.netIds.resolveOrNull(netId))[Transform].position
        position.x to position.y
    }

    /** A clock only the test moves. */
    private class ManualClock : AgentClock {
        private var nanos = 0L

        fun advanceSeconds(seconds: Long) {
            nanos += seconds * 1_000_000_000L
        }

        override fun nowNanos(): Long = nanos
    }

    private companion object {
        val SESSION = Regex("\"sessionId\":(\\d+)")
        val SIZE = Regex("\"size\":(\\d+)")
        val ID = Regex("\"id\":(-?\\d+)")
    }
}
