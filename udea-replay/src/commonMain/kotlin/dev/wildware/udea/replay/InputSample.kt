package dev.wildware.udea.replay

/**
 * One peer's input for **one tick**: the unit a recording is made of.
 *
 * This is the same shape `udea-render`'s `Intent` holds - held actions, press counts, two-axis
 * sticks - written down in a module with no device in it, so a recording can be produced,
 * validated and replayed by a process that has never opened a window. The game copies between
 * the two; see [InputSchema] for why this module may not simply name `Intent`.
 *
 * ## Press counts, not press flags
 *
 * `Intent` counts presses rather than flagging them because a key tapped and released between
 * two frames is never *held* at any sample point, and a flag loses it outright. A recording that
 * stored a flag would therefore replay a fight with roughly one attack in three missing at 30fps
 * - a divergence with a cause nobody would find by looking at the replay code. So the count is
 * what is recorded, and [ReplayFormat] gives it a whole byte per action.
 *
 * ## Reused, never allocated per tick
 *
 * A recorder owns one of these and overwrites it every tick, exactly as `IntentState` owns one
 * `Intent`. [writeTo] allocates nothing.
 */
public class InputSample(
    /** The vocabulary this sample's indices refer to. */
    public val schema: InputSchema,
) {

    private val axisX = FloatArray(schema.axisCount)
    private val axisY = FloatArray(schema.axisCount)
    private val held = BooleanArray(schema.actionCount)
    private val presses = IntArray(schema.actionCount)

    /** Horizontal component of the axis at [axis], in `-1..1`. */
    public fun axisX(axis: Int): Float = axisX[axis]

    /** Vertical component of the axis at [axis], in `-1..1`. */
    public fun axisY(axis: Int): Float = axisY[axis]

    /** Whether the action at [action] is held as of this tick. */
    public fun isPressed(action: Int): Boolean = held[action]

    /** How many distinct presses of the action at [action] this tick covers. */
    public fun pressCount(action: Int): Int = presses[action]

    /** Sets the axis at [axis]. Values are stored as given; clamping is the producer's job. */
    public fun setAxis(axis: Int, x: Float, y: Float) {
        axisX[axis] = x
        axisY[axis] = y
    }

    /** Sets whether the action at [action] is held. */
    public fun setPressed(action: Int, pressed: Boolean) {
        held[action] = pressed
    }

    /**
     * Records [count] fresh presses of the action at [action].
     *
     * @throws IllegalArgumentException past [MAX_PRESSES]. The format gives a press count one
     *   byte, and silently truncating 300 presses to 44 would be a divergence the file itself
     *   caused - the exact class of silent failure standards section 1 bans.
     */
    public fun setPressCount(action: Int, count: Int) {
        require(count in 0..MAX_PRESSES) {
            "a press count must be in 0..$MAX_PRESSES, was $count for action " +
                "'${schema.actions[action]}'"
        }
        presses[action] = count
    }

    /** Back to "nothing held, nothing pressed, every axis centred". Allocates nothing. */
    public fun clear() {
        axisX.fill(0f)
        axisY.fill(0f)
        held.fill(false)
        presses.fill(0)
    }

    /** Copies [other], which must be over the same schema. */
    public fun copyFrom(other: InputSample) {
        require(other.schema == schema) {
            "cannot copy an InputSample across schemas: ${other.schema} into $schema"
        }
        other.axisX.copyInto(axisX)
        other.axisY.copyInto(axisY)
        other.held.copyInto(held)
        other.presses.copyInto(presses)
    }

    /** True when nothing is held, nothing was pressed and every axis is centred. */
    public fun isIdle(): Boolean {
        for (value in axisX) if (value != 0f) return false
        for (value in axisY) if (value != 0f) return false
        for (value in held) if (value) return false
        for (value in presses) if (value != 0) return false
        return true
    }

    /**
     * Writes this sample, section by section, behind a one-byte presence mask.
     *
     * The mask is what makes an idle tick one byte rather than sixty. A match is mostly idle
     * per-action - a player holds two keys out of eight and touches one stick - and a recording
     * that paid full width for every tick of every peer would be twenty times the size for no
     * information at all.
     */
    public fun writeTo(sink: ByteSink) {
        var mask = 0
        if (hasAxes()) mask = mask or AXES_PRESENT
        if (hasHeld()) mask = mask or HELD_PRESENT
        if (hasPresses()) mask = mask or PRESSES_PRESENT
        sink.u8(mask)
        if (mask and AXES_PRESENT != 0) {
            for (index in axisX.indices) {
                sink.f32(axisX[index])
                sink.f32(axisY[index])
            }
        }
        if (mask and HELD_PRESENT != 0) writeHeld(sink)
        if (mask and PRESSES_PRESENT != 0) for (count in presses) sink.u8(count)
    }

    /**
     * Reads a sample written by [writeTo] into this one, which is cleared first.
     *
     * Cleared first and not "overwritten section by section": an absent section means *zero*,
     * and a reader that left the previous tick's values in place would replay a released stick
     * as a held one for as long as the player did not touch it. That is the `Q.Axis8` bug in a
     * different costume, and it walked a standing character across the map for ever.
     */
    public fun readFrom(source: ByteSource) {
        clear()
        val mask = source.u8()
        if (mask and UNKNOWN_BITS != 0) {
            throw ReplayFormatException(
                "an input sample declares presence bits 0x${mask.toString(16)}, and this build " +
                    "knows only 0x${KNOWN_BITS.toString(16)}; the file is corrupt or was written " +
                    "by a newer format that passed the version check by mistake",
            )
        }
        if (mask and AXES_PRESENT != 0) {
            for (index in axisX.indices) {
                axisX[index] = source.f32()
                axisY[index] = source.f32()
            }
        }
        if (mask and HELD_PRESENT != 0) readHeld(source)
        if (mask and PRESSES_PRESENT != 0) for (index in presses.indices) presses[index] = source.u8()
    }

    /** True when the two samples hold the same values. Used by the round-trip gate. */
    public fun contentEquals(other: InputSample): Boolean =
        other.schema == schema &&
            other.axisX.contentEquals(axisX) &&
            other.axisY.contentEquals(axisY) &&
            other.held.contentEquals(held) &&
            other.presses.contentEquals(presses)

    override fun toString(): String = buildString {
        append("InputSample(")
        var first = true
        for (index in 0 until schema.actionCount) {
            if (!held[index] && presses[index] == 0) continue
            if (!first) append(", ")
            first = false
            append(schema.actions[index])
            if (presses[index] > 0) append('!').append(presses[index])
        }
        for (index in 0 until schema.axisCount) {
            if (axisX[index] == 0f && axisY[index] == 0f) continue
            if (!first) append(", ")
            first = false
            append(schema.axes[index]).append("=(").append(axisX[index]).append(", ")
                .append(axisY[index]).append(')')
        }
        if (first) append("idle")
        append(')')
    }

    private fun hasAxes(): Boolean {
        for (value in axisX) if (value != 0f) return true
        for (value in axisY) if (value != 0f) return true
        return false
    }

    private fun hasHeld(): Boolean {
        for (value in held) if (value) return true
        return false
    }

    private fun hasPresses(): Boolean {
        for (value in presses) if (value != 0) return true
        return false
    }

    private fun writeHeld(sink: ByteSink) {
        var byte = 0
        for (index in held.indices) {
            if (held[index]) byte = byte or (1 shl (index and 7))
            if (index and 7 == 7) {
                sink.u8(byte)
                byte = 0
            }
        }
        if (held.size and 7 != 0) sink.u8(byte)
    }

    private fun readHeld(source: ByteSource) {
        var byte = 0
        for (index in held.indices) {
            if (index and 7 == 0) byte = source.u8()
            held[index] = byte and (1 shl (index and 7)) != 0
        }
    }

    public companion object {

        /** The presence mask's axis section. */
        public const val AXES_PRESENT: Int = 1

        /** The presence mask's held-action section. */
        public const val HELD_PRESENT: Int = 2

        /** The presence mask's press-count section. */
        public const val PRESSES_PRESENT: Int = 4

        /** Every bit this build understands. */
        public const val KNOWN_BITS: Int = AXES_PRESENT or HELD_PRESENT or PRESSES_PRESENT

        /** Bits that must be zero. A set one is a corrupt file, not a forward-compatible one. */
        private const val UNKNOWN_BITS: Int = KNOWN_BITS.inv() and 0xFF

        /** Presses of one action in one tick the format can carry: one byte's worth. */
        public const val MAX_PRESSES: Int = 255
    }
}
