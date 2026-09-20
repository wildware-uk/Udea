package dev.wildware.udea.render.input

/**
 * Several [IntentSource]s combined into one: a human at the keyboard *and* an agent driving.
 *
 * A Windowed agent instance is the case this exists for. A human is watching the window and can
 * still play; the agent is synthesising input into the same simulation. Either can move the
 * character, and neither cancels the other - held actions are OR'd, press edges are summed, and
 * axis vectors are added and then clamped to length 1 the same way one source's own key pair is.
 *
 * ## What it deliberately does not do
 *
 * It does not arbitrate. If a human holds "left" while an agent holds "right" the character
 * stands still, exactly as it would if one person pressed both keys. Priority rules ("the human
 * wins") sound obviously right and are not: they make an agent's synthesised input behave
 * differently from a human's, which is the one property this whole model exists to guarantee.
 *
 * ## The one exception, and it is forced (issue #262)
 *
 * The pointer. Two held keys combine because "held" is a boolean; two *positions* do not - there is
 * no point that is both of them, and the midpoint is somewhere neither source asked for. So the
 * **last** source in [sources] that is pointing somewhere is the one that reaches the tick, and the
 * ordering is the game's, because the game built the list. A drag beginning and a drag ending follow
 * the pointer for the same reason. Only the wheel combines, because notches are a delta and two
 * deltas add.
 *
 * Stated here rather than left to be discovered, because the alternative - dropping the pointer
 * whenever a game composes two sources - is a mouse that stops working the day an agent attaches.
 *
 * The scratch intents are allocated at construction, one per source, and reused - so a composite
 * sample allocates nothing per tick.
 */
public class CompositeIntent(
    private val catalog: InputCatalog,
    private val sources: List<IntentSource>,
) : IntentSource {

    private val scratch: Array<Intent> = Array(sources.size) { Intent(catalog) }

    override fun sample(into: Intent) {
        for (index in sources.indices) {
            val part = scratch[index]
            part.clear()
            sources[index].sample(part)
        }
        for (index in 0 until catalog.actionCount) {
            val id = ActionId(index)
            var held = false
            var presses = 0
            for (part in scratch) {
                if (part.isPressed(id)) held = true
                presses += part.pressCount(id)
            }
            into.setPressed(id, held)
            into.setPressCount(id, presses)
        }
        for (index in 0 until catalog.axisCount) {
            val id = AxisId(index)
            var x = 0f
            var y = 0f
            for (part in scratch) {
                x += part.axisX(id)
                y += part.axisY(id)
            }
            val length = kotlin.math.sqrt(x * x + y * y)
            if (length > 1f) {
                x /= length
                y /= length
            }
            into.setAxis(id, x, y)
        }
        combinePointer(into)
    }

    /** The pointer half: the last source that has one wins, and the wheel adds. See the class KDoc. */
    private fun combinePointer(into: Intent) {
        var notches = 0f
        for (part in scratch) notches += part.scroll
        if (notches != 0f) into.setScroll(notches)
        for (index in scratch.indices.reversed()) {
            val part = scratch[index]
            if (!part.hasPointer) continue
            into.setPointer(part.pointerX, part.pointerY, part.pointerEntity)
            break
        }
        for (index in scratch.indices.reversed()) {
            val part = scratch[index]
            if (!part.dragStarted) continue
            into.setDragStart(part.dragStartX, part.dragStartY)
            break
        }
        for (index in scratch.indices.reversed()) {
            val part = scratch[index]
            if (!part.dragEnded) continue
            into.setDragEnd(part.dragEndX, part.dragEndY)
            break
        }
    }

    override fun toString(): String = "CompositeIntent(${sources.size} source(s))"
}
