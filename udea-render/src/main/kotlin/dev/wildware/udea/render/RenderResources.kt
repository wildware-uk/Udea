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
