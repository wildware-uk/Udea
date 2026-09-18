package dev.wildware.udea.render.overlay

/**
 * The whole of what a screen-space overlay needs in order to draw itself (spec 3.7).
 *
 * ## Why a port and not a batch
 *
 * This lives in `udea-render` because the six primitives below are exactly the ones
 * [dev.wildware.udea.render.draw.SpriteBatch2D] and [dev.wildware.udea.render.draw.BitmapFont2D]
 * already give a Kool scene, and every module that draws an overlay - today the agent activity
 * overlay, moved here by issue #211 - wants the same six and nothing GL-specific. Keeping the
 * port agent-agnostic is what lets `udea-agent-host` describe *what* an overlay says (issue #159,
 * #160) with no `udea-render` type in its own decisions, while the *pixels* are drawn here by
 * [dev.wildware.udea.render.overlay.AgentOverlaySystem].
 *
 * ## Coordinates
 *
 * Pixels, origin at the **bottom left**, matching every other target in this module. Not
 * top-left: a panel pinned to the top of the window is then `height - margin`, computed once,
 * rather than every marker's y being flipped at the point it is drawn - which is where a sign
 * error hides.
 *
 * ## Colours
 *
 * One packed `0xRRGGBBAA` `Int` per call, not a colour object. A marker is drawn per anchored
 * call per frame; an object per draw would be presentation-thread garbage sixty times a second,
 * which is the same reason [dev.wildware.udea.render.RenderPipeline] walks its lists by index.
 */
public interface OverlayCanvas {

    /** Window width in pixels. Changes when the human drags the window edge. */
    public val width: Float

    /** Window height in pixels. */
    public val height: Float

    /** Baseline-to-baseline distance for [text], in pixels. */
    public val lineHeight: Float

    /** How wide [text] would be, in pixels. For sizing a panel to its contents. */
    public fun measure(text: CharSequence): Float

    /** Fills a rectangle. A panel's own background, and nothing else. */
    public fun fill(x: Float, y: Float, w: Float, h: Float, rgba: Int)

    /** Draws [text] with its left edge at [x] and its **baseline** at [y]. */
    public fun text(x: Float, y: Float, text: CharSequence, rgba: Int)

    /**
     * Draws an unfilled circle centred on [cx], [cy].
     *
     * Unfilled deliberately: a marker rings the thing it is about and must not hide it. A human
     * watching an entity the agent just inspected needs to see the entity.
     */
    public fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, rgba: Int)

    /**
     * Draws a cross centred on [x], [y], [size] pixels across.
     *
     * A different *shape* from [ring], not a different colour, because the two answer different
     * questions - "which entity" versus "which point" - and a human should not have to remember
     * a colour key to tell them apart. Colour is spent on the session and on read-versus-write,
     * which is two dimensions already.
     */
    public fun cross(x: Float, y: Float, size: Float, thickness: Float, rgba: Int)
}

/**
 * Where a world position lands on the human's screen.
 *
 * Separate from [OverlayCanvas] because it is the camera's business and not the batch's: the
 * same canvas serves a panel that is laid out in screen pixels and a marker that is not.
 *
 * Returning a `Boolean` rather than clamping is the point. A marker whose entity has walked off
 * screen must draw **nothing**; clamping would pin it to the window edge, and a human would read
 * that as "the agent is looking at something at the edge of the map".
 */
public fun interface WorldProjector {

    /**
     * Projects a world point to screen pixels.
     *
     * @param out a two-slot array the caller owns and reuses, `[x, y]` in the same
     *   bottom-left pixel space as [OverlayCanvas]. Taking the array rather than returning a
     *   pair is what keeps the per-frame marker pass allocation-free.
     * @return `false` when the point is not on screen, or when there is no camera yet.
     */
    public fun project(worldX: Float, worldY: Float, out: FloatArray): Boolean

    public companion object {
        /**
         * A projector that puts nothing on screen.
         *
         * The honest default for a host with no camera wired: a panel and no markers, rather
         * than markers drawn at the origin.
         */
        public val NONE: WorldProjector = WorldProjector { _, _, _ -> false }
    }
}
