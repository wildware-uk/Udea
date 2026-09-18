package dev.wildware.udea.render.overlay

/**
 * What [AgentOverlaySystem] draws, agent-agnostic.
 *
 * ## Why this seam exists
 *
 * `udea-render` may not name a `udea-agent-host` type (the module arrow points the other way:
 * `udea-agent-host` depends on `udea-render`, never the reverse), so [AgentOverlaySystem] cannot
 * take `dev.wildware.udea.agent.host.overlay.AgentOverlayView` directly - that would be exactly
 * the upward arrow the module graph forbids. This interface is the whole of what the system
 * needs to draw a frame, and a composition root adapts its real content into one:
 *
 * ```
 * registry.overlay { resources ->
 *     AgentOverlaySystem(resources, OverlayContent { canvas, dt, projector, locator ->
 *         view.render(canvas, dt, projector, locator)
 *     })
 * }
 * ```
 *
 * A `fun interface` rather than a plain function type so the lambda above reads as "this is the
 * overlay's content" at the call site rather than as an anonymous four-argument callback.
 */
public fun interface OverlayContent {

    /**
     * Draws one frame of overlay content into [canvas].
     *
     * @param dtSeconds wall-clock seconds since the previous frame. Never simulation time.
     * @param projector world to screen, for anything anchored to the world.
     * @param locator where an anchored entity is now.
     */
    public fun render(
        canvas: OverlayCanvas,
        dtSeconds: Float,
        projector: WorldProjector,
        locator: EntityLocator,
    )
}
