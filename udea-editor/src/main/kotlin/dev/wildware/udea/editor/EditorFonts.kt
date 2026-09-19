package dev.wildware.udea.editor

import dev.wildware.composegl.ui.text.DEFAULT_FAMILY
import dev.wildware.udea.render.ui.DesktopFonts

/**
 * The editor window's fonts: DejaVu Sans, bundled with this module, as the toolkit's default family.
 *
 * Bundled rather than read from the machine, so the window looks the same on every desktop and on a
 * display-less box under xvfb. Registered under `DEFAULT_FAMILY` because the panels are the toolkit's
 * own widgets in its default skin, which asks for that family by name.
 *
 * The caller owns what this returns and closes it after the `UiLayer` that draws with it.
 *
 * @throws IllegalStateException when the font is missing from the jar - a packaging defect, and
 *   one a window with no text in it would otherwise hide.
 */
public fun editorFonts(): DesktopFonts {
    val bytes = checkNotNull(EditorWindowMarker::class.java.getResourceAsStream(FONT)) {
        "$FONT is missing from udea-editor's resources"
    }.use { it.readBytes() }
    return DesktopFonts().apply { register(DEFAULT_FAMILY, bytes, SIZES) }
}

/** An anchor for the resource lookup: a class this module owns, so the path resolves against its jar. */
private object EditorWindowMarker

/** Beside this package's classes in the jar. */
private const val FONT: String = "/dev/wildware/udea/editor/DejaVuSans.ttf"

/**
 * The sizes ComposeGL 0.7.0-SNAPSHOT's skins set text at, read out of their JSON when this was
 * written. A style asking for a size missing here fails with `no font for default at N` the first
 * time it draws, loudly, rather than drawing something else.
 */
private val SIZES: List<Int> = listOf(11, 12, 13, 14, 15, 16, 18, 22, 26)
