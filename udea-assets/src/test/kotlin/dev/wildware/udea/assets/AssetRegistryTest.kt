package dev.wildware.udea.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The registry as an injectable instance, and the lookups that replace `Assets.find`. */
class AssetRegistryTest {

    private val fireball = Ability(AssetId("ability/fireball"), uClass("moba.FireballExec"))
    private val orc = Blueprint(AssetId("character/orc_elite"))

    @Test
    fun `a duplicate id is refused when the graph is built, not when a lookup hits it`() {
        val failure = assertFailsWith<DuplicateAssetIdException> {
            registryOf(orc, Blueprint(AssetId("character/orc_elite"), tags = listOf(EntityTagName("boss"))))
        }

        assertEquals(AssetId("character/orc_elite"), failure.id)
        assertEquals(0, failure.firstIndex)
        assertEquals(1, failure.secondIndex)
    }

    @Test
    fun `find misses without throwing and contains answers the same question`() {
        val registry = registryOf(orc, fireball)

        assertSame(orc, registry.find(orc.id))
        assertNull(registry.find(AssetId("character/nobody")))
        assertTrue(AssetId("ability/fireball") in registry)
        assertFalse(AssetId("ability/frostbolt") in registry)
    }

    @Test
    fun `an index round-trips to the asset it names`() {
        val registry = registryOf(orc, fireball)

        assertSame(fireball, registry.at(registry.indexOf(fireball.id)))
    }

    @Test
    fun `an index outside the graph is refused rather than read`() {
        val registry = registryOf(orc)

        assertFailsWith<IllegalArgumentException> { registry.at(AssetIndex(7)) }
    }

    @Test
    fun `ids are the pack order, so two runs report the same order`() {
        assertEquals(listOf(orc.id, fireball.id), registryOf(orc, fireball).ids)
    }

    @Test
    fun `the array a registry was built from is not a way into it`() {
        val data: Array<AssetData> = arrayOf(orc)
        val registry = AssetRegistry(data, byteArrayOf())

        data[0] = Blueprint(AssetId("character/impostor"))

        assertSame(orc, registry.find(orc.id), "the caller's array was still the registry's storage")
    }

    @Test
    fun `the content hash cannot be rewritten through the accessor`() {
        val registry = AssetRegistry(arrayOf(orc), byteArrayOf(7, 7))

        registry.contentHash[0] = 0

        assertEquals(listOf<Byte>(7, 7), registry.contentHash.toList())
    }
}

/**
 * Two live registries in one JVM, which `object Assets` made impossible: it was one global map,
 * `clear()` existed because tests had to fight over it, and two scenarios could not coexist.
 */
class TwoRegistriesTest {

    private val id = AssetId("ability/fireball")
    private val tuned = Ability(id, uClass("moba.FireballExec"), params = mapOf("damage" to AssetValue.FloatValue(40F)))
    private val shipped = Ability(id, uClass("moba.FireballExec"), params = mapOf("damage" to AssetValue.FloatValue(30F)))

    @Test
    fun `two registries answer for their own graph`() {
        val a = registryOf(shipped)
        val b = registryOf(tuned)

        assertSame(shipped, a.find(id))
        assertSame(tuned, b.find(id))
    }

    @Test
    fun `one reference read through both registries gets each one's data`() {
        val a = registryOf(shipped)
        val b = registryOf(tuned)
        val ref = reference<Ability>("ability/fireball")

        // Interleaved on purpose: a cached slot from one graph must never answer for the other.
        assertSame(shipped, a[ref])
        assertSame(tuned, b[ref])
        assertSame(shipped, a[ref])
    }

    @Test
    fun `a slot cached against one graph is not read out of a differently shaped one`() {
        val wide = registryOf(Blueprint(AssetId("character/a")), Blueprint(AssetId("character/b")), shipped)
        val narrow = registryOf(shipped)
        val ref = reference<Ability>("ability/fireball")

        assertEquals(AssetIndex(2), wide.indexOf(id))
        assertSame(shipped, wide[ref])
        // Slot 2 does not exist in `narrow`; a registry that trusted the cached index would throw
        // an index-out-of-bounds here, and one that trusted a content hash could read slot 2 of a
        // graph that had one and hand back the wrong asset.
        assertSame(shipped, narrow[ref])
        assertEquals(AssetIndex(0), narrow.indexOf(id))
    }

    @Test
    fun `a graph with no reload never reports a change`() {
        val registry = registryOf(shipped)

        assertEquals(0, registry.current())
        assertEquals(AssetChangeSet.None, registry.changesSince(0))
    }
}
