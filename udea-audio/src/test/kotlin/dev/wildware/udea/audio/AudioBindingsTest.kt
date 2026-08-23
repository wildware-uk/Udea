package dev.wildware.udea.audio

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.core.CueId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The routing table: how it is built, and what it refuses. */
class AudioBindingsTest {

    private fun sound(id: Int, name: String = "cue$id", vararg slots: Int) =
        CueSound(CueId(id), name, if (slots.isEmpty()) intArrayOf(0) else slots, 1F, 0F)

    @Test
    fun `a cue outside the table is unbound rather than an index failure`() {
        val bindings = AudioBindings.of(listOf(sound(3)))
        assertNotNull(bindings[CueId(3)])
        assertNull(bindings[CueId(4)], "above the table")
        assertNull(bindings[CueId(0)], "inside the table, nothing bound")
        assertEquals(1, bindings.size)
        assertEquals(3, bindings.highestCueId)
    }

    @Test
    fun `an empty binding list is a table that binds nothing`() {
        val bindings = AudioBindings.of(emptyList())
        assertEquals(0, bindings.size)
        assertNull(bindings[CueId(0)])
    }

    /** Last-writer-wins here would make the sound a cue makes depend on list order. */
    @Test
    fun `two sounds on one cue id are refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            AudioBindings.of(listOf(sound(2, "melee_hit"), sound(2, "arrow_hit")))
        }
        assertEquals(true, failure.message?.contains("melee_hit"))
        assertEquals(true, failure.message?.contains("arrow_hit"))
    }

    /** A `CueId` is a table index here, so a wild one is a message rather than a huge array. */
    @Test
    fun `a cue id above the ceiling is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            AudioBindings.of(listOf(sound(AudioBindings.MAX_CUE_ID + 1, "wild")))
        }
        assertEquals(true, failure.message?.contains("wild"))
    }

    @Test
    fun `a cue sound with no files is refused`() {
        assertFailsWith<IllegalArgumentException> {
            CueSound(CueId(1), "silent", intArrayOf(), 1F, 0F)
        }
    }

    /** The authored asset is what decides volume, variance and which files may play. */
    @Test
    fun `loading a SoundCue carries its authored mix across and loads every file`() {
        val device = RecordingDevice()
        val cue = SoundCue(
            id = AssetId("sounds/melee_hit"),
            sounds = listOf(ResPath("sounds/effects/melee_hit_1.ogg"), ResPath("sounds/effects/melee_hit_2.ogg")),
            pitchVariance = 0.5F,
            volume = 0.5F,
        )
        val loaded = CueSound.load(CueId(2), cue, device)
        assertEquals(listOf("sounds/effects/melee_hit_1.ogg", "sounds/effects/melee_hit_2.ogg"), device.loaded)
        assertEquals(2, loaded.size)
        assertEquals(0.5F, loaded.volume)
        assertEquals(0.5F, loaded.pitchVariance)
        assertEquals("sounds/melee_hit", loaded.name)
    }
}
