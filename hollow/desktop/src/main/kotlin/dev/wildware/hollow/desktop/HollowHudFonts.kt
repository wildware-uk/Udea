package dev.wildware.hollow.desktop

import dev.wildware.composegl.ui.text.DEFAULT_FAMILY
import dev.wildware.hollow.render.HOLLOW_HUD_FONT_SIZES
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiFonts

/**
 * The desktop's fonts for Hollow's HUD: DejaVu Sans, bundled with this launcher, as the toolkit's
 * default family, at every size the HUD sets text in (issue #252).
 *
 * Bundled rather than read from the machine, so a capture looks the same on every desktop and on a
 * display-less box under xvfb - the fight shot's pictures are compared by a person. `moba`'s
 * `mobaHudFonts` is the same function for the same reason, and the two are separate because a
 * launcher's resources are its own jar's: neither module is on the other's classpath, and a game
 * outside this repository has neither.
 *
 * The HUD owns what this returns and closes it with the pipeline (see `HollowHudSystem`).
 *
 * @throws IllegalStateException when the font is missing from the jar: a packaging defect, and one
 *   a HUD with its panels drawn and no text on them would otherwise hide.
 */
internal fun hollowHudFonts(): UiFonts {
    val bytes = checkNotNull(HollowHudFontsMarker::class.java.getResourceAsStream(FONT)) {
        "$FONT is missing from hollow:desktop's resources"
    }.use { it.readBytes() }
    return DesktopFonts().apply { register(DEFAULT_FAMILY, bytes, HOLLOW_HUD_FONT_SIZES) }
}

/** An anchor for the resource lookup: a class this module owns, so the path resolves against its jar. */
private object HollowHudFontsMarker

/** Beside this package's classes in the jar. Its licence is the text file next to it. */
private const val FONT: String = "/dev/wildware/hollow/desktop/DejaVuSans.ttf"
