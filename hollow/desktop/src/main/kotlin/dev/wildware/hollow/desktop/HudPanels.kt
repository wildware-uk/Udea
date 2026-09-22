package dev.wildware.hollow.desktop

import dev.wildware.hollow.render.HollowHudLook
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Whether a captured frame carries Hollow's HUD, read as a pixel of each of its solid panels
 * (issue #252).
 *
 * The only thing that can tell a `CapturedUi` from a `UiLayer` from the outside. A HUD drawn into
 * the window instead of the capture, or one that stopped drawing, leaves a picture that looks
 * perfectly fine and has no panel in it - so the check is a colour at a coordinate the layout fixes,
 * taken from [HollowHudLook] rather than written down twice.
 *
 * In `main` rather than in either caller, because both `HollowFightShot` and the HUD soak ask
 * exactly this question and a second copy of it could answer differently from the first.
 */
internal object HudPanels {

    /** One solid panel: what it is called, the colour it is, and where to read it. */
    private class Panel(val name: String, val rgb: Int, val at: (height: Int) -> Pair<Int, Int>)

    private val PANELS = listOf(
        // Inside the top strip's own background, past its corner.
        Panel("wave strip", HollowHudLook.PANEL_RGB) {
            (HollowHudLook.MARGIN + INSET).toInt() to (HollowHudLook.MARGIN + INSET).toInt()
        },
        // Inside the player panel's padding, beside its bottom-left corner: always panel,
        // whatever it holds.
        Panel("player panel", HollowHudLook.PANEL_RGB) { height ->
            (HollowHudLook.MARGIN + INSET).toInt() to (height - HollowHudLook.MARGIN - INSET).toInt()
        },
    )

    /** How far inside a panel's own edge a sample is taken, in pixels. */
    private const val INSET: Float = 2f

    /**
     * Each panel [png] should show and does not, as a sentence naming the pixel and both colours;
     * empty when every panel is there. [what] names the picture in each sentence.
     */
    fun missingFrom(what: String, png: ByteArray): List<String> {
        val image = ImageIO.read(ByteArrayInputStream(png)) ?: return listOf("$what is not a decodable image")
        return PANELS.mapNotNull { panel ->
            val (x, y) = panel.at(image.height)
            val rgb = image.getRGB(x, y) and RGB_MASK
            if (rgb == panel.rgb) {
                null
            } else {
                "%s has no %s: (%d, %d) is #%06X, the panel is #%06X".format(what, panel.name, x, y, rgb, panel.rgb)
            }
        }
    }

    /** The alpha channel `getRGB` returns is not part of a panel's declared colour. */
    private const val RGB_MASK: Int = 0xFFFFFF
}
