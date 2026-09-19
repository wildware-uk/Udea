package dev.wildware.udea.agent.assets

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.SpriteSheet
import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #195's three tools, `assets.fields`, `assets.set` and `assets.create`, over a warm daemon and
 * a running game: the editor's Save is these calls, so an agent saves exactly what a person does.
 *
 * Each assertion is on the file afterwards and on the registry the game reads, not only on the
 * answer: a save that answered `changed` and wrote nothing would pass a test of the answer.
 */
class AssetsSaveToolsTest {

    private fun harness(name: String): AssetToolHarness = AssetToolHarness(name).apply {
        write("character/orc.udea.kts", ORC)
        start()
    }

    private fun orcScale(harness: AssetToolHarness): Float =
        (harness.registry.at(harness.registry.indexOf(AssetId("character/orc_walk"))) as SpriteSheet).scale

    @Test
    fun `assets set replaces exactly one value, keeps every comment, and hot-reloads the game`() {
        val harness = harness("set")
        val file = harness.assetRoot.resolve("character/orc.udea.kts")
        val before = file.readText()
        harness.tick()

        val json = harness.ok("assets.set", "id" to "character/orc_walk", "field" to "scale", "value" to "0.08")

        assertEquals(before.replace("scale = 0.02f)", "scale = 0.08F)"), file.readText())
        assertTrue("\"applied\":true" in json, json)
        assertTrue("\"pushedToGame\":true" in json, json)
        harness.tick()
        assertEquals(0.08f, orcScale(harness))
    }

    @Test
    fun `assets set refuses a computed value with the reason and the line, and leaves the file alone`() {
        val harness = harness("read-only")
        val file = harness.assetRoot.resolve("character/orc.udea.kts")
        val before = file.readText()

        val error = harness.failure("assets.set", "id" to "character/orc_idle", "field" to "scale", "value" to "2")

        assertEquals("read_only_field", error.kind.id)
        assertTrue("set by `val orcScale` on line 3" in error.message, error.message)
        assertEquals(before, file.readText())
    }

    @Test
    fun `assets set restores the file byte for byte when the new value does not validate`() {
        val harness = harness("rollback")
        val file = harness.assetRoot.resolve("character/orc.udea.kts")
        val before = file.readText()

        val json = harness.ok("assets.set", "id" to "character/orc_idle_anim", "field" to "sheet", "value" to "character/orc_idel")

        assertTrue("\"rolledBack\":true" in json, json)
        assertEquals(before, file.readText())
    }

    @Test
    fun `assets fields says which values are plain literals and why the others are not`() {
        val json = harness("fields").ok("assets.fields", "id" to "character/orc_idle")

        assertTrue("\"file\":\"udea-agent/build/tmp/scratch/fields/assets/character/orc.udea.kts\"" in json, json)
        assertTrue("{\"name\":\"columns\",\"line\":9,\"editable\":true,\"text\":\"6\",\"type\":\"Int\",\"value\":\"6\"}" in json, json)
        assertTrue(
            "{\"name\":\"scale\",\"line\":10,\"editable\":false,\"reason\":\"set by `val orcScale` on line 3\",\"reasonLine\":3}" in json,
            json,
        )
    }

    @Test
    fun `assets fields pages an asset too wide for one answer, and every page reaches the caller inline`() {
        val harness = AssetToolHarness("fields-paged").apply {
            write("character/orc.udea.kts", ORC)
            write("character/wide.udea.kts", WIDE)
            start()
        }

        val pages = ArrayList<String>()
        var next: String? = "0"
        while (next != null) {
            val page = harness.ok("assets.fields", "id" to "character/wide_sheet", "from" to next)
            pages += page
            next = Regex("\"next\":(\\d+)").find(page)?.groupValues?.get(1)
        }

        assertTrue(pages.size > 1, "the wide asset fitted one answer, so this test pages nothing: $pages")
        for (page in pages) {
            assertTrue(
                page.length <= AgentBridge.MAX_DELIVERABLE_RESULT_CHARS,
                "a page is ${page.length} characters, and the bridge hands anything over " +
                    "${AgentBridge.MAX_DELIVERABLE_RESULT_CHARS} back as a file handle the editor cannot read",
            )
        }
        val names = pages.flatMap { page -> Regex("\"name\":\"(\\w+)\"").findAll(page).map { it.groupValues[1] }.toList() }
        assertEquals(listOf("name", "spritePath", "rows", "columns", "scale"), names)
    }

    @Test
    fun `assets create writes a generated asset that validates, and its values can then be set`() {
        val harness = harness("create")

        val json = harness.ok("assets.create", "from" to "character/orc_idle", "name" to "orc_idle_copy")

        val file = harness.assetRoot.resolve("character/orc_idle_copy.udea.kts")
        assertTrue(file.exists(), json)
        assertTrue("\"created\":true" in json, json)
        assertTrue("\"id\":\"character/orc_idle_copy\"" in json, json)
        // A new asset is a new shape, which the running game takes on its next launch.
        assertTrue("\"applied\":false" in json && "asset_added" in json, json)
        assertTrue("\"ok\":true" in harness.ok("assets.validate"), "the new asset does not validate:\n${file.readText()}")
        assertTrue("scale = 0.02F," in file.readText(), file.readText())

        harness.ok("assets.set", "id" to "character/orc_idle_copy", "field" to "columns", "value" to "4")
        assertTrue("columns = 4," in file.readText(), file.readText())
    }

    @Test
    fun `assets create refuses a taken name without writing anything`() {
        val harness = harness("create-taken")

        val error = harness.failure("assets.create", "from" to "character/orc_idle", "name" to "orc_walk")

        assertEquals("cannot_create", error.kind.id)
        assertTrue("character/orc_walk" in error.message, error.message)
        assertFalse(harness.assetRoot.resolve("character/orc_walk.udea.kts").exists())
    }

    private companion object {
        /** One sheet whose path is long enough that its fields do not fit one inline answer. */
        val WIDE = "spriteSheet(name = \"wide_sheet\", spritePath = \"/sprites/${"w".repeat(WIDE_PATH)}.png\", " +
            "rows = 1, columns = 1, scale = 1f)"

        const val WIDE_PATH = 380

        val ORC = """
            // The orc's sheets, with a comment a save must keep.

            val orcScale = 0.02f

            spriteSheet(
                name = "orc_idle",
                spritePath = "/sprites/orc/idle.png",
                rows = 1,
                columns = 6,
                scale = orcScale,
            )

            // The walk strip, on one line.
            spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = 0.02f)

            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
        """
    }
}
