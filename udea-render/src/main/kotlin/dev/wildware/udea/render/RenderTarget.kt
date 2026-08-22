package dev.wildware.udea.render

import com.badlogic.gdx.utils.Disposable

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
    override val width: Int,
    override val height: Int,
) : RenderTarget {

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
     * GL resources whose lifetime the pipeline owns, in construction order.
     *
     * Empty until the drawing ports land; the ordering and disposal behaviour is already
     * exercised, so the batch inherits it rather than introducing it.
     */
    internal val owned: List<Disposable> = emptyList(),
) {

    override fun toString(): String =
        "RenderTargets(offscreen=$offscreen, screen=$screen, owned=${owned.size})"
}
