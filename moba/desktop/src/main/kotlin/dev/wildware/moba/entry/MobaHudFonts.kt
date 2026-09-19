package dev.wildware.moba.entry

import dev.wildware.composegl.ui.text.DEFAULT_FAMILY
import dev.wildware.moba.HUD_FONT_SIZES
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiFonts

/**
 * The desktop's fonts for the HUD: DejaVu Sans, bundled with this launcher, as the toolkit's default
 * family, at every size the HUD sets text in.
 *
 * Bundled rather than read from the machine, so a capture looks the same on every desktop and on a
 * display-less box under xvfb - `MatchShot`'s pictures are compared by a person. The HUD owns what this
 * returns and closes it with the pipeline (see `MobaHudSystem`).
 *
 * @throws IllegalStateException when the font is missing from the jar: a packaging defect, and one a
 *   HUD with its panels drawn and no text on them would otherwise hide.
 */
internal fun mobaHudFonts(): UiFonts {
    val bytes = checkNotNull(MobaHudFontsMarker::class.java.getResourceAsStream(FONT)) {
        "$FONT is missing from moba:desktop's resources"
    }.use { it.readBytes() }
    return DesktopFonts().apply { register(DEFAULT_FAMILY, bytes, HUD_FONT_SIZES) }
}

/** An anchor for the resource lookup: a class this module owns, so the path resolves against its jar. */
private object MobaHudFontsMarker

/** Beside this package's classes in the jar. Its licence is the text file next to it. */
private const val FONT: String = "/dev/wildware/moba/entry/DejaVuSans.ttf"
