package dev.wildware.udea.render.sky

import kotlin.concurrent.Volatile

/**
 * The sky a game is drawn over, changeable while it runs (issue #267).
 *
 * Every [dev.wildware.udea.render.RenderRegistry] has one, as `registry.sky`. It is on the registry
 * rather than on the window's configuration because a window is configured once and a game has day
 * maps and night maps: a level loader, a scene switch or a render system reading the world sets
 * [background], and the next frame drawn shows it.
 *
 * ```kotlin
 * registry.sky.background = SkyBackground.Gradient(top = Rgba.of(0.26f, 0.46f, 0.76f), bottom = Rgba.of(0.82f, 0.87f, 0.86f))
 * ```
 *
 * The sky is drawn first in every frame, before any `RenderSystem`, so everything a game draws is
 * drawn over it; a 3D model pass, which clears to transparent, shows it wherever no model is.
 */
public class Sky internal constructor() {

    /**
     * What is drawn behind everything, from the next frame on. [SkyBackground.None] until a game
     * says otherwise.
     *
     * Safe to set from any thread. The value is immutable and the render thread reads it once at
     * the top of each frame, so `@Volatile` is all the hand-over there is: a write lands whole on
     * the next frame, never half-way through one, and setting it touches nothing of the renderer's.
     */
    @Volatile
    public var background: SkyBackground = SkyBackground.None

    override fun toString(): String = "Sky($background)"
}
