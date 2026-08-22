package dev.wildware.udea.render

/**
 * The coarse ordering of a frame. Phase ordinal is the outer sort key of a
 * [RenderRegistry]; `before`/`after` constraints only order systems *within* one phase.
 *
 * Two levels rather than one because they answer different questions. "Debug shapes go on
 * top of sprites" is a property of the engine and should not need a constraint at every
 * registration site; "this renderer must run after that one" is a property of two particular
 * systems and cannot be inferred.
 *
 * ## Why [Overlay] is last, and not third
 *
 * The frame has a capture point in it. [PreRender] through [Debug] draw into the
 * [OffscreenTarget]; `FrameCapture` (agent epic) reads that target; the result is blitted to
 * the window; only then does [Overlay] draw, onto the [ScreenTarget]. An [OverlaySystem]
 * therefore runs after everything a capture can see, and is handed a target no capture reads
 * (spec 3.7).
 *
 * [Debug] sits *before* the capture on purpose: debug shapes are information about the game
 * world, and an agent asking for a screenshot should get them. The overlay is information
 * about the *agent*, which is exactly what it must not be shown.
 */
public enum class RenderPhase {

    /** Frame setup: clears, camera update, render-target binding. Draws little or nothing. */
    PreRender,

    /** The simulated world: terrain, sprites, effects. The bulk of every frame. */
    World,

    /** Screen-space game UI: health bars, minimap, ability cooldowns. Part of the game. */
    UI,

    /** Developer visualisation: collision shapes, paths, spawn markers. Capturable. */
    Debug,

    /**
     * The agent activity overlay, and nothing else. Runs after the capture point and draws
     * onto the [ScreenTarget], so it can never reach a capture.
     *
     * Systems in this phase are [OverlaySystem]s, registered with [RenderRegistry.overlay];
     * [RenderRegistry.register] rejects this phase because a [RenderSystem] here would be
     * handed a capturable target.
     */
    Overlay,
    ;

    /** True when systems in this phase draw before the capture point, into the offscreen target. */
    internal val isCapturable: Boolean get() = this != Overlay
}
