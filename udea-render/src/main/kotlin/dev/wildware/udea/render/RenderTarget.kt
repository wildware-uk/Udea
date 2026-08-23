package dev.wildware.udea.render

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable
import dev.wildware.udea.render.capture.PixelSource

/**
 * Somewhere a frame can be drawn.
 *
 * The hierarchy is sealed and has exactly two cases because the difference between them is
 * a correctness requirement, not a detail (spec 3.7). A frame capture reads
 * [OffscreenTarget]; nothing ever captures a [ScreenTarget]. So a system that must not
 * appear in a screenshot -- the agent activity overlay -- is simply never handed a target
 * that can be captured, and no amount of refactoring can hand it one by accident.
 *
 * The alternative, "remember to switch the overlay off before capturing", is the version
 * that gets forgotten and then silently corrupts every visual diff an agent does: the agent
 * would see its own narration change between two captures and conclude the *game* had
 * changed.
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
 * The capturable target: world, UI and debug drawing land here, and `FrameCapture` (agent
 * epic) reads it.
 *
 * Everything drawn into this target is, by definition, something an agent is allowed to see
 * -- it is the game. See [RenderTarget] for why that is a type and not a convention.
 */
public class OffscreenTarget internal constructor(
    override val width: Int,
    override val height: Int,
) : RenderTarget {

    override fun toString(): String = "OffscreenTarget(${width}x$height)"
}

/**
 * The never-captured target: the human's window, after the captured frame has been blitted
 * to it.
 *
 * Only an [OverlaySystem] draws here, and only in `RenderMode.Windowed`. Because a capture
 * never reads this target, overlay pixels cannot reach an agent.
 */
public class ScreenTarget internal constructor(
    width: Int,
    height: Int,
) : RenderTarget {

    /**
     * Current window width. A `var` with an internal setter because this target *is* the
     * window: a human dragging an edge changes it, and an overlay laying itself out against a
     * size captured at startup would drift off the screen. [OffscreenTarget] is deliberately
     * the opposite — its size is the framebuffer's and never moves, which is what keeps two
     * captures comparable.
     */
    override var width: Int = width
        internal set

    /** Current window height. See [width]. */
    override var height: Int = height
        internal set

    override fun toString(): String = "ScreenTarget(${width}x$height)"
}

/**
 * The pair of targets a [RenderPipeline] draws into, plus the GL resources it owns.
 *
 * ## Why the resources live here
 *
 * `GameScreen` in the old tree constructed a `SpriteBatch` and a `ShapeRenderer` in its own
 * constructor (`common/UdeaGameManager.kt:143-144`) and then *two more* batches appeared
 * because `BackgroundDrawSystem` and `DebugDrawSystem` each built their own. Three batches,
 * three lifetimes, and disposal spread across whoever remembered. Here there is one set,
 * constructed once by whatever brings GL up, handed to the pipeline, and disposed by
 * [RenderPipeline.dispose] in reverse construction order.
 *
 * [owned] is typed as LibGDX's [Disposable] rather than something invented here because
 * `SpriteBatch` and `ShapeRenderer` already implement it, and it is a pure interface with no
 * GL of its own -- so this file, and the tests that drive it, need no GL context.
 *
 * The constructor is `internal`: the LWJGL3 bootstrap (separate issue) is the only thing
 * that should be building targets, because it is the only thing that knows a GL context
 * exists.
 */
public class RenderTargets internal constructor(
    /** Where the game is drawn, and the only thing a capture may read. */
    public val offscreen: OffscreenTarget,
    /** Where the human's window is drawn, after the capture. Never read by a capture. */
    public val screen: ScreenTarget,
    /**
     * The one sprite batch, shared by every renderer and by the blit.
     *
     * Typed as LibGDX's `Batch` **interface** rather than `SpriteBatch`, which is what makes
     * every ported drawing system testable with no GL context at all: a test hands the
     * pipeline a recording batch and asserts what was drawn, where, and in what order.
     *
     * One, not three. `GameScreen` constructed a batch (`UdeaGameManager.kt:143`) and then
     * `BackgroundDrawSystem.kt:23` and `DebugDrawSystem.kt:26` each constructed another, so a
     * frame flushed three separate vertex buffers and disposal was wherever somebody
     * remembered.
     */
    public val batch: Batch,
    /**
     * Binds [offscreen] before the frame and blits it to [screen] afterwards.
     *
     * [FrameSurface.None] for a pipeline with no framebuffer behind it -- the ordering tests,
     * and any host that draws straight to the window. The LWJGL3 backend passes a real one.
     */
    internal val surface: FrameSurface = FrameSurface.None,
    /**
     * How a capture reads pixels back, or `null` when this pipeline cannot be captured.
     *
     * `null` is what makes `GameHost.screenshot` answer `no_capture_backend` instead of
     * returning a blank image: a pipeline with no way to read pixels says so.
     */
    internal val pixels: PixelSource? = null,
    /**
     * GL resources whose lifetime the pipeline owns, in construction order.
     *
     * Disposed by [RenderPipeline.dispose] in reverse.
     */
    internal val owned: List<Disposable> = emptyList(),
) {

    override fun toString(): String = "RenderTargets(offscreen=$offscreen, screen=$screen, " +
        "surface=$surface, capturable=${pixels != null}, owned=${owned.size})"
}
