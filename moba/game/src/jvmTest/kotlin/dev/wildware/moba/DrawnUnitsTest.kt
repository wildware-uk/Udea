package dev.wildware.moba

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.view.PickSink
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `CharacterRenderSystem` reports to the editor's picking (issue #235): each unit's body, where
 * it was drawn, in the order it was drawn - and nothing for a unit it cannot name.
 */
class DrawnUnitsTest {

    @Test
    fun `each unit is reported as its body around where it stands, in draw order, and one with no NetId is not`() {
        val drawn = DrawnUnits()
        drawn.begin()
        drawn.add(NetId.ofRaw(7), x = 10f, y = 20f, height = 100f)
        drawn.add(NetId.NONE, x = 0f, y = 0f, height = 100f)
        drawn.add(NetId.ofRaw(3), x = -5f, y = 0f, height = 50f)

        assertEquals(
            listOf(
                // Half a body across is a tenth of the frame's height; the foot sits FOOT_OF_HEIGHT
                // below the position, and the head 0.13 of the height above it.
                Reported(7, 0f, 11.5f, 20f, 33f),
                Reported(3, -10f, -4.25f, 0f, 6.5f),
            ),
            reported(drawn),
        )
    }

    @Test
    fun `a new frame forgets the last one, however many units that had`() {
        val drawn = DrawnUnits()
        drawn.begin()
        // More than the first arrays hold, so the growth is on the path too.
        repeat(DrawnUnits.INITIAL_CAPACITY + 1) { drawn.add(NetId.ofRaw(it + 1), it.toFloat(), 0f, 10f) }
        assertEquals(DrawnUnits.INITIAL_CAPACITY + 1, reported(drawn).size)
        assertEquals(DrawnUnits.INITIAL_CAPACITY + 1, reported(drawn).last().id)

        drawn.begin()
        drawn.add(NetId.ofRaw(9), 0f, 0f, 10f)
        assertEquals(listOf(9), reported(drawn).map { it.id })
    }

    private data class Reported(val id: Int, val left: Float, val bottom: Float, val right: Float, val top: Float)

    private fun reported(drawn: DrawnUnits): List<Reported> {
        val out = ArrayList<Reported>()
        drawn.report(object : PickSink {
            override fun rect(entity: NetId, minX: Float, minY: Float, maxX: Float, maxY: Float) {
                // To a thousandth, so a fraction's last float bit does not decide the test.
                out += Reported(entity.raw, milli(minX), milli(minY), milli(maxX), milli(maxY))
            }

            override fun box(entity: NetId, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
                error("a unit is a flat sprite and is reported as a rectangle")
            }
        })
        return out
    }

    private fun milli(value: Float): Float = Math.round(value * 1000f) / 1000f
}
