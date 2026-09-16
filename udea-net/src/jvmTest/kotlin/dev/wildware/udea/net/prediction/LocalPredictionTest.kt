package dev.wildware.udea.net.prediction

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.input.JitterBuffer
import dev.wildware.udea.net.input.MoveInput
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prediction, reconciliation and the smoothing that stops a correction reading as a yank. */
class LocalPredictionTest {

    private val model = PlanarMoveModel(SPEED)

    private fun predictor(
        smoothing: PredictionSmoothing = PredictionSmoothing(),
        capacity: Int = LocalPrediction.DEFAULT_HISTORY_CAPACITY,
    ) = LocalPrediction(model, smoothing, capacity)

    private fun command(seq: Int, x: Float = 1f, y: Float = 0f): MoveInput =
        MoveInput(seq and SEQ_MASK, Tick(seq.toLong()), x, y, 0f, 0)

    /**
     * Where the server ends up after simulating [commands], by accumulation.
     *
     * Accumulated through the same [PlanarMoveModel] rather than computed as `step * n`, because
     * a product and a sum of floats are not the same number and a test that compared them would
     * be asserting the wrong thing - and would then need a tolerance that hid real errors.
     */
    private fun serverAfter(commands: Int): PredictedPose {
        val pose = PredictedPose()
        repeat(commands) { model.step(pose, command(it)) }
        return pose
    }

    @Test
    fun `the champion moves on the tick the input is read`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        prediction.predict(command(0))
        assertEquals(STEP, prediction.x, "a predicted command must move the pose immediately")
        assertEquals(1, prediction.pendingCount, "and be held for replay")
    }

    @Test
    fun `a server that saw every command corrects by nothing at all`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        repeat(TICKS) { prediction.predict(command(it)) }
        val server = serverAfter(TICKS)
        val error = prediction.reconcile(server.x, server.y, TICKS - 1)
        assertEquals(0f, error, "an agreeing server must produce no correction at all")
        assertEquals(0L, prediction.corrections, "and must not be counted as one")
        assertEquals(0, prediction.pendingCount, "every acknowledged command must be dropped")
    }

    @Test
    fun `unacknowledged commands are replayed on top of the correction`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        repeat(TICKS) { prediction.predict(command(it)) }
        val predictedBefore = prediction.settledX
        // The server has only simulated the first five; the other five are still in flight.
        val server = serverAfter(5)
        prediction.reconcile(server.x, server.y, 4)
        assertEquals(TICKS - 5, prediction.pendingCount, "the in-flight commands must be kept")
        assertEquals(
            predictedBefore.toRawBits(),
            prediction.settledX.toRawBits(),
            "replay must reproduce the prediction bit for bit, not approximately",
        )
        assertEquals(0L, prediction.corrections, "an in-flight command is not a disagreement")
    }

    @Test
    fun `input the server never received is a correction, and it is smoothed rather than snapped`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        repeat(TICKS) { prediction.predict(command(it)) }
        val drawnBefore = prediction.x
        // Everything is acknowledged, but three commands never arrived at all, so the server is
        // three steps short of where the client predicted.
        val lost = 3
        val authoritative = serverAfter(TICKS - lost)
        val error = prediction.reconcile(authoritative.x, authoritative.y, TICKS - 1)
        assertEquals(STEP * lost, error, TOLERANCE, "the correction is the input the server lost")
        assertEquals(1L, prediction.corrections)
        assertEquals(0L, prediction.snaps, "an ordinary correction must not snap")
        assertEquals(
            drawnBefore,
            prediction.x,
            TOLERANCE,
            "the drawn position must not move on the tick the correction lands",
        )

        var ticks = 0
        while (prediction.residual > 0f && ticks < CONVERGE_LIMIT) {
            prediction.advance()
            ticks++
        }
        assertTrue(ticks < CONVERGE_LIMIT, "the residual must converge; it took $ticks ticks")
        assertEquals(
            authoritative.x,
            prediction.x,
            TOLERANCE,
            "once settled the drawn position is the server's answer",
        )
    }

    @Test
    fun `no single tick of a correction moves the character more than the decay allows`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        repeat(TICKS) { prediction.predict(command(it)) }
        val server = serverAfter(TICKS - 3)
        val error = prediction.reconcile(server.x, server.y, TICKS - 1)
        var previous = prediction.x
        var worst = 0f
        repeat(CONVERGE_LIMIT) {
            prediction.advance()
            val step = abs(prediction.x - previous)
            if (step > worst) worst = step
            previous = prediction.x
        }
        val allowed = error * (1f - PredictionSmoothing.DEFAULT_DECAY) + TOLERANCE
        assertTrue(
            worst <= allowed,
            "smoothing must bound the per-tick move to $allowed, worst was $worst",
        )
    }

    @Test
    fun `a correction past the snap distance is taken whole`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        prediction.predict(command(0))
        // A respawn: the server moved the champion, it did not correct it.
        prediction.reconcile(FAR, 0f, 0)
        assertEquals(1L, prediction.snaps, "a teleport must not be smoothed")
        assertEquals(0f, prediction.residual, "and must leave no residual to bleed off")
        assertEquals(FAR, prediction.x, "the drawn position is the new one immediately")
    }

    @Test
    fun `acknowledgement drops history correctly across a sequence wrap`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        // Sequences straddling the 16-bit wrap: 65534, 65535, 0, 1, 2.
        val seqs = intArrayOf(SEQ_MASK - 1, SEQ_MASK, 0, 1, 2)
        for (seq in seqs) prediction.predict(command(seq))
        val predictedBefore = prediction.settledX
        // Sequence 0 here is the third command, not the oldest: a `<=` comparison would keep
        // everything and a naive `>` would drop everything.
        val server = serverAfter(3)
        prediction.reconcile(server.x, server.y, 0)
        assertEquals(
            2,
            prediction.pendingCount,
            "acking sequence 0 must drop 65534, 65535 and 0, leaving 1 and 2",
        )
        assertEquals(
            predictedBefore.toRawBits(),
            prediction.settledX.toRawBits(),
            "and replay the two that are left, back to where the prediction was",
        )
    }

    @Test
    fun `a server that has simulated nothing keeps the whole history`() {
        val prediction = predictor()
        prediction.start(0f, 0f)
        repeat(TICKS) { prediction.predict(command(it)) }
        val predictedBefore = prediction.settledX
        prediction.reconcile(0f, 0f, JitterBuffer.NO_SEQ)
        assertEquals(TICKS, prediction.pendingCount)
        assertEquals(predictedBefore.toRawBits(), prediction.settledX.toRawBits())
    }

    @Test
    fun `overrunning the history is counted rather than silently forgotten`() {
        val prediction = predictor(capacity = 4)
        prediction.start(0f, 0f)
        repeat(6) { prediction.predict(command(it)) }
        assertEquals(2L, prediction.overruns, "two commands were older than the history could hold")
        assertEquals(4, prediction.pendingCount)
    }

    private companion object {
        const val SPEED = 0.75f
        const val TICKS = 10
        const val SEQ_MASK = 0xFFFF
        const val TOLERANCE = 1e-4f
        const val SETTLED = 1e-3f
        const val CONVERGE_LIMIT = 200
        const val FAR = 500f

        /** One tick of full deflection, through the wire codec: what both peers actually move. */
        val STEP: Float = PlanarMoveModel.onWire(1f) * SPEED
    }
}
