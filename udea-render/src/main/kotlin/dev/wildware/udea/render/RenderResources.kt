package dev.wildware.udea.render

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable

/**
 * What a [RenderSystem]'s constructor is handed: the shared batch, the surface it draws on, and
 * somewhere to hand a resource it allocates.
 *
 * ## Why the factory takes this instead of the systems making their own
 *
 * A drawing system needs a `Batch`, and a `Batch` needs a GL context, and a GL context does not
 * exist until the render thread is up — after the game has already declared what it draws. The
 * old tree's answer was for each system to construct its own in a field initialiser
 * (`BackgroundDrawSystem.kt:23`, `DebugDrawSystem.kt:26`), which produced three batches, three
 * lifetimes, and a `VisUI.getSkin()` call at class-initialisation time that crashed whenever a
 * skin had not been loaded yet (`DebugDrawSystem.kt:25`).
 *
 * Here the registration is a factory, and the factory runs on the render thread with this in
 * hand. A system takes what it needs as constructor parameters, so a missing dependency is a
 * compile error at the registration site rather than a null field several frames later.
 *
 * ## What is deliberately missing
 *
 * The [ScreenTarget]. A [RenderSystem] draws into [offscreen] and has no business holding a
 * reference to the surface an [OverlaySystem] draws on — handing it one would be handing back
 * the very thing the type split in spec 3.7 takes away.
 *
 * ## And the mirror of that, which is the load-bearing half
 *
 * An [OverlaySystem]'s factory is **not** handed one of these. It gets [OverlayResources],
 * which carries the [ScreenTarget] and no capturable target at all. That is not tidiness: for
 * one wave this class was the argument to `RenderRegistry.overlay` as well, so an overlay could
 * hold the [OffscreenTarget], size itself to the capture and read the shared batch — and the
 * spec 3.7 guarantee was back to being frame ordering plus a separate FBO, which is the
 * "remember to do it in the right order" arrangement the type split exists to replace.
 * `OverlayResourcesTest` asserts the reachability, so a field added back here cannot leak into
 * the overlay side unnoticed.
 */
public class RenderResources internal constructor(
    /** The one batch for the frame. See [RenderTargets.batch] for why it is the interface. */
    public val batch: Batch,
    /** The capturable surface this pipeline draws into, so a system can size itself to it. */
    public val offscreen: OffscreenTarget,
) {

    private val extra = ArrayList<Disposable>()

    /**
     * Registers [resource] for disposal with the pipeline, and returns it.
     *
     * For the things a system genuinely has to allocate for itself — a font, a shader, a
     * scratch framebuffer. Disposed in reverse construction order along with everything else
     * the pipeline owns, which is the property that made the old tree's three-batches-and-hope
     * arrangement impossible to reason about.
     *
     * ```
     * private val font = resources.own(BitmapFont())
     * ```
     */
    public fun <T : Disposable> own(resource: T): T {
        extra += resource
        return resource
    }

    /** Everything registered through [own], in construction order. */
    internal fun owned(): List<Disposable> = extra.toList()

    override fun toString(): String = "RenderResources($offscreen, owns ${extra.size})"
}

/**
 * What an [OverlaySystem]'s constructor is handed: the shared batch, the never-captured
 * [ScreenTarget], and somewhere to hand a resource it allocates.
 *
 * ## Why this is a separate type from [RenderResources]
 *
 * Spec 3.7's guarantee is *structural*: "an [OverlaySystem] is never handed a capturable
 * target, so it cannot reach one". A single resources type passed to both factories defeats
 * that outright — the overlay side would hold an [OffscreenTarget] through a side channel, and
 * the flag-you-can-forget would be back in another shape. There is deliberately **no**
 * `offscreen` here, no `RenderTargets`, and no route from [screen] to a capturable target.
 *
 * The [batch] is shared, and that is fine: a batch is a vertex buffer, not a surface. What a
 * draw call lands on is decided by what is bound at the time, and by the time an overlay draws
 * the offscreen framebuffer has been unbound, the capture has already been read, and the frame
 * has been blitted to the window.
 */
public class OverlayResources internal constructor(
    /** The one batch for the frame. See [RenderTargets.batch] for why it is the interface. */
    public val batch: Batch,
    /**
     * The window an overlay draws on, and the one target no capture ever reads.
     *
     * A `var`-sized target on purpose: the human can drag the window edge, and an overlay laid
     * out against a size captured at startup would drift off the screen.
     */
    public val screen: ScreenTarget,
) {

    private val extra = ArrayList<Disposable>()

    /**
     * Registers [resource] for disposal with the pipeline, and returns it.
     *
     * The same contract as [RenderResources.own]: reverse construction order, alongside
     * everything else the pipeline owns.
     */
    public fun <T : Disposable> own(resource: T): T {
        extra += resource
        return resource
    }

    /** Everything registered through [own], in construction order. */
    internal fun owned(): List<Disposable> = extra.toList()

    override fun toString(): String = "OverlayResources($screen, owns ${extra.size})"
}
