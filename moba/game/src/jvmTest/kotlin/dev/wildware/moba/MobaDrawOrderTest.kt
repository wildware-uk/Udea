package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The order the world pass draws in, as arithmetic.
 *
 * ## Why this is worth a test at all
 *
 * "The scene looks right" is a screenshot, and a screenshot cannot tell a correct sort from a
 * sort that happened to agree with spawn order on the day it was captured - which is exactly the
 * state `moba` was in before [WorldDrawOrder] existed. The properties below are the ones the
 * picture depends on, and each of them fails independently: reverse the y comparison and
 * `nearer units draw last` goes red; drop the layer from the key and `a corpse is under every
 * body` goes red; drop the slot from the key and `two units at one y keep their order` goes red.
 *
 * Every one of them runs with no GL context and no world: the sort is deliberately a pure
 * function of `(layer, y, insertion order)`, which is what lets it be checked at all.
 */
class MobaDrawOrderTest {

    private fun entity(id: Int): Entity = Entity(id, version = 0u)

    /** The whole point: a unit further up the field is drawn first, so nearer bodies win. */
    @Test
    fun `nearer units draw last`() {
        val order = WorldDrawOrder()
        order.begin()
        order.add(entity(1), DrawLayer.UNIT, y = -40f)
        order.add(entity(2), DrawLayer.UNIT, y = 120f)
        order.add(entity(3), DrawLayer.UNIT, y = 0f)
        order.sort()
        assertEquals(
            listOf(2, 3, 1),
            List(order.size) { order.entityAt(it).id },
            "back to front is descending y: 120, 0, -40",
        )
    }

    /**
     * Negative coordinates are the normal case in this game, not an edge one.
     *
     * `level/test_level` centres on `(25, -25)` and every clearing is at a negative y, so a sort
     * that only ordered positive floats correctly would order the entire shipped level wrongly.
     * That is what a naive `floatToIntBits` comparison does: the sign bit makes every negative
     * float compare *greater* than every positive one.
     */
    @Test
    fun `negative and positive world y share one order`() {
        val ys = listOf(-120f, -0.5f, 0f, -0f, 0.5f, 120f, -75f, 3f)
        val order = WorldDrawOrder()
        order.begin()
        ys.forEachIndexed { at, y -> order.add(entity(at), DrawLayer.UNIT, y) }
        order.sort()
        val drawn = List(order.size) { ys[order.entityAt(it).id] }
        assertEquals(
            ys.sortedDescending(),
            drawn,
            "the key's float order must be the numeric order, negatives included",
        )
    }

    /**
     * A corpse is under every living body, however far down the field it fell.
     *
     * The measured symptom this pins: `DeathSystem` leaves bodies on the field, and a corpse at a
     * low y - which is to say, one that fell towards the viewer - was drawn over the unit that
     * killed it. Layer beats depth, always.
     */
    @Test
    fun `a corpse is under every body whatever its y`() {
        val order = WorldDrawOrder()
        order.begin()
        // The corpse is nearest the viewer, so depth alone would draw it last, over everything.
        order.add(entity(1), DrawLayer.CORPSE, y = -500f)
        order.add(entity(2), DrawLayer.UNIT, y = 500f)
        order.add(entity(3), DrawLayer.EFFECT, y = 500f)
        order.sort()
        assertEquals(
            listOf(1, 2, 3),
            List(order.size) { order.entityAt(it).id },
            "corpse, then body, then flash - regardless of depth",
        )
    }

    /**
     * Two units at exactly one y draw in the order they were added, every frame.
     *
     * Not cosmetic. `Arrays.sort` over equal keys is not a stable sort, so without the slot in the
     * key the pair would swap on a whim of the quicksort's pivot choice - and two captures of one
     * paused, unmutated world would then differ, which is the property `render.compare_artifacts`
     * is built on.
     */
    @Test
    fun `two units at one y keep their order`() {
        val order = WorldDrawOrder()
        repeat(2) {
            order.begin()
            for (id in 0 until 20) order.add(entity(id), DrawLayer.UNIT, y = 7f)
            order.sort()
            assertEquals(
                List(20) { it },
                List(order.size) { order.entityAt(it).id },
                "equal depths must resolve to insertion order",
            )
        }
    }

    /** [WorldDrawOrder.begin] resets the cursor, so a frame never sees the last frame's entities. */
    @Test
    fun `begin drops the previous frame`() {
        val order = WorldDrawOrder()
        order.begin()
        order.add(entity(1), DrawLayer.UNIT, y = 0f)
        order.add(entity(2), DrawLayer.UNIT, y = 1f)
        order.begin()
        order.add(entity(3), DrawLayer.UNIT, y = 0f)
        order.sort()
        assertEquals(1, order.size)
        assertEquals(3, order.entityAt(0).id)
    }

    /** More entities than the initial capacity is a growth, not an exception. */
    @Test
    fun `it grows past its initial capacity`() {
        val order = WorldDrawOrder(initialCapacity = 2)
        order.begin()
        val count = WorldDrawOrder.DEFAULT_CAPACITY * 3
        for (id in 0 until count) order.add(entity(id), DrawLayer.UNIT, y = id.toFloat())
        order.sort()
        assertEquals(count, order.size)
        assertEquals(count - 1, order.entityAt(0).id, "the highest y is drawn first")
        assertEquals(0, order.entityAt(count - 1).id)
    }

    /**
     * A layer outside the key's byte is refused, loudly.
     *
     * The failure it prevents is the worst kind to attribute: bit 63 is the sign of the key, so a
     * layer of 128 would invert the entire frame's order rather than misplacing one entity.
     */
    @Test
    fun `an out of range layer is refused`() {
        val order = WorldDrawOrder()
        order.begin()
        val failure = assertFailsWith<IllegalArgumentException> {
            order.add(entity(1), WorldDrawOrder.MAX_LAYER + 1, y = 0f)
        }
        assertTrue(
            failure.message.orEmpty().contains("${WorldDrawOrder.MAX_LAYER}"),
            "the message must name the bound: ${failure.message}",
        )
    }
}
