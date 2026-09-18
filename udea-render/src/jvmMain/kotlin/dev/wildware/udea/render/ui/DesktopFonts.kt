package dev.wildware.udea.render.ui

import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.render.AtlasFonts

/**
 * The desktop's [UiFonts]: `.ttf` faces rasterised by stb_truetype into the toolkit's glyph atlas.
 *
 * ```kotlin
 * DesktopFonts().use { fonts ->
 *     fonts.register("default", File("DejaVuSans.ttf").readBytes(), listOf(16, 24))
 *     backend.show(UiLayer(fonts, Size(1280f, 720f)))
 * }
 * ```
 *
 * Desktop only, because the rasteriser is: Android's glyphs come from its own `Typeface` inside
 * `composegl-kool`, which is not reachable from here, so an Android [UiFonts] is a separate class
 * and not this one behaving differently.
 *
 * ## A screen with no text still needs one
 *
 * The toolkit samples solid colour from the atlas's white block, so a panel of flat colour is drawn
 * through these as surely as a label is - and `StbFonts` refuses to prepare an atlas with no family
 * registered at all (`no fonts were registered`). So [register] is not optional decoration; an
 * interface with nothing registered draws nothing and says why.
 */
public class DesktopFonts : UiFonts() {

    private val stb = StbFonts()

    override val atlas: AtlasFonts get() = stb

    override fun register(family: String, ttf: ByteArray, sizes: List<Int>) {
        stb.register(family, ttf, sizes)
    }

    override fun close(): Unit = stb.close()

    override fun toString(): String = "DesktopFonts(stb_truetype)"
}
