package dev.wildware.moba.editor

import dev.wildware.moba.MobaAssets
import dev.wildware.moba.Position
import dev.wildware.udea.assets.Model
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.editor.AnimatedModel
import dev.wildware.udea.editor.EditorAnimation
import dev.wildware.udea.generated.Fox
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.model.ImportedModel
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import java.nio.file.Path
import kotlin.math.PI

/**
 * The editor's 3D models (issue #243): what `runEditor` draws beside `moba`'s own sprites, and what
 * its Animation panel lists.
 *
 * `moba` is a 2D game and draws no model itself, so this is the editor's alone, in the `editor`
 * source set: a [ModelRenderSystem] registered before the backend starts, and the Fox - the game's
 * own `models/fox` asset, with its generated clips - offered in the Animation panel. With
 * `-PeditorFox=true` a Fox playing Survey is also put beside the player when the editor opens, so
 * there is an animated entity to select; there is none in `moba`'s level. It is a dev hook and off
 * by default, because the editor's Save writes the world into the level file, fox included.
 */
internal class MobaEditorModels(
    private val fox: ImportedModel,
    /** Whether to put a Fox beside the player when the editor opens. */
    private val spawnFox: Boolean,
) {

    /** The game frame's view of the models, aimed at the Fox once it is placed. */
    private val camera = ModelCamera(near = CAMERA_NEAR, far = CAMERA_FAR)

    private val light = ModelLight(shadowDistance = SHADOW_DISTANCE)

    /** The system, once the backend has built the pipeline out of the registry. */
    private var renderer: ModelRenderSystem? = null

    /** Registers the model system with the game's own: before the backend starts. */
    fun register(registry: RenderRegistry) {
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light).also { renderer = it } })
    }

    /**
     * The Animation panel's models, with the Fox put beside [player] first when asked for. Before
     * the first frame, while the world is paused and nothing else is touching it.
     */
    fun animation(host: GameHost, player: NetId): EditorAnimation {
        val netIds = host.ctx[CoreModule.NET_IDS]
        if (spawnFox) {
            val beside = checkNotNull(netIds.resolveOrNull(player)) { "the player $player is not in the world the editor opened on" }
            val at = with(host.world) { beside[Position] }
            val x = at.x + FOX_OFFSET_X
            val y = at.y
            val entity = host.world.entity {
                it += Transform3D(x = x, y = y, rotationZ = FOX_HEADING, scaleX = FOX_SCALE, scaleY = FOX_SCALE, scaleZ = FOX_SCALE)
                it += ModelRenderer(model = fox)
                it += Animator().apply { play(Fox.Clips.Survey, host.ctx.clock.tick) }
            }
            netIds.allocate(entity)
            // Side on, a little above: the Scene tab's 3D camera starts here too.
            camera.lookAt(x, y - CAMERA_BACK, CAMERA_UP, x, y, FOX_MIDDLE)
        }
        return EditorAnimation(host.world, netIds, listOf(AnimatedModel(fox, Fox.Clips.all)), renderer)
    }

    override fun toString(): String = "MobaEditorModels(fox=$fox, spawnFox=$spawnFox)"

    companion object {

        /**
         * The editor's models from `runEditor`'s system properties: the Fox read from
         * `udea.assets.root`, or `null` - no panel - when the launch named no asset root.
         */
        fun fromProperties(): MobaEditorModels? {
            val root = System.getProperty("udea.assets.root") ?: return null
            val model: Model = MobaAssets.registry[GameAssets.models.fox]
            return MobaEditorModels(loadModel(Path.of(root), model), System.getProperty("moba.editor.fox").toBoolean())
        }

        /** World units right of the player: clear of its sprite. */
        const val FOX_OFFSET_X = 60f

        /** About 40 units tall, in a view 180 units high. */
        const val FOX_SCALE = 0.5f

        /** Side on to a camera behind it on -Y. */
        const val FOX_HEADING = (PI / 2).toFloat()

        /** Half the Fox's height, where the camera looks. */
        const val FOX_MIDDLE = 18f

        const val CAMERA_BACK = 140f
        const val CAMERA_UP = 70f
        const val CAMERA_NEAR = 1f
        const val CAMERA_FAR = 4000f
        const val SHADOW_DISTANCE = 600f
    }
}
