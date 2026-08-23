package dev.wildware.udea.agent.host.overlay

/**
 * The whole of what the agent activity overlay needs in order to draw itself (spec 3.7).
 *
 * ## Why a port and not a `Batch`
 *
 * `udea-agent-host` is in `ModuleGraphRules.HEADLESS_PROJECTS`: `UDEA-MG-002` keeps a GL backend
 * off its classpath, and `udeaVerifyHeadless` fails the build if any class compiled here so much
 * as *names* `com/badlogic/gdx/graphics/`. `Batch`, `BitmapFont` and `ShapeRenderer` all live
 * there. So the overlay's layout, its colour scheme, its pre-formatting and its marker
 * arithmetic - everything with a decision in it - live here and are unit-testable with no
 * display, and the six primitives below are what a GL adapter has to supply.
 *
 * This is the same seam, and the same reasoning, as
 * [dev.wildware.udea.agent.host.RenderControl]: the port is declared in the module that decides
 * *what* happens, and the pixels are `udea-render`'s.
 *
 * **The adapter is not written yet.** No class in the tree implements this against GL, exactly
 * as no class implements `RenderControl` against GL, and for the same reason: it belongs in
 * `udea-render`, wrapped in an `OverlaySystem` so that it is handed an `OverlayResources` and
 * therefore structurally cannot reach a capturable target. `OverlayCaptureIsolationTest` drives
 * a real GL adapter over this port from `udea-agent-host`'s **test** source set, which is where
 * the headless rule stops applying, so the port is proven sufficient and the shipped overlay
 * code is proven not to reach a capture - but the production adapter still has to be moved into
 * `udea-render` by whoever owns that module.
 *
 * ## Coordinates
 *
 * Pixels, origin at the **bottom left**, matching GL and matching
 * [dev.wildware.udea.agent.host.PixelRegion]. Not top-left: a panel pinned to the top of the
 * window is then `height - margin`, computed once, rather than every marker's y being flipped
 * at the point it is drawn - which is where a sign error hides.
 *
 * ## Colours
 *
 * One packed `0xRRGGBBAA` `Int` per call, not a colour object. A marker is drawn per anchored
 * call per frame; an object per draw would be presentation-thread garbage sixty times a second,
 * which is the same reason `RenderPipeline` walks its lists by index.
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

    /** Fills a rectangle. The panel's own background, and nothing else. */
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
}
