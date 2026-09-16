package dev.wildware.udea.core.replication

import dev.wildware.udea.core.fixtures.ArrayBitWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaskOpsTest {

    @Test
    fun `EMPTY selects nothing and ALL selects everything`() {
        assertTrue(MaskOps.isEmpty(MaskOps.EMPTY))
        assertFalse(MaskOps.isNotEmpty(MaskOps.EMPTY))
        assertEquals(0, MaskOps.cardinality(MaskOps.EMPTY))

        assertEquals(MaskOps.MAX_FIELDS, MaskOps.cardinality(MaskOps.ALL))
        assertTrue(MaskOps.test(MaskOps.ALL, 0))
        assertTrue(MaskOps.test(MaskOps.ALL, MaskOps.MAX_FIELDS - 1))
    }

    @Test
    fun `set, clear and test address individual fields`() {
        var mask = MaskOps.EMPTY
        mask = MaskOps.set(mask, 3)
        mask = MaskOps.set(mask, 63)

        assertTrue(MaskOps.test(mask, 3))
        assertTrue(MaskOps.test(mask, 63))
        assertFalse(MaskOps.test(mask, 4))
        assertEquals(2, MaskOps.cardinality(mask))

        mask = MaskOps.clear(mask, 3)
        assertFalse(MaskOps.test(mask, 3))
        assertEquals(1, MaskOps.cardinality(mask))
    }

    @Test
    fun `setting a field twice is idempotent`() {
        val once = MaskOps.set(MaskOps.EMPTY, 7)
        assertEquals(once, MaskOps.set(once, 7))
    }

    @Test
    fun `of and lowest build masks from indices and from a count`() {
        assertEquals(MaskOps.of(0, 1, 2), MaskOps.lowest(3))
        assertEquals(MaskOps.EMPTY, MaskOps.of())
        assertEquals(MaskOps.EMPTY, MaskOps.lowest(0))
        assertEquals(MaskOps.ALL, MaskOps.lowest(MaskOps.MAX_FIELDS))
        assertEquals(64, MaskOps.cardinality(MaskOps.lowest(64)))
    }

    @Test
    fun `and, or and andNot combine masks`() {
        val a = MaskOps.of(0, 1, 2)
        val b = MaskOps.of(2, 3)

        assertEquals(MaskOps.of(2), MaskOps.and(a, b))
        assertEquals(MaskOps.of(0, 1, 2, 3), MaskOps.or(a, b))
        assertEquals(MaskOps.of(0, 1), MaskOps.andNot(a, b))
        assertEquals(MaskOps.of(3), MaskOps.andNot(b, a))
    }

    @Test
    fun `containsAll expresses the netMask subset rule`() {
        val all = MaskOps.of(0, 1, 2, 3)
        val net = MaskOps.of(0, 1, 2)

        assertTrue(MaskOps.containsAll(all, net))
        assertFalse(MaskOps.containsAll(net, all))
    }

    @Test
    fun `lowestSetBit and nextSetBit walk the mask`() {
        val mask = MaskOps.of(2, 5, 63)

        assertEquals(2, MaskOps.lowestSetBit(mask))
        assertEquals(-1, MaskOps.lowestSetBit(MaskOps.EMPTY))
        assertEquals(2, MaskOps.nextSetBit(mask, 0))
        assertEquals(5, MaskOps.nextSetBit(mask, 3))
        assertEquals(63, MaskOps.nextSetBit(mask, 6))
        assertEquals(-1, MaskOps.nextSetBit(mask, 64))
        assertEquals(-1, MaskOps.nextSetBit(MaskOps.EMPTY, 0))
    }

    @Test
    fun `forEachSetBit visits every field in ascending order`() {
        val visited = ArrayList<Int>()
        MaskOps.forEachSetBit(MaskOps.of(63, 0, 17)) { visited += it }

        assertEquals(listOf(0, 17, 63), visited)

        val none = ArrayList<Int>()
        MaskOps.forEachSetBit(MaskOps.EMPTY) { none += it }
        assertEquals(emptyList(), none)
    }

    @Test
    fun `an out of range field index is rejected`() {
        assertFailsWith<IllegalArgumentException> { MaskOps.single(-1) }
        assertFailsWith<IllegalArgumentException> { MaskOps.single(MaskOps.MAX_FIELDS) }
        assertFailsWith<IllegalArgumentException> { MaskOps.set(MaskOps.EMPTY, 64) }
        assertFailsWith<IllegalArgumentException> { MaskOps.test(MaskOps.EMPTY, 64) }
        assertFailsWith<IllegalArgumentException> { MaskOps.of(0, 64) }
        assertFailsWith<IllegalArgumentException> { MaskOps.lowest(65) }
    }

    @Test
    fun `word and fromWords round-trip, and generalise past one word`() {
        // The only sanctioned way to see the mask's storage. A wire encoder written against
        // wordCount and word keeps compiling when the mask widens to a LongArray.
        val mask = MaskOps.of(0, 31, 63)
        val words = LongArray(MaskOps.wordCount()) { MaskOps.word(mask, it) }

        assertEquals(mask, MaskOps.fromWords(words))
        assertFailsWith<IllegalArgumentException> { MaskOps.word(mask, MaskOps.wordCount()) }
        assertFailsWith<IllegalArgumentException> { MaskOps.fromWords(LongArray(0)) }
    }

    @Test
    fun `writeTo and readFrom round-trip at every field count`() {
        for (fieldCount in intArrayOf(1, 3, 7, 31, 32, 33, 63, 64)) {
            val mask = MaskOps.and(MaskOps.of(0, 2, 31, 32, 63), MaskOps.lowest(fieldCount))
            val writer = ArrayBitWriter()

            MaskOps.writeTo(mask, writer, fieldCount)
            assertEquals(
                fieldCount.toLong(),
                writer.bitPosition,
                "a $fieldCount-field component must spend exactly $fieldCount bits on its mask",
            )

            assertEquals(mask, MaskOps.readFrom(writer.toReader(), fieldCount))
        }
    }

    @Test
    fun `writing a zero-field mask emits nothing`() {
        val writer = ArrayBitWriter()
        MaskOps.writeTo(MaskOps.EMPTY, writer, 0)
        assertEquals(0L, writer.bitPosition)
    }

    @Test
    fun `an out of range field count is rejected by the wire encoding`() {
        val writer = ArrayBitWriter()
        assertFailsWith<IllegalArgumentException> { MaskOps.writeTo(MaskOps.EMPTY, writer, 65) }
        assertFailsWith<IllegalArgumentException> { MaskOps.readFrom(writer.toReader(), -1) }
    }
}
