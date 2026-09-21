package dev.wildware.hollow.render

import dev.wildware.hollow.Prop
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.camera.ThirdPersonRig
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLibrary
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelSource

/**
 * What Hollow draws, in order: the [SkySystem], then the camera rig placing this frame's view, then
 * [ScenerySystem] and [CharacterSystem] bringing the models and the sun up to date, then
 * `udea-render`'s `ModelRenderSystem` drawing every model lit by the sun with its shadows. Every
 * launcher that draws registers this, so the window and the shot show one scene.
 *
 * The fixed camera of H1 (issue #249) is gone: the view is [rig], the third-person camera of issue
 * #248, which follows the character this process is playing from behind and above and is turned by
 * the mouse. A launcher sets [ThirdPersonRig.target] to its local character's `NetId` and
 * [ThirdPersonRig.motion] to the window's pointer; until it does, the camera stays where it is,
 * which is what a process with nobody to follow should show.
 *
 * [camera] is built here and handed to both the rig that moves it and the renderer that draws
 * through it, so neither has to be registered before the other.
 *
 * The rig writes **nothing** into the world. Movement that follows the camera is read where input is
 * sampled instead - see [cameraRelative].
 *
 * @param models the model each prop is drawn with; see [ScenerySystem].
 * @param human the model a character is drawn with; see [CharacterSystem].
 * @param netIds how [rig] resolves the character it follows.
 * @param library what an entity the simulation named a model for with `Drawn` is drawn with - every
 *   fox (issue #251). Null draws no `Drawn` entity at all, which only a test that has no foxes wants.
 */
public class HollowScene(
    private val models: (Prop) -> ModelSource,
    private val human: () -> ModelSource,
    private val netIds: NetIdIndex,
    private val library: ModelLibrary? = null,
) {

    /** Where the clearing is seen from. Moved by [rig] every frame, drawn through by the renderer. */
    public val camera: ModelCamera = ModelCamera(fovYDegrees = FOV_DEGREES, near = NEAR, far = FAR)

    /** The light every model is drawn in: the level's `Sunlight`, copied each frame. */
    public val light: ModelLight = ModelLight(shadowDistance = SHADOW_DISTANCE)

    /**
     * The view, once [register]'s pipeline has been built.
     *
     * Null until then, because a `RenderSystem` is constructed out of the resources the backend
     * hands it, and those do not exist until the backend starts.
     */
    public var rig: ThirdPersonRig? = null
        private set

    /** Registers the five systems. Before the backend starts: it builds the pipeline from the registry. */
    public fun register(registry: RenderRegistry) {
        registry.register(RenderPhase.PreRender, ::SkySystem)
        registry.register(RenderPhase.PreRender, { resources ->
            ThirdPersonRig(resources, netIds, registry.frameTime, camera).also {
                it.distance = DISTANCE
                it.focusHeight = FOCUS_HEIGHT
                rig = it
            }
        })
        registry.register(RenderPhase.PreRender, { ScenerySystem(models, light) })
        registry.register(RenderPhase.PreRender, { CharacterSystem(human) })
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light, models = library) })
    }

    /**
     * [device]'s keys, turned into world axes by where the camera is looking.
     *
     * Handed to `InputModule` as the game's `IntentSource`, so W walks away from the camera. It is
     * built before the backend is, which is why it asks for [rig] per sample rather than holding
     * one: with no rig yet the keys mean fixed compass directions, which is what a headless host
     * wants and what a window shows for the frame or two before its pipeline exists.
     */
    public fun cameraRelative(device: IntentSource): IntentSource = CameraRelativeIntent(device) { rig }

    override fun toString(): String = "HollowScene($rig, $light)"

    private companion object {
        /** Metres from the camera that shadows reach: across the clearing to the far trees. */
        const val SHADOW_DISTANCE = 45f

        /** How far behind the character the eye sits. Far enough to see what is about to reach it. */
        const val DISTANCE = 6.5f

        /** How high up the character the camera looks: its chest rather than its feet. */
        const val FOCUS_HEIGHT = 1.2f

        const val FOV_DEGREES = 55f
        const val NEAR = 0.3f
        const val FAR = 150f
    }
}
