package dev.wildware.udea.render

/**
 * The only module that touches GL (spec 4). RenderSystem implementations live here and
 * are deliberately not Fleks systems, so world.update(dt) is pure simulation by
 * construction. Seconds and interpolation alpha exist here; ticks are the currency
 * everywhere else.
 *
 * This is the only module that may apply udea.kotlin-library-gl.
 *
 * This object is a placeholder so the module has a source root and appears in an IDE
 * sync. Later Phase 0 waves replace it with the real declarations.
 */
internal object RenderModule
