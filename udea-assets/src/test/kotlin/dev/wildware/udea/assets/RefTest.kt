package dev.wildware.udea.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `AssetRefImpl` carried no type token (`common/.../assets.kt:36-43`) and resolved itself through a
 * global with a `by lazy`. Both are what these tests are about.
 */
class RefTest {

    private val orc = Blueprint(AssetId("character/orc_elite"))
    private val cue = SoundCue(AssetId("character/orc_attack_sound_cue"), listOf(ResPath("s/a.wav")))
    private val registry = registryOf(orc, cue)

    @Test
    fun `a reference records the type it expects`() {
        assertEquals(Blueprint::class, reference<Blueprint>("character/orc_elite").expected)
        assertEquals(SoundCue::class, reference<SoundCue>("character/orc_attack_sound_cue").expected)
    }

    @Test
    fun `a reference resolves to the asset it names`() {
        assertSame(orc, registry[reference<Blueprint>("character/orc_elite")])
    }

    @Test
    fun `a reference to the wrong kind fails at resolution, naming both kinds`() {
        val wrong = reference<Blueprint>("character/orc_attack_sound_cue")

        val failure = assertFailsWith<AssetTypeMismatchException> { registry[wrong] }

        assertEquals(AssetId("character/orc_attack_sound_cue"), failure.id)
        assertEquals("Blueprint", failure.expected)
        assertEquals("SoundCue", failure.actual)
    }

    @Test
    fun `a reference to nothing fails with a did-you-mean`() {
        // One deletion. `DidYouMean`'s length-scaled budget is three edits for a name this long,
        // and the budget is deliberately not re-decided here: spec 5 wants the runtime miss and
        // the build-time `unresolved-ref` diagnostic to suggest the same thing.
        val typo = reference<SoundCue>("character/orc_atack_sound_cue")

        val failure = assertFailsWith<UnknownAssetException> { registry[typo] }

        assertEquals(AssetId("character/orc_attack_sound_cue"), failure.suggestion)
        assertTrue(
            failure.message!!.contains("did you mean 'character/orc_attack_sound_cue'?"),
            "unhelpful message: ${failure.message}",
        )
    }

    @Test
    fun `a reference to something nothing resembles suggests nothing`() {
        val failure = assertFailsWith<UnknownAssetException> {
            registry[reference<Blueprint>("levels/dungeon_of_utter_doom")]
        }

        assertNull(failure.suggestion)
        assertTrue(failure.message!!.endsWith("assets"), "invented a suggestion: ${failure.message}")
    }

    @Test
    fun `resolution interns the slot the pack assigned`() {
        val ref = reference<Blueprint>("character/orc_elite")
        assertNull(ref.resolvedIndex, "a fresh reference is not bound to any graph")

        registry[ref]

        assertEquals(registry.indexOf(orc.id), ref.resolvedIndex)
    }

    @Test
    fun `a failed resolution interns nothing`() {
        val wrong = reference<Blueprint>("character/orc_attack_sound_cue")

        assertFailsWith<AssetTypeMismatchException> { registry[wrong] }

        assertNull(wrong.resolvedIndex, "a reference that failed its type check cached a slot")
    }

    @Test
    fun `equality is the id and the expected type, not the cached slot`() {
        val fresh = reference<Blueprint>("character/orc_elite")
        val resolved = reference<Blueprint>("character/orc_elite")
        registry[resolved]

        assertEquals(fresh, resolved)
        assertEquals(fresh.hashCode(), resolved.hashCode())
        assertEquals(1, setOf(fresh, resolved).size)
    }

    @Test
    fun `two references to one id expecting different kinds are different references`() {
        assertNotEquals<Ref<*>>(
            reference<Blueprint>("character/orc_elite"),
            reference<SoundCue>("character/orc_elite"),
        )
    }
}

/** A registry over [assets], with a content hash nothing in these tests reads. */
internal fun registryOf(vararg assets: AssetData, log: AssetGraphLog = AssetGraphLog()): AssetRegistry =
    AssetRegistry(arrayOf(*assets), byteArrayOf(1, 2, 3), log)
