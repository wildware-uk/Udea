package dev.wildware.moba

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.view.PickSink

/**
 * Where each unit was drawn by the most recent frame, back to front: what [CharacterRenderSystem]
 * reports to the editor so a click in the Scene tab picks a unit (issue #235).
 *
 * Recorded while drawing rather than worked out again when asked, so a click picks exactly what the
 * person was looking at: the frame the animation was on, the sheet's live scale, the draw order.
 *
 * The rectangle is the unit's body, not its frame. A character sheet's frames are mostly transparent
 * margin - a 100px frame at scale 1.88 draws 188 world units, and the character inside it is about
 * 30 - so the whole frame would put a unit under the pointer wherever its neighbours stand.
 *
 * Reused frame after frame: [begin] forgets, [add] grows the arrays only when a frame has more units
 * than any before it.
 */
internal class DrawnUnits {

    private var ids = IntArray(INITIAL_CAPACITY)
    private var rects = FloatArray(INITIAL_CAPACITY * SIDES)
    private var size = 0

    /** Forgets the last frame. */
    fun begin() {
        size = 0
    }

    /**
     * [id] was drawn standing at ([x], [y]) in a frame [height] world units tall. An entity with no
     * `NetId` cannot be selected and is not kept.
     */
    fun add(id: NetId, x: Float, y: Float, height: Float) {
        if (id.isNone) return
        if (size == ids.size) grow()
        val at = size * SIDES
        rects[at] = x - height * BODY_HALF_WIDTH_OF_HEIGHT
        rects[at + 1] = y - height * CharacterRenderSystem.FOOT_OF_HEIGHT
        rects[at + 2] = x + height * BODY_HALF_WIDTH_OF_HEIGHT
        rects[at + 3] = y + height * BODY_TOP_OF_HEIGHT
        ids[size] = id.raw
        size++
    }

    /** Reports every unit kept, in the order it was drawn. */
    fun report(out: PickSink) {
        for (index in 0 until size) {
            val at = index * SIDES
            out.rect(NetId.ofRaw(ids[index]), rects[at], rects[at + 1], rects[at + 2], rects[at + 3])
        }
    }

    private fun grow() {
        ids = ids.copyOf(ids.size * 2)
        rects = rects.copyOf(rects.size * 2)
    }

    override fun toString(): String = "DrawnUnits($size)"

    internal companion object {
        /** Units in the first frame's arrays: a full lane wave and both teams' champions. */
        const val INITIAL_CAPACITY = 64

        /** A rectangle's four numbers: left, bottom, right, top. */
        private const val SIDES = 4

        /**
         * Half a body's width, as a fraction of the drawn frame's height: the character inside a
         * frame is about a fifth of it across. A fraction, for the reason `FOOT_OF_HEIGHT` is one.
         */
        const val BODY_HALF_WIDTH_OF_HEIGHT: Float = 0.1f

        /** How far above a unit's [Position] its head is, as a fraction of the drawn frame's height. */
        const val BODY_TOP_OF_HEIGHT: Float = 0.13f
    }
}
