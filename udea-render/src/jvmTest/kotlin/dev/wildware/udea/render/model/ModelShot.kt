package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
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
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * `runModelShot`: textured, lit 3D models drawn by [ModelRenderSystem], written as PNGs.
 *
 * The scene is built the way a game would build it - entities with a `Transform3D` and a
 * [ModelRenderer], one [ModelRenderSystem] registered in [RenderPhase.World] - on a real Kool
 * context in `Offscreen` mode, and every picture is a capture of the same capturable pass an agent's
 * screenshot reads. A sky gradient is drawn by a plain 2D system in [RenderPhase.PreRender], so the
 * pictures also show the 3D pass sitting on top of 2D drawing.
 *
 * Every texture is generated here from bytes: no art file, nothing to license.
 *
 * Writes `model-textured-lit.png` (the hero shot) and `model-turn-<n>.png` (the crate turning a
 * quarter turn in four steps, the light fixed) into `-Dudea.modelshot.dir`.
 *
 * In the test source set, run by name and never by `check`: it needs a GL driver, and a missing
 * driver in `check` would be a skip, which hides exactly the failure it exists to show.
 */
object ModelShot {

    private const val WIDTH = 960
    private const val HEIGHT = 540

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(
            System.getProperty("udea.modelshot.dir") ?: error("-Dudea.modelshot.dir is not set"),
        )
        out.mkdirs()

        val camera = ModelCamera().apply { lookAt(6.5f, -8f, 5f, 0f, 0.4f, 0.7f) }
        // Low sunlight from the right of the picture, so every model throws a long shadow to the
        // left where the camera can see it, and the faces turned to the sun are clearly brighter.
        val light = ModelLight(directionX = -1f, directionY = 0.25f, directionZ = -0.7f, intensity = 3.2f, ambient = Rgba.of(0.08f, 0.08f, 0.1f))
        val registry = RenderRegistry()
        registry.register(RenderPhase.PreRender, ::SkySystem)
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light) })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-model-shot",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(
                RenderMode.Offscreen,
                UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()),
                backend,
            )
            backend.drive(host)
            val slot = checkNotNull(backend.pipeline?.capture) { "the pipeline has no capture slot" }
            val world = host.world

            lateinit var crate: Entity
            backend.onRenderThread {
                val ground = ModelMaterial(tiles(), roughness = 0.9f)
                val wood = ModelMaterial(planks(), roughness = 0.75f)
                val ball = ModelMaterial(beachBall(), roughness = 0.18f)
                world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.plane(14f, 14f), ground)
                }
                crate = world.entity {
                    it += Transform3D(z = 1f, rotationZ = 0.5f)
                    it += ModelRenderer(ModelMesh.box(2f, 2f, 2f), wood)
                }
                world.entity {
                    it += Transform3D(x = -2.6f, y = -1.2f, z = 0.5f, rotationZ = -0.3f, scaleX = 0.5f, scaleY = 0.5f, scaleZ = 0.5f)
                    it += ModelRenderer(ModelMesh.box(2f, 2f, 2f), wood)
                }
                world.entity {
                    it += Transform3D(x = 2.3f, y = 1.2f, z = 0.9f, rotationX = 0.4f, rotationZ = 0.8f)
                    it += ModelRenderer(ModelMesh.sphere(0.9f, steps = 48), ball)
                }
            }
            // One frame for the new meshes to be ready (see GlModelRenderTest).
            slot.capture(CaptureRequest())

            File(out, "model-textured-lit.png").writeBytes(slot.capture(CaptureRequest()).bytes)
            for (step in 0 until TURN_STEPS) {
                backend.onRenderThread {
                    with(world) { crate[Transform3D].rotationZ = 0.5f + step * (PI / 2 / (TURN_STEPS - 1)).toFloat() }
                }
                File(out, "model-turn-$step.png").writeBytes(slot.capture(CaptureRequest()).bytes)
            }
            println("model shots written to ${out.absolutePath}")
        } finally {
            backend.close()
        }
    }

    private const val TURN_STEPS = 4

    /** A sky: a vertical gradient, drawn in 2D beneath the 3D pass. */
    private class SkySystem(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            val bands = 32
            val bandHeight = target.height.toFloat() / bands
            for (band in 0 until bands) {
                // Band 0 is the bottom: pale at the horizon, deeper blue overhead.
                val t = band / (bands - 1f)
                batch.fill(
                    0f, band * bandHeight, target.width.toFloat(), bandHeight + 1f,
                    Rgba.of(0.78f - 0.45f * t, 0.86f - 0.36f * t, 0.95f - 0.15f * t),
                )
            }
            batch.end()
        }
    }

    // --- generated textures ------------------------------------------------------------------

    /** Grey-green stone tiles with dark grout, lightly mottled. */
    private fun tiles(): SpriteTexture = texture(256, "shot-tiles") { x, y ->
        val tile = 32
        val grout = x % tile < 2 || y % tile < 2
        val tone = if (((x / tile) + (y / tile)) % 2 == 0) 0.52f else 0.44f
        val mottle = (noise(x / 6, y / 6, 7) - 0.5f) * 0.08f
        if (grout) rgb(0.2f, 0.21f, 0.2f) else rgb(tone + mottle, tone + 0.06f + mottle, tone - 0.02f + mottle)
    }

    /** Horizontal wooden planks inside a darker frame, with grain. */
    private fun planks(): SpriteTexture = texture(128, "shot-planks") { x, y ->
        val frame = x < 10 || x >= 118 || y < 10 || y >= 118
        val seam = y % 27 < 2
        val grain = sin((x * 0.21f + noise(x / 8, y / 3, 3) * 6f).toDouble()).toFloat() * 0.05f
        when {
            frame -> rgb(0.36f + grain, 0.22f + grain, 0.1f)
            seam -> rgb(0.28f, 0.17f, 0.08f)
            else -> rgb(0.66f + grain, 0.45f + grain, 0.24f + grain * 0.5f)
        }
    }

    /** Six coloured segments around the ball, white caps at the poles. */
    private fun beachBall(): SpriteTexture = texture(128, "shot-ball") { x, y ->
        val colours = arrayOf(
            rgb(0.9f, 0.16f, 0.14f), rgb(0.98f, 0.98f, 0.96f), rgb(0.12f, 0.4f, 0.9f),
            rgb(0.98f, 0.8f, 0.1f), rgb(0.98f, 0.98f, 0.96f), rgb(0.15f, 0.72f, 0.3f),
        )
        if (y < 12 || y >= 116) rgb(0.98f, 0.98f, 0.96f) else colours[x * colours.size / 128]
    }

    private fun texture(size: Int, name: String, texel: (Int, Int) -> Int): SpriteTexture {
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val colour = texel(x, y)
                val at = (y * size + x) * 4
                rgba[at] = (colour ushr 16).toByte()
                rgba[at + 1] = (colour ushr 8).toByte()
                rgba[at + 2] = colour.toByte()
                rgba[at + 3] = -1
            }
        }
        return SpriteTexture.fromRgba(size, size, rgba, name)
    }

    private fun rgb(r: Float, g: Float, b: Float): Int =
        (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).toInt()

    /** A fixed hash of a lattice point, in `[0, 1)`: texture variation with no random stream. */
    private fun noise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 144269504
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65536f
    }
}
