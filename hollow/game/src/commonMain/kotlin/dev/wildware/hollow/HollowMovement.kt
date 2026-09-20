package dev.wildware.hollow

import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.prediction.MoveModel
import dev.wildware.udea.net.prediction.PredictedPose
import kotlin.math.sqrt

/**
 * Hollow's one movement rule: how far a character gets in a tick for a given move axis.
 *
 * It is one object because two things have to agree about it to the last bit or the game
 * rubber-bands: [PlayerMovementSystem], which is the server's authority, and [HollowMoveModel],
 * which is what a client replays when the server's answer arrives (spec 3.4, and `MoveModel`'s own
 * KDoc says why a predictor may not hold its *own* approximation of the rule).
 *
 * Speeds are **units per second**, because that is the unit a Box2D velocity is in - the solver
 * integrates `position += velocity * h`, and `h` is the tick. Everything else in this game is
 * denominated in ticks; this is the one place a second is the right unit, and it is the solver's
 * second rather than a wall clock's.
 */
public object HollowMovement {

    /** How fast a character walks, in world units a second. A brisk human walk. */
    public const val WALK_SPEED: Float = 2.4f

    /** How fast a character runs, with [HollowControls.RUN] held. */
    public const val RUN_SPEED: Float = 5.4f

    /**
     * Bit 0 of [MoveInput.buttons]: run is held.
     *
     * A held button and not a press count: the character runs *while* Shift is down, so what
     * crosses the wire has to be the state and not the edge.
     */
    public const val RUN_BUTTON: Int = 1

    /** The fixed simulation rate this game runs at, and the rate a velocity is integrated over. */
    public const val TICK_RATE: Int = 60

    /** World units a second for a character that is or is not running. */
    public fun speed(running: Boolean): Float = if (running) RUN_SPEED else WALK_SPEED

    /** How far [speed] carries a character in one tick. What a predictor steps by. */
    public fun perTick(speed: Float): Float = speed / TICK_RATE
}

/**
 * One tick's asked-for direction: the two axes, clamped to the unit circle, reused in place.
 *
 * ## Why the clamp is part of the rule and not a tidy-up
 *
 * `DeviceIntent` already clamps a keyboard diagonal and a stick deflection to length one, so a
 * player's own axis arrives no longer than that. Two things can still make it longer: the wire
 * codec, which rounds each axis separately and so turns `(0.707, 0.707)` into a vector a shade over
 * one; and a client that simply writes `(1, 1)` into the datagram, which is forty per cent of free
 * speed along every diagonal. The clamp is applied *here*, in the one rule both ends run, so it is
 * not something the server has that a predictor lacks - the difference a reconciler would then
 * correct on every snapshot, forever.
 *
 * Mutable and reused because it is on the per-tick path, and a reconciliation replays every
 * unacknowledged command on the tick a correction lands.
 */
internal class MoveAxis {

    /** The clamped x. */
    var x: Float = 0f
        private set

    /** The clamped y. */
    var y: Float = 0f
        private set

    /** True when the player asked to move at all this tick. */
    val isMoving: Boolean get() = x != 0f || y != 0f

    /** Reads ([rawX], [rawY]) in, clamped to the unit circle, and returns this. */
    fun set(rawX: Float, rawY: Float): MoveAxis {
        val square = rawX * rawX + rawY * rawY
        if (square > 1f) {
            // `sqrt` is the one transcendental IEEE-754 specifies exactly, so both ends of a
            // session get the same bits from it - see `determinism-audit.md` section 3.1, which
            // probed it at zero differences in two million samples.
            val scale = 1f / sqrt(square)
            x = rawX * scale
            y = rawY * scale
        } else {
            x = rawX
            y = rawY
        }
        return this
    }

    override fun toString(): String = "MoveAxis($x, $y)"
}

/**
 * [HollowMovement] as a `udea-net` [MoveModel]: what a client replays over the server's answer.
 *
 * Steps the axis the **server will read** rather than the one the player pressed: a command goes on
 * the wire through `MoveInput.AXIS`, eight bits over `-1..1`, so the server applies
 * `dequantise(quantise(pressed))`. `PlanarMoveModel`'s KDoc spells out what predicting from the raw
 * value costs - a standing error in a fixed direction that the reconciler corrects on every single
 * snapshot, which is a permanent low-amplitude rubber-band and is invisible in a loopback test.
 *
 * ## What it is not
 *
 * It is not Box2D. The server's character is a dynamic body, so a tick the character spends against
 * a rock moves it less than this says. That is exactly the case prediction exists to absorb: the
 * disagreement is the information, the server's position is the authority, and
 * `PredictionSmoothing` decides how it is taken. On open ground with no loss the two agree to
 * within the solver's own rounding, which is several orders of magnitude inside
 * `PredictionSmoothing.tolerance`.
 */
internal class HollowMoveModel(
    /** Walk speed in units a second. The server's own, so the two cannot drift apart. */
    private val walkSpeed: Float = HollowMovement.WALK_SPEED,
    /** Run speed in units a second. */
    private val runSpeed: Float = HollowMovement.RUN_SPEED,
) : MoveModel {

    private val axis = MoveAxis()

    override fun step(pose: PredictedPose, command: MoveInput) {
        axis.set(onWire(command.moveX), onWire(command.moveY))
        if (!axis.isMoving) return
        val running = command.buttons and HollowMovement.RUN_BUTTON != 0
        val perTick = HollowMovement.perTick(if (running) runSpeed else walkSpeed)
        pose.x = pose.x + axis.x * perTick
        pose.y = pose.y + axis.y * perTick
    }

    override fun toString(): String = "HollowMoveModel(walk=$walkSpeed, run=$runSpeed)"

    private companion object {
        /** [value] as the server will read it: through the wire's quantiser and back. */
        fun onWire(value: Float): Float = MoveInput.AXIS.dequantise(MoveInput.AXIS.quantise(value))
    }
}
