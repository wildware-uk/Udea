package dev.wildware.udea.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden pin for the [NetId] bit layout.
 *
 * The packing is a wire contract: `udea-net` writes it into packets and the snapshot ring
 * stores it as a field. Changing it must be a deliberate golden update with those two
 * consumers in mind, so the expected words are written out literally rather than computed
 * from the same constants the implementation uses.
 */
class NetIdTest {

    @Test
    fun `the bit layout is index in bits 0 to 15 and generation in bits 16 to 23`() {
        assertEquals(0x00000000, NetId.of(0, 0).raw)
        assertEquals(0x00000001, NetId.of(1, 0).raw)
        assertEquals(0x0000FFFF, NetId.of(65535, 0).raw)
        assertEquals(0x00010000, NetId.of(0, 1).raw)
        assertEquals(0x00561234, NetId.of(0x1234, 0x56).raw)
        assertEquals(0x00FFFFFF, NetId.of(65535, 255).raw)
        assertEquals(-1, NetId.NONE.raw)
    }

    @Test
    fun `the layout constants match the layout`() {
        assertEquals(16, NetId.INDEX_BITS)
        assertEquals(8, NetId.GENERATION_BITS)
        assertEquals(65_536, NetId.MAX_INDICES)
        assertEquals(256, NetId.GENERATION_MODULUS)
    }

    @Test
    fun `index and generation unpack what of packed`() {
        val id = NetId.of(0x1234, 0x56)
        assertEquals(0x1234, id.index)
        assertEquals(0x56, id.generation)

        val extreme = NetId.of(65535, 255)
        assertEquals(65535, extreme.index)
        assertEquals(255, extreme.generation)
    }

    @Test
    fun `ofRaw round-trips every packed word`() {
        for (index in intArrayOf(0, 1, 255, 256, 30_000, 65_535)) {
            for (generation in intArrayOf(0, 1, 127, 255)) {
                val id = NetId.of(index, generation)
                assertEquals(id, NetId.ofRaw(id.raw))
            }
        }
        assertEquals(NetId.NONE, NetId.ofRaw(NetId.NONE.raw))
    }

    @Test
    fun `a word with reserved bits set is rejected`() {
        // A future layout may claim the top byte. Accepting it today would mean a corrupt
        // packet silently produced an id that means something else tomorrow.
        assertFailsWith<IllegalArgumentException> { NetId.ofRaw(0x01000000) }
        assertFailsWith<IllegalArgumentException> { NetId.ofRaw(0x7F123456) }
    }

    @Test
    fun `out of range index or generation is rejected`() {
        assertFailsWith<IllegalArgumentException> { NetId.of(-1, 0) }
        assertFailsWith<IllegalArgumentException> { NetId.of(NetId.MAX_INDICES, 0) }
        assertFailsWith<IllegalArgumentException> { NetId.of(0, -1) }
        assertFailsWith<IllegalArgumentException> { NetId.of(0, NetId.GENERATION_MODULUS) }
    }

    @Test
    fun `NONE names no entity`() {
        assertTrue(NetId.NONE.isNone)
        assertFalse(NetId.of(0, 0).isNone)
        assertFalse(NetId.of(65535, 255).isNone)
    }

    @Test
    fun `ordering is by index, tie-broken by generation`() {
        val ids = listOf(
            NetId.of(9, 0),
            NetId.of(2, 3),
            NetId.of(2, 1),
            NetId.of(0, 255),
        )

        assertEquals(
            listOf(NetId.of(0, 255), NetId.of(2, 1), NetId.of(2, 3), NetId.of(9, 0)),
            ids.sorted(),
        )
    }

    @Test
    fun `the same index with different generations is a different id`() {
        assertFalse(NetId.of(5, 0) == NetId.of(5, 1))
        assertEquals(NetId.of(5, 1), NetId.of(5, 1))
    }

    @Test
    fun `toString names the index and generation`() {
        assertEquals("NetId(#5@2)", NetId.of(5, 2).toString())
        assertEquals("NetId.NONE", NetId.NONE.toString())
    }
}
