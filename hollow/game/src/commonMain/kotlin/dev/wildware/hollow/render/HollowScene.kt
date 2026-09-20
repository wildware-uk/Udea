package dev.wildware.hollow.render

import dev.wildware.hollow.Prop
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelSource

/**
 * What Hollow draws, in order: the [SkySystem], then [ScenerySystem] bringing the models and the
 * sun up to date, then `udea-render`'s `ModelRenderSystem` drawing every model lit by the sun with
 * its shadows. Every launcher that draws registers this, so the window and the shot show one scene.
 *
 * The camera is fixed in H1 (issue #249): inside the ring on its near side, above head height,
 * looking across the middle at the far trees with the sky above them. The
 * third-person rig of issue #248 replaces it in issue #250.
 *
 * @param models the model each prop is drawn with; see [ScenerySystem].
 */
public class HollowScene(private val models: (Prop) -> ModelSource) {

    /** Where the clearing is seen from. Mutable, as `ModelCamera` is: a launcher may move it. */
    public val camera: ModelCamera = ModelCamera(
        eyeX = 0f, eyeY = -11f, eyeZ = 5.5f,
        targetX = 0f, targetY = 6f, targetZ = 1.2f,
        fovYDegrees = 55f,
        near = 0.3f,
        far = 150f,
    )

    /** The light every model is drawn in: the level's `Sunlight`, copied each frame. */
    public val light: ModelLight = ModelLight(shadowDistance = SHADOW_DISTANCE)

    /** Registers the three systems. Before the backend starts: it builds the pipeline from the registry. */
    public fun register(registry: RenderRegistry) {
        registry.register(RenderPhase.PreRender, ::SkySystem)
        registry.register(RenderPhase.PreRender, { ScenerySystem(models, light) })
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light) })
    }

    override fun toString(): String = "HollowScene($camera, $light)"

    private companion object {
        /** Metres from the camera that shadows reach: across the clearing to the far trees. */
        const val SHADOW_DISTANCE = 45f
    }
}
