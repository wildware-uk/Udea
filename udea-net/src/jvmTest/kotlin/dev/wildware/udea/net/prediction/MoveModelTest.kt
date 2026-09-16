package dev.wildware.udea.net.prediction

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.input.MoveInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The property everything else in this package rests on: the client's step and the server's step
 * are the *same arithmetic*, so a replay is a re-run and not an estimate (spec 3.4).
 */
class MoveModelTest {

    private val model = PlanarMoveModel(SPEED)

    private fun command(x: Float, y: Float, seq: Int = 0, tick: Long = 0L): MoveInput =
        MoveInput(seq, Tick(tick), x, y, 0f, 0)

    @Test
    fun `the model steps the axis the wire will deliver, not the axis the player pressed`() {
        // 0.3 is not representable in eight bits over -1..1; the server will read the rounded
        // value, because the raw one never leaves the client.
        val pressed = 0.3f
        val onWire = PlanarMoveModel.onWire(pressed)
        assertNotEquals(pressed, onWire, "0.3 must not survive an 8-bit axis round trip intact")

        val pose = PredictedPose()
        model.step(pose, command(pressed, 0f))
        assertEquals(onWire * SPEED, pose.x, "prediction must use the value the server will apply")
    }

    @Test
    fun `a hundred held ticks of an unrepresentable axis do not drift from the server`() {
        // The failure this guards is not one tick of rounding, it is the accumulation: the raw
        // and the wire value differ in a fixed direction, so a hundred ticks is a hundred times
        // the difference, which is what a reconciler would then correct forever.
        val pressed = 0.3f
        val predicted = PredictedPose()
        val server = PredictedPose()
        val wire = PlanarMoveModel.onWire(pressed)
        repeat(HELD_TICKS) {
            model.step(predicted, command(pressed, 0f))
            // What the server does: it only ever sees the dequantised axis.
            server.x += wire * SPEED
        }
        assertEquals(
            server.x.toRawBits(),
            predicted.x.toRawBits(),
            "after $HELD_TICKS held ticks the prediction must be bit-identical to the server",
        )
    }

    @Test
    fun `replaying the same commands from the same pose is bit-identical`() {
        val commands = List(SEQUENCE_LENGTH) { command(AXES[it % AXES.size], AXES[(it + 1) % AXES.size], it) }
        val first = PredictedPose(START_X, START_Y)
        val second = PredictedPose(START_X, START_Y)
        for (c in commands) model.step(first, c)
        for (c in commands) model.step(second, c)
        assertEquals(first.x.toRawBits(), second.x.toRawBits(), "x must replay bit for bit")
        assertEquals(first.y.toRawBits(), second.y.toRawBits(), "y must replay bit for bit")
        assertTrue(first.x != START_X || first.y != START_Y, "the sequence must actually move the pose")
    }

    @Test
    fun `a centred stick is exactly zero on the wire, so a standing champion stands`() {
        // THE REGRESSION GUARD for a defect that shipped: `MoveInput.AXIS` used to be
        // `Q.declared(bits = 8, min = -1f, max = 1f)`, which spreads 255 steps over the range and
        // so has no level on zero. A centred stick rounded to level 128 and came back as +0.0039,
        // the server walked the champion at 0.0029 units a tick for ever - about 106 world units
        // over a ten-minute match - and `PlayerMovementSystem`'s `moveX == 0f` early-out was dead
        // code for every networked client, because it tests the dequantised value.
        //
        // `Q.Axis8` gives up level 255 to put level 127 exactly on zero. This asserts the
        // property, not the encoding: a released stick must cost the pose nothing.
        assertEquals(0f, PlanarMoveModel.onWire(0f), "a centred stick must be zero on the wire")
        assertEquals(1f, PlanarMoveModel.onWire(1f), "and the ends must still be the ends")
        assertEquals(-1f, PlanarMoveModel.onWire(-1f))

        val pose = PredictedPose()
        pose.x = START_X
        pose.y = START_Y
        repeat(HELD_TICKS) { model.step(pose, command(0f, 0f)) }
        assertEquals(START_X.toRawBits(), pose.x.toRawBits(), "a standing champion must not drift")
        assertEquals(START_Y.toRawBits(), pose.y.toRawBits())
    }

    private companion object {
        const val SPEED = 0.75f
        const val HELD_TICKS = 100
        const val SEQUENCE_LENGTH = 64
        const val START_X = 12.5f
        const val START_Y = -3.25f
        val AXES = floatArrayOf(1f, -1f, 0.3f, 0f, -0.7f, 0.125f)
    }
}
