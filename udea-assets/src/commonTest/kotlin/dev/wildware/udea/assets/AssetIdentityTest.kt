package dev.wildware.udea.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The direct regression for `common/.../assets.kt:81-87`, where `Asset.equals`/`hashCode` were
 * keyed on `path` alone.
 *
 * `character/orc_elite.udea.kts` declares thirteen assets and every one of them had the same
 * `path`, so all thirteen compared equal and any `Set` or `Map` keyed on them kept one.
 */
class AssetIdentityTest {

    /** The thirteen assets `example/.../character/orc_elite.udea.kts` declares, in one file. */
    private fun oneSourceFile(): List<AssetData> = listOf(
        Blueprint(AssetId("character/orc_elite")),
        SpriteAnimationSet(
            AssetId("character/orc_elite_animations"),
            listOf(reference("character/orc_elite_idle_anim")),
        ),
        SpriteAnimation(AssetId("character/orc_elite_idle_anim"), reference("character/orc_elite_idle")),
        SpriteAnimation(AssetId("character/orc_elite_walk_anim"), reference("character/orc_elite_walk")),
        SpriteAnimation(AssetId("character/orc_elite_attack_anim"), reference("character/orc_elite_attack")),
        SpriteAnimation(AssetId("character/orc_elite_hit_anim"), reference("character/orc_elite_hit")),
        SpriteAnimation(AssetId("character/orc_elite_death_anim"), reference("character/orc_elite_death")),
        sheet("character/orc_elite_idle", "/sprites/orc_elite/orc_elite_idle.png", columns = 6),
        sheet("character/orc_elite_walk", "/sprites/orc_elite/orc_elite_walk.png", columns = 8),
        sheet("character/orc_elite_attack", "/sprites/orc_elite/orc_elite_attack01.png", columns = 6),
        sheet("character/orc_elite_hit", "/sprites/orc_elite/orc_elite_hit.png", columns = 4),
        sheet("character/orc_elite_death", "/sprites/orc_elite/orc_elite_death.png", columns = 8),
        SoundCue(AssetId("character/orc_attack_sound_cue"), listOf(ResPath("sounds/orc_attack.wav"))),
    )

    private fun sheet(id: String, path: String, columns: Int) =
        SpriteSheet(AssetId(id), ResPath(path), columns = columns, rows = 1, scale = 0.03F)

    @Test
    fun `two assets declared in one source file are not equal`() {
        val assets = oneSourceFile()
        val idle = assets.single { it.id == AssetId("character/orc_elite_idle") }
        val walk = assets.single { it.id == AssetId("character/orc_elite_walk") }

        assertNotEquals(idle, walk)
        assertNotEquals(
            idle.hashCode(),
            walk.hashCode(),
            "two sprite sheets from one file collided in a hash bucket the way the old " +
                "path-keyed hashCode made every asset in a file collide",
        )
    }

    @Test
    fun `thirteen assets from one file are thirteen entries in a Set`() {
        assertEquals(13, oneSourceFile().toSet().size)
    }

    @Test
    fun `two assets with the same id and the same contents are equal`() {
        // The other half: identity is the value, so a decoded pack and a rebuilt one agree.
        assertEquals(
            sheet("character/orc_elite_idle", "sprites/a.png", columns = 6),
            sheet("character/orc_elite_idle", "sprites/a.png", columns = 6),
        )
    }

    @Test
    fun `an id keeps its folder and its name without string surgery`() {
        val id = AssetId("character/orc_elite")

        assertEquals("character", id.folder)
        assertEquals("orc_elite", id.name)
    }

    @Test
    fun `a single segment id has no folder`() {
        assertEquals("", AssetId("gameconfig").folder)
        assertEquals("gameconfig", AssetId("gameconfig").name)
    }

    @Test
    fun `an id may not be a path`() {
        // Every one of these produced a distinct key for one asset in the old string-keyed map.
        listOf("/character/orc", "character/orc/", "character//orc", "character\\orc", "orc elite", "")
            .forEach { bad ->
                assertFailsWith<IllegalArgumentException>("'$bad' was accepted as an AssetId") {
                    AssetId(bad)
                }
            }
    }

    @Test
    fun `the rejection message names the value that was rejected`() {
        val message = assertFailsWith<IllegalArgumentException> { AssetId("/character/orc") }.message
        assertTrue(message!!.contains("/character/orc"), "unhelpful message: $message")
    }
}
