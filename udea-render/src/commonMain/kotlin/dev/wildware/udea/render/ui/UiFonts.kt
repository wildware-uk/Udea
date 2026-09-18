package dev.wildware.udea.render.ui

import dev.wildware.composegl.render.AtlasFonts

/**
 * The glyphs a [UiLayer] draws with, as a game holds them.
 *
 * It exists to keep one type off this module's public surface. The toolkit measures text through
 * `composegl-render`'s `AtlasFonts`, and `composegl-render` is a renderer - `UDEA-MG-002` keeps it
 * and every other ComposeGL frontend inside `udea-render`, and `composegl-kool` is an
 * `implementation` dependency here, so a game could not name `AtlasFonts` even to pass one in. A
 * game holds this instead and never sees what is behind it.
 *
 * The constructor is `internal`, so this module supplies every implementation. That is the rule
 * rather than a restriction worth apologising for: making an atlas means naming a rasteriser, and a
 * rasteriser is a frontend. [DesktopFonts] is the desktop's.
 */
public abstract class UiFonts internal constructor() : AutoCloseable {

    /** What the frontend is handed. Never crosses this module's boundary. */
    internal abstract val atlas: AtlasFonts

    /**
     * Registers [family] from the bytes of a `.ttf`, at each of [sizes] in pixels.
     *
     * Every size a screen draws at has to be registered, because faces are rasterised before the
     * first frame rather than during one. A style asking for a size nothing was registered at is an
     * error that says so.
     */
    public abstract fun register(family: String, ttf: ByteArray, sizes: List<Int>)
}
