package dev.wildware.udea.replay

import dev.wildware.udea.core.identity.NetId

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
 * ## The pointer, and why it is world units (issue #262)
 *
 * An RTS is driven by the mouse: "move there", "build here", "attack that". Those orders are a world
 * point and an entity, and a recording that stored only keys and sticks would replay a match in which
 * nobody ever gave one - silently, because every array would still be the right length. So the
 * pointer travels with the rest of the tick, behind [POINTER_PRESENT].
 *
 * What it stores is the **world** point presentation computed, never the pixel it came from. A pixel
 * would replay differently on another window size, and a pixel in a sample is a pixel one step away
 * from the simulation, which is the thing `Intent` exists to prevent.
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

    // --- the pointer (issue #262), in world units and never in pixels ------------------------

    /** Whether the player was pointing at the world at all this tick. */
    public var hasPointer: Boolean = false
        private set

    /** Where on the ground the pointer was. Meaningless unless [hasPointer]. */
    public var pointerX: Float = 0f
        private set

    /** Where on the ground the pointer was. Meaningless unless [hasPointer]. */
    public var pointerY: Float = 0f
        private set

    /** The entity drawn under the pointer, or [NetId.NONE] for open ground. */
    public var pointerEntity: NetId = NetId.NONE
        private set

    /** Wheel notches this tick. Positive is away from the player. */
    public var scroll: Float = 0f
        private set

    /** Whether a drag began this tick. */
    public var dragStarted: Boolean = false
        private set

    /** Where a drag begun this tick began. Meaningless unless [dragStarted]. */
    public var dragStartX: Float = 0f
        private set

    /** Where a drag begun this tick began. Meaningless unless [dragStarted]. */
    public var dragStartY: Float = 0f
        private set

    /** Whether a drag ended this tick. */
    public var dragEnded: Boolean = false
        private set

    /** Where a drag ended this tick ended. Meaningless unless [dragEnded]. */
    public var dragEndX: Float = 0f
        private set

    /** Where a drag ended this tick ended. Meaningless unless [dragEnded]. */
    public var dragEndY: Float = 0f
        private set

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

    /**
     * Says the player was pointing at world point ([worldX], [worldY]) on the ground, with [entity]
     * drawn under the cursor ([NetId.NONE] for open ground).
     *
     * @throws IllegalArgumentException for a point that is not a number. A `NaN` in a recording
     *   compares equal to nothing, so every tick after it replays as a mismatch and the divergence
     *   report points at the wrong place - the exact class of silent failure the format's length
     *   prefixes and bounds exist to stop.
     */
    public fun setPointer(worldX: Float, worldY: Float, entity: NetId = NetId.NONE) {
        require(worldX.isFinite() && worldY.isFinite()) {
            "a pointer is at a finite world point, was ($worldX, $worldY)"
        }
        hasPointer = true
        pointerX = worldX
        pointerY = worldY
        pointerEntity = entity
    }

    /** Records [notches] of wheel this tick. Positive is away from the player. */
    public fun setScroll(notches: Float) {
        require(notches.isFinite()) { "a wheel turn is a finite number of notches, was $notches" }
        scroll = notches
    }

    /** Says a drag began this tick, at world point ([worldX], [worldY]) on the ground. */
    public fun setDragStart(worldX: Float, worldY: Float) {
        require(worldX.isFinite() && worldY.isFinite()) {
            "a drag begins at a finite world point, was ($worldX, $worldY)"
        }
        dragStarted = true
        dragStartX = worldX
        dragStartY = worldY
    }

    /** Says a drag ended this tick, at world point ([worldX], [worldY]) on the ground. */
    public fun setDragEnd(worldX: Float, worldY: Float) {
        require(worldX.isFinite() && worldY.isFinite()) {
            "a drag ends at a finite world point, was ($worldX, $worldY)"
        }
        dragEnded = true
        dragEndX = worldX
        dragEndY = worldY
    }

    /** Back to "nothing held, nothing pressed, every axis centred, pointing nowhere". Allocates nothing. */
    public fun clear() {
        axisX.fill(0f)
        axisY.fill(0f)
        held.fill(false)
        presses.fill(0)
        hasPointer = false
        pointerX = 0f
        pointerY = 0f
        pointerEntity = NetId.NONE
        scroll = 0f
        dragStarted = false
        dragStartX = 0f
        dragStartY = 0f
        dragEnded = false
        dragEndX = 0f
        dragEndY = 0f
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
        hasPointer = other.hasPointer
        pointerX = other.pointerX
        pointerY = other.pointerY
        pointerEntity = other.pointerEntity
        scroll = other.scroll
        dragStarted = other.dragStarted
        dragStartX = other.dragStartX
        dragStartY = other.dragStartY
        dragEnded = other.dragEnded
        dragEndX = other.dragEndX
        dragEndY = other.dragEndY
    }

    /**
     * True when nothing is held, nothing was pressed, every axis is centred and the pointer says
     * nothing.
     *
     * The pointer counts: a tick in which the player was hovering over a unit is a tick whose input
     * matters, and calling it idle would drop the hover and replay a match in which nobody aimed.
     */
    public fun isIdle(): Boolean {
        for (value in axisX) if (value != 0f) return false
        for (value in axisY) if (value != 0f) return false
        for (value in held) if (value) return false
        for (value in presses) if (value != 0) return false
        if (carriesPointer) return false
        return true
    }

    /**
     * True when any part of the pointer section carries something: what sets [POINTER_PRESENT], and
     * what decides whether a whole recording needs the format version that can express a pointer.
     */
    public val carriesPointer: Boolean
        get() = hasPointer || scroll != 0f || dragStarted || dragEnded

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
        if (carriesPointer) mask = mask or POINTER_PRESENT
        sink.u8(mask)
        if (mask and AXES_PRESENT != 0) {
            for (index in axisX.indices) {
                sink.f32(axisX[index])
                sink.f32(axisY[index])
            }
        }
        if (mask and HELD_PRESENT != 0) writeHeld(sink)
        if (mask and PRESSES_PRESENT != 0) for (count in presses) sink.u8(count)
        if (mask and POINTER_PRESENT != 0) writePointer(sink)
    }

    /**
     * The pointer section, behind a presence byte of its own.
     *
     * Two levels of mask rather than one, because the parts arrive on different clocks: a hovering
     * pointer is in nearly every tick of a strategy game, while a wheel notch and the two ends of a
     * drag are in a handful. One flat block would cost 25 bytes on every tick of every peer for the
     * sake of three numbers that are almost always zero, which over a match is most of the file.
     */
    private fun writePointer(sink: ByteSink) {
        var parts = 0
        if (hasPointer) parts = parts or POINTER_AT
        if (scroll != 0f) parts = parts or POINTER_SCROLL
        if (dragStarted) parts = parts or POINTER_DRAG_START
        if (dragEnded) parts = parts or POINTER_DRAG_END
        sink.u8(parts)
        if (parts and POINTER_AT != 0) {
            sink.f32(pointerX)
            sink.f32(pointerY)
            sink.i32(pointerEntity.raw)
        }
        if (parts and POINTER_SCROLL != 0) sink.f32(scroll)
        if (parts and POINTER_DRAG_START != 0) {
            sink.f32(dragStartX)
            sink.f32(dragStartY)
        }
        if (parts and POINTER_DRAG_END != 0) {
            sink.f32(dragEndX)
            sink.f32(dragEndY)
        }
    }

    /** [writePointer] backwards, into a sample [readFrom] has already cleared. */
    private fun readPointer(source: ByteSource) {
        val parts = source.u8()
        if (parts and POINTER_UNKNOWN_BITS != 0) {
            throw ReplayFormatException(
                "an input sample's pointer declares parts 0x${parts.toString(16)}, and this build " +
                    "knows only 0x${POINTER_KNOWN_BITS.toString(16)}; the file is corrupt or was " +
                    "written by a newer format that passed the version check by mistake",
            )
        }
        if (parts and POINTER_AT != 0) {
            hasPointer = true
            pointerX = source.f32()
            pointerY = source.f32()
            // `ofRaw` rather than a bare word: a reserved bit set in a corrupt file is refused here
            // rather than becoming an id no allocator ever issued.
            pointerEntity = NetId.ofRaw(source.i32())
        }
        if (parts and POINTER_SCROLL != 0) scroll = source.f32()
        if (parts and POINTER_DRAG_START != 0) {
            dragStarted = true
            dragStartX = source.f32()
            dragStartY = source.f32()
        }
        if (parts and POINTER_DRAG_END != 0) {
            dragEnded = true
            dragEndX = source.f32()
            dragEndY = source.f32()
        }
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
        if (mask and POINTER_PRESENT != 0) readPointer(source)
    }

    /** True when the two samples hold the same values. Used by the round-trip gate. */
    public fun contentEquals(other: InputSample): Boolean =
        other.schema == schema &&
            other.axisX.contentEquals(axisX) &&
            other.axisY.contentEquals(axisY) &&
            other.held.contentEquals(held) &&
            other.presses.contentEquals(presses) &&
            pointerEquals(other)

    /**
     * The pointer half of [contentEquals].
     *
     * Only the parts that are present are compared, because only those are written: two samples that
     * are not pointing anywhere are the same sample whatever stale coordinates happen to sit behind
     * [hasPointer], and a round trip brings back zeroes rather than what the writer had.
     */
    private fun pointerEquals(other: InputSample): Boolean {
        if (other.hasPointer != hasPointer) return false
        if (hasPointer &&
            (other.pointerX != pointerX || other.pointerY != pointerY || other.pointerEntity != pointerEntity)
        ) {
            return false
        }
        if (other.scroll != scroll) return false
        if (other.dragStarted != dragStarted) return false
        if (dragStarted && (other.dragStartX != dragStartX || other.dragStartY != dragStartY)) return false
        if (other.dragEnded != dragEnded) return false
        if (dragEnded && (other.dragEndX != dragEndX || other.dragEndY != dragEndY)) return false
        return true
    }

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
        if (hasPointer) {
            if (!first) append(", ")
            first = false
            append("at (").append(pointerX).append(", ").append(pointerY).append(')')
            if (!pointerEntity.isNone) append(" over ").append(pointerEntity)
        }
        if (scroll != 0f) {
            if (!first) append(", ")
            first = false
            append("wheel ").append(scroll)
        }
        if (dragStarted) {
            if (!first) append(", ")
            first = false
            append("drag from (").append(dragStartX).append(", ").append(dragStartY).append(')')
        }
        if (dragEnded) {
            if (!first) append(", ")
            first = false
            append("drag to (").append(dragEndX).append(", ").append(dragEndY).append(')')
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

        /** The presence mask's pointer section (issue #262). See [writePointer]. */
        public const val POINTER_PRESENT: Int = 8

        /** Every bit this build understands. */
        public const val KNOWN_BITS: Int = AXES_PRESENT or HELD_PRESENT or PRESSES_PRESENT or POINTER_PRESENT

        /** The pointer's own presence byte: where the cursor was, and what was under it. */
        private const val POINTER_AT: Int = 1

        /** The pointer's own presence byte: a wheel turn. */
        private const val POINTER_SCROLL: Int = 2

        /** The pointer's own presence byte: a drag began this tick. */
        private const val POINTER_DRAG_START: Int = 4

        /** The pointer's own presence byte: a drag ended this tick. */
        private const val POINTER_DRAG_END: Int = 8

        private const val POINTER_KNOWN_BITS: Int =
            POINTER_AT or POINTER_SCROLL or POINTER_DRAG_START or POINTER_DRAG_END

        private const val POINTER_UNKNOWN_BITS: Int = POINTER_KNOWN_BITS.inv() and 0xFF

        /** Bits that must be zero. A set one is a corrupt file, not a forward-compatible one. */
        private const val UNKNOWN_BITS: Int = KNOWN_BITS.inv() and 0xFF

        /** Presses of one action in one tick the format can carry: one byte's worth. */
        public const val MAX_PRESSES: Int = 255
    }
}
