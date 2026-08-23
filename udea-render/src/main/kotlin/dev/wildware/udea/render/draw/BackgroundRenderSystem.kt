package dev.wildware.udea.render.draw

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderSystem

/**
 * Fills the frame with a background image, before anything else draws.
 *
 * The port of `BackgroundDrawSystem` (`common/ecs/system/BackgroundDrawSystem.kt`), which is
 * three lines of drawing wrapped around two problems:
 *
 * - it constructed its **own** `SpriteBatch` in a field initialiser (`:23`), so a frame ran
 *   three batches and disposal was nobody's. It takes the shared one now, and does not own it.
 * - it sized itself with `Gdx.graphics.width/height` (`:32`), reading the *window* from inside
 *   a draw call. The background is drawn into the offscreen framebuffer, not the window, so on
 *   any frame where those differ — which is every frame, since the framebuffer is deliberately
 *   fixed-size — it stretched wrong or left a gap. The size comes from the [OffscreenTarget]
 *   here, which is the surface actually being drawn on.
 *
 * It is a [RenderPhase.PreRender] system and it does not clear: clearing belongs to
 * [dev.wildware.udea.render.FrameSurface.begin], which happens once for the frame whether a
 * background exists or not.
 */
public class BackgroundRenderSystem(
    private val resources: RenderResources,
    /**
     * The image to fill with, or `null` for none.
     *
     * A `var` because the background changes with the scene, and a `by lazy` reading a global
     * config — which is what the original did (`BackgroundDrawSystem.kt:16`) — resolves once
     * per JVM and then shows the first level's sky forever.
     */
    public var background: TextureRegion? = null,
) : RenderSystem {

    /**
     * Reused, and assigned to the batch rather than mutated in place.
     *
     * `batch.projectionMatrix.setToOrtho2D(...)` compiles and does nothing useful: a
     * `SpriteBatch` copies the matrix into its shader when it is *set*, so mutating the one it
     * hands back leaves the shader on the previous frame's projection. It is the sort of
     * mistake that shows up as "the background is in the wrong place only on the first frame".
     */
    private val projection = Matrix4()

    /** Frames on which a background was actually drawn. What `DrawSystemPortTest` counts. */
    public var drawnCount: Long = 0L
        private set

    override fun onBind(world: World, ctx: GameContext): Unit = Unit

    override fun render(target: OffscreenTarget, alpha: Float) {
        val image = background ?: return

        val batch = resources.batch
        // Screen space, not world space: the background does not scroll with the camera, so it
        // is projected onto the target's own pixels rather than the camera's world units.
        projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
        batch.projectionMatrix = projection
        batch.begin()
        try {
            batch.draw(image, 0f, 0f, target.width.toFloat(), target.height.toFloat())
            drawnCount++
        } finally {
            batch.end()
        }
    }
}
