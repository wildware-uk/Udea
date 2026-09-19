package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.Model
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI

/**
 * `runModelShot`: the game's own imported models, as its build left them, drawn with their
 * textures (issue #244).
 *
 * Both models come out of the game's packed bundle by their generated accessors -
 * `GameAssets.models.human` and `GameAssets.models.fox` - so what is drawn is what a game is
 * given, not a file a test chose. The fox is a `.glb` and is read from the asset root; the human is
 * `models/human/Human.fbx` in the asset scripts, and the bundle names `models/human/Human.glb`, which
 * `:moba:game:udeaPackBundle` converted and wrote under `build/udea/converted`. No FBX is read here,
 * and nothing that could read one is on this classpath (`UDEA-MG-013`).
 *
 * Writes `model-human.png` (the character beside the fox, for scale) and `model-human-turn-<n>.png`
 * (the character a quarter turn a step) into `-Dudea.modelshot.dir`. It draws the bind pose: posing
 * a skin from the `Animator` is issue #242's.
 *
 * In the test source set and run by name, never by `check`: it needs a GL driver, and a missing
 * driver in `check` would be a skip, which hides exactly the failure it exists to show.
 */
object GameModelShot {

    private const val WIDTH = 960
    private const val HEIGHT = 540

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(property("udea.modelshot.dir")).apply { mkdirs() }
        val assetRoot = Path.of(property("udea.moba.gameAssets"))
        val convertedRoot = Path.of(property("udea.moba.convertedModels"))

        val humanModel: Model = MobaAssets.registry[GameAssets.models.human]
        val foxModel: Model = MobaAssets.registry[GameAssets.models.fox]
        check(humanModel.file.value == "models/human/Human.glb") {
            "the bundle names ${humanModel.file} for the human; an .fbx is published as the .glb it converts to"
        }
        check(Files.isRegularFile(convertedRoot.resolve(humanModel.file.value))) {
            "no converted ${humanModel.file} under $convertedRoot; run :moba:game:udeaPackBundle"
        }
        val human = loadModel(convertedRoot, humanModel)
        val fox = loadModel(assetRoot, foxModel)

        val camera = ModelCamera().apply { lookAt(0.55f, -3.9f, 1.7f, 0.55f, 0f, 0.9f) }
        val light = ModelLight(directionX = -1f, directionY = 0.6f, directionZ = -0.8f, intensity = 3.2f, ambient = Rgba.of(0.1f, 0.1f, 0.12f))
        val registry = RenderRegistry()
        registry.register(RenderPhase.PreRender, ::SkySystem)
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light) })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "moba-model-shot",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val slot = checkNotNull(backend.pipeline?.capture) { "the pipeline has no capture slot" }
            val world = host.world

            backend.onRenderThread {
                world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.plane(12f, 12f), ModelMaterial(ground(), roughness = 0.9f))
                }
            }
            slot.capture(CaptureRequest())
            val empty = slot.capture(CaptureRequest()).bytes

            lateinit var humanEntity: Entity
            backend.onRenderThread {
                humanEntity = world.entity {
                    it += Transform3D(rotationZ = HUMAN_HEADING, scaleX = HUMAN_SCALE, scaleY = HUMAN_SCALE, scaleZ = HUMAN_SCALE)
                    it += ModelRenderer(model = human)
                }
                world.entity {
                    it += Transform3D(x = 1.4f, y = 0.3f, rotationZ = FOX_HEADING, scaleX = FOX_SCALE, scaleY = FOX_SCALE, scaleZ = FOX_SCALE)
                    it += ModelRenderer(model = fox)
                }
            }
            val settled = settle(empty) { slot.capture(CaptureRequest()).bytes }
            File(out, "model-human.png").writeBytes(settled)
            for (step in 0 until TURN_STEPS) {
                backend.onRenderThread {
                    with(world) { humanEntity[Transform3D].rotationZ = HUMAN_HEADING + step * (PI / 2).toFloat() }
                }
                File(out, "model-human-turn-$step.png").writeBytes(slot.capture(CaptureRequest()).bytes)
            }
            println("model shots written to ${out.absolutePath}")
        } finally {
            backend.close()
        }
    }

    /**
     * Frames until one differs from [empty] and the next is the same picture: Kool draws an
     * imported model once its textures have decoded on its loader threads, not on the first frame.
     */
    private fun settle(empty: ByteArray, frame: () -> ByteArray): ByteArray {
        var previous = empty
        var current = frame()
        var waited = 1
        while ((current.contentEquals(empty) || !current.contentEquals(previous)) && waited < FRAME_BUDGET) {
            previous = current
            current = frame()
            waited++
        }
        check(!current.contentEquals(empty)) { "the models were not drawn in $waited frames" }
        return current
    }

    private fun property(name: String): String = System.getProperty(name) ?: error("-D$name is not set")

    private const val TURN_STEPS = 4
    private const val FRAME_BUDGET = 240

    /**
     * The converted character is about 553 units tall: its mesh is 8 units, under a node scaled by
     * 69.18 in the FBX. This makes it 1.8 tall.
     */
    private const val HUMAN_SCALE = 0.00325f

    /** Three-quarters on to the camera. */
    private const val HUMAN_HEADING = -0.5f

    /** The fox's file is in centimetre-like units, about 80 tall: this makes it about 0.8 tall. */
    private const val FOX_SCALE = 0.01f
    private const val FOX_HEADING = -2.2f

    /** A pale sky, drawn in 2D beneath the 3D pass. */
    private class SkySystem(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(0.62f, 0.74f, 0.88f))
            batch.end()
        }
    }

    /** Grass-green checks, so the ground reads as a floor and the shadow shows on it. */
    private fun ground(): SpriteTexture {
        val size = 64
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val light = ((x / 8) + (y / 8)) % 2 == 0
                val at = (y * size + x) * 4
                rgba[at] = (if (light) 118 else 98).toByte()
                rgba[at + 1] = (if (light) 150 else 128).toByte()
                rgba[at + 2] = (if (light) 92 else 76).toByte()
                rgba[at + 3] = -1
            }
        }
        return SpriteTexture.fromRgba(size, size, rgba, "model-shot-ground")
    }
}
