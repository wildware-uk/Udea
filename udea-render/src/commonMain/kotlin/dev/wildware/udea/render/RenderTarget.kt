package dev.wildware.udea.render

import dev.wildware.udea.render.capture.PixelSource
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.kool.GlScreenPasses
import dev.wildware.udea.render.kool.ScenePasses

/**
 * Somewhere a frame can be drawn.
 *
 * The hierarchy is sealed and has exactly two cases because the difference between them is
 * a correctness requirement, not a detail (spec 3.7). A frame capture reads
 * [OffscreenTarget]; nothing ever captures a [ScreenTarget]. So a system that must not
 * appear in a screenshot -- the agent activity overlay -- is simply never handed a target
 * that can be captured, and no amount of refactoring can hand it one by accident.
 *
 * Both constructors are `internal`. Only this module builds targets, which is what stops a
 * consumer from manufacturing an [OffscreenTarget] and handing it to an overlay.
 */
public sealed interface RenderTarget {

    /** Width of the drawable area in pixels. */
    public val width: Int

    /** Height of the drawable area in pixels. */
    public val height: Int
}

/**
 * The capturable target: world, UI and debug drawing land here, and a frame capture reads it.
 *
 * Everything drawn into this target is, by definition, something an agent is allowed to see
 * -- it is the game.
 */
public class OffscreenTarget internal constructor(
    override val width: Int,
    override val height: Int,
) : RenderTarget {

    override fun toString(): String = "OffscreenTarget(${width}x$height)"
}

/**
 * The never-captured target: the human's window, after the captured frame has been presented on
 * it.
 *
 * Only an [OverlaySystem] draws here, and only in `RenderMode.Windowed`. Because a capture never
 * reads this target, overlay pixels cannot reach an agent.
 */
public class ScreenTarget internal constructor(
    width: Int,
    height: Int,
) : RenderTarget {

    /**
     * Current window width. A `var` with an internal setter because this target *is* the window: a
     * human dragging an edge changes it. [OffscreenTarget] is deliberately the opposite — its size
     * is the capturable pass's and never moves, which is what keeps two captures comparable.
     */
    override var width: Int = width
        internal set

    /** Current window height. See [width]. */
    override var height: Int = height
        internal set

    override fun toString(): String = "ScreenTarget(${width}x$height)"
}

/**
 * The pair of targets a [RenderPipeline] draws into, the two batches that reach them, and the
 * resources the pipeline owns.
 *
 * ## Two batches, not one
 *
 * [batch] records what [RenderSystem]s draw and reaches only the capturable pass; [screenBatch]
 * records what [OverlaySystem]s draw and reaches only the window. Under LibGDX one batch served
 * both, and the overlay stayed out of captures because the framebuffer had been unbound by the time
 * it drew - an ordering fact. On Kool a batch is bound to a render pass by the scene graph, so
 * handing each side its own batch makes the exclusion a fact about which object a system holds.
 *
 * The constructor is `internal`: the backend is the only thing that should be building targets,
 * because it is the only thing that knows a render context exists.
 */
public class RenderTargets internal constructor(
    offscreen: OffscreenTarget,
    /** Where the human's window is drawn, after the capture. Never read by a capture. */
    public val screen: ScreenTarget,
    /** The batch every [RenderSystem] draws with. Reaches the capturable pass and nothing else. */
    internal val batch: SpriteBatch2D,
    /** The batch every [OverlaySystem] draws with. Reaches the window and nothing else. */
    internal val screenBatch: SpriteBatch2D,
    /**
     * Clears the batches at the frame's edges and presents the capturable frame on the window.
     *
     * [FrameSurface.None] for a pipeline with nothing behind it -- the ordering tests.
     */
    internal val surface: FrameSurface = FrameSurface.None,
    /**
     * How a capture reads pixels back, or `null` when this pipeline cannot be captured.
     *
     * `null` is what makes a screenshot answer `no_capture_backend` instead of returning a blank
     * image: a pipeline with no way to read pixels says so.
     */
    internal val pixels: PixelSource? = null,
    /** Resources whose lifetime the pipeline owns, in construction order. Released in reverse. */
    internal val owned: List<RenderResource> = emptyList(),
    /** Where a system adds a pass of its own, or `null` when there is no Kool scene behind this. */
    internal val passes: ScenePasses? = null,
    /**
     * The game's screen effects (issues #259, #266), or `null` when there is no Kool scene behind
     * this pipeline - the ordering tests, which draw nothing and so have nothing to process.
     */
    internal val screenPasses: GlScreenPasses? = null,
) {

    /**
     * Where the game is drawn, and the only thing a capture may read.
     *
     * Its size is fixed for the life of a game, with one exception: an editor's Game tab asks for
     * the frame to be the size of the tab (issue #234), and the pipeline replaces this at the top of
     * a frame, before anything draws. A window being dragged never changes it.
     */
    public var offscreen: OffscreenTarget = offscreen
        internal set

    override fun toString(): String = "RenderTargets(offscreen=$offscreen, screen=$screen, " +
        "surface=$surface, capturable=${pixels != null}, owned=${owned.size})"
}
