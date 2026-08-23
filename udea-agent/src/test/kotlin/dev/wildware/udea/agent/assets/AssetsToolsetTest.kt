package dev.wildware.udea.agent.assets

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #92: the `assets` toolset over a warm daemon and a running game.
 *
 * Every assertion is against the rendered result document and against the state of the world after
 * the call - the file on disk, the registry the game reads. A test that only asserted a tool
 * returned `ok` would pass for a tool that did nothing.
 */
class AssetsToolsetTest {

    private fun harness(name: String): AssetToolHarness = AssetToolHarness(name).apply {
        write(
            "character/orc.udea.kts",
            """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            """,
        )
        write("blueprint/player.udea.kts", """blueprint(name = "player", components = listOf("dev.wildware.moba.Health"))""")
        start()
    }

    @Test
    fun `every tool is listed and callable through the real index`() {
        val harness = harness("surface")
        assertEquals(
            listOf(
                "assets.changed_since",
                "assets.get",
                "assets.graph",
                "assets.list",
                "assets.patch",
                "assets.resolve_reference",
                "assets.search",
                "assets.validate",
                "assets.write",
            ),
            harness.toolNames(),
        )
        // Callable, not merely listed: a tool whose toolset was never registered is refused by
        // ToolIndex.build, but one whose arguments do not coerce fails only when it is called.
        assertTrue("\"total\"" in harness.ok("assets.list"))
        assertTrue("orc_idle" in harness.ok("assets.get", "id" to "character/orc_idle"))
        assertTrue("orc" in harness.ok("assets.search", "query" to "orc"))
        assertTrue("\"edges\"" in harness.ok("assets.graph", "rootId" to "character/orc_idle_anim"))
        assertTrue("\"exists\":true" in harness.ok("assets.resolve_reference", "id" to "character/orc_idle"))
        assertTrue("\"ok\":true" in harness.ok("assets.validate"))
        assertTrue("\"appliedReloads\":0" in harness.ok("assets.changed_since", "tick" to "0"))
    }

    @Test
    fun `resolve_reference names the rule and suggests the closest id on a miss`() {
        val harness = harness("resolve")
        val json = harness.ok("assets.resolve_reference", "id" to "character/orc_idel", "expectedKind" to "spriteSheet")
        assertTrue("\"exists\":false" in json, json)
        assertTrue("\"matchesExpected\":false" in json, json)
        assertTrue("\"character/orc_idle\"" in json, "an agent must self-correct in one turn: $json")
        assertTrue(UdeaRules.UNRESOLVED_REFERENCE.id in json, "the tool and the build log name one rule: $json")
    }

    @Test
    fun `resolve_reference reports a kind mismatch for an id that does exist`() {
        val harness = harness("resolve-kind")
        val json = harness.ok("assets.resolve_reference", "id" to "character/orc_hit", "expectedKind" to "spriteSheet")
        assertTrue("\"exists\":true" in json, json)
        assertTrue("\"kind\":\"soundCue\"" in json, json)
        assertTrue("\"matchesExpected\":false" in json, json)
    }

    @Test
    fun `a write with a broken reference leaves the file byte-identical and says what to type`() {
        val harness = harness("broken-write")
        val file = harness.assetRoot.resolve("character/orc.udea.kts")
        val before = file.readText()

        val json = harness.ok(
            "assets.write",
            "path" to "character/orc.udea.kts",
            "content" to """
                spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.02f)
                spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idel"))
                soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            """.trimIndent(),
        )

        assertTrue("\"rolledBack\":true" in json, json)
        assertEquals(before, file.readText(), "a rejected write must leave the file byte-identical")
        assertTrue(UdeaRules.UNRESOLVED_REFERENCE.id in json, json)
        // The suggestion is inside a JSON string, so its quotes are escaped in the document; the
        // claim is that both halves are there, not that they survive a naive substring with quotes.
        assertTrue("did you mean" in json.lowercase(), json)
        assertTrue("character/orc_idle" in json, json)
    }

