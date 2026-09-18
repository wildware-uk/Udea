package dev.wildware.udea.render

/**
 * Something the renderer allocated and has to give back: a texture, a mesh, the pass a frame is
 * drawn into.
 *
 * Udea's own type rather than Kool's `Releasable`, because it appears on this module's public
 * surface - [RenderResources.own] takes one - and Kool is an `implementation` dependency: a
 * consumer that registers a resource must not need Kool on its compile classpath to do it. The
 * shape is the one LibGDX's `Disposable` had here before issue #211, so the ownership rules did
 * not move: [RenderPipeline.dispose] releases everything it owns, once, in reverse construction
 * order.
 */
public fun interface RenderResource {

    /** Gives the resource back. Called at most once, on the render thread. */
    public fun release()
}
