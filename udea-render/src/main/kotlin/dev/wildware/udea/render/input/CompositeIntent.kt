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
    }

    override fun toString(): String = "CompositeIntent(${sources.size} source(s))"
}