    @Test
    fun `a valid patch hot-reloads the running game and names the changed ids`() {
        val harness = harness("patch")
        // One tick first, so the reload lands at a tick strictly after the one asked about below.
        // `changed_since(t)` is exclusive of `t` on purpose - "what changed since the snapshot I
        // restored" must not include the snapshot's own tick - and a delta applied at tick 0 is
        // genuinely not "since tick 0".
        harness.tick()
        assertEquals(
            0.02f,
            (harness.registry.at(harness.registry.indexOf(AssetId("character/orc_idle"))) as SpriteSheet).scale,
        )

        val json = harness.ok(
            "assets.patch",
            "path" to "character/orc.udea.kts",
            "find" to "scale = 0.02f",
            "replace" to "scale = 0.08f",
        )
        assertTrue("\"applied\":true" in json, json)
        assertTrue("\"character/orc_idle\"" in json, json)
        assertTrue("\"pushedToGame\":true" in json, json)

        // Still the old value: the delta is on the barrier and the game has not ticked.
        assertEquals(
            0.02f,
            (harness.registry.at(harness.registry.indexOf(AssetId("character/orc_idle"))) as SpriteSheet).scale,
            "a delta must not reach the registry before a tick boundary",
        )
        harness.tick()
        assertEquals(
            0.08f,
            (harness.registry.at(harness.registry.indexOf(AssetId("character/orc_idle"))) as SpriteSheet).scale,
        )
        assertEquals(1, harness.hotReload.applied)
        assertTrue("character/orc_idle" in harness.ok("assets.changed_since", "tick" to "0"))
    }

    @Test
    fun `a patch whose find text is ambiguous is refused rather than applied to the first match`() {
        val harness = harness("ambiguous")
        val file = harness.assetRoot.resolve("character/orc.udea.kts")
        val before = file.readText()

        val error = harness.failure(
            "assets.patch",
            "path" to "character/orc.udea.kts",
            "find" to "name = ",
            "replace" to "name= ",
        )
        assertEquals("bad_argument", error.kind.id)
        assertTrue("3 times" in error.message, error.message)
        assertEquals(before, file.readText())
    }

    @Test
    fun `one unresolved id with five referrers is exactly one diagnostic`() {
        val harness = harness("root-cause")
        val json = harness.ok(
            "assets.write",
            "path" to "character/orc.udea.kts",
            "content" to """
                spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.02f)
                spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
                soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
                repeat(5) { i -> spriteAnimation(name = "broken_${'$'}i", sheet = reference("character/orc_idel")) }
            """.trimIndent(),
        )
        assertEquals(
            1,
            json.split(UdeaRules.UNRESOLVED_REFERENCE.id).size - 1,
            "five referrers of one typo is one diagnostic: $json",
        )
        assertTrue("\"errors\":1" in json, json)
    }

    @Test
    fun `a tool that throws returns ok false without stalling the loop`() {
        val harness = harness("throwing")
        // `assets.get` on a missing id is a refusal, not an exception; a genuinely throwing path is
        // a patch against a file the daemon is not watching, which reaches the filesystem.
        val error = harness.failure("assets.get", "id" to "character/nope")
        assertEquals("no_such_asset", error.kind.id)
        assertTrue("character/nope" in error.message, error.message)

        val missing = harness.failure("assets.patch", "path" to "nowhere.udea.kts", "find" to "a", "replace" to "b")
        assertEquals("bad_argument", missing.kind.id)

        // The loop is unaffected: the game still ticks and the next tool call still answers.
        harness.tick()
        assertTrue("\"total\"" in harness.ok("assets.list"))
    }

    @Test
    fun `a write that adds an asset reports a restart rather than half-applying`() {
        val harness = harness("shape")
        val json = harness.ok(
            "assets.write",
            "path" to "character/orc.udea.kts",
            "content" to """
                spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.5f)
                spriteSheet(name = "orc_die", spritePath = "/sprites/orc/die.png", rows = 1, columns = 4, scale = 0.5f)
                spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
                soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            """.trimIndent(),
        )
        assertTrue("\"applied\":false" in json, json)
        assertTrue("reload_requires_restart" in json, json)
        assertTrue("asset_added" in json, json)

        harness.tick()
        assertEquals(
            0.02f,
            (harness.registry.at(harness.registry.indexOf(AssetId("character/orc_idle"))) as SpriteSheet).scale,
            "the value half of a shape-changing edit must not reach the running game either",
        )
    }
}
