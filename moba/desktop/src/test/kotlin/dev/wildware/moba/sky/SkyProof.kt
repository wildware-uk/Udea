package dev.wildware.moba.sky

import dev.wildware.moba.MobaAssets
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.FrameCaptureSlot
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
import dev.wildware.udea.render.sky.SkyBackground
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * `runSkyProof`: a game sets its sky, changes it while running, and takes it away (issue #267).
 *
 * ## Why this lives in `moba:desktop`
 *
 * The same reason `ShaderProof` does. `UDEA-MG-002` refuses Kool on this project, so a sky set from
 * here is a sky a game sets with no Kool type in its source - `SkyBackground` and `Rgba` are all it
 * names - and that is a build gate rather than a claim.
 *
 * ## The scene, and what "where nothing is drawn" means here
 *
 * The game's fox on a wide ground plane, the camera low and looking at the horizon, so the top of
 * the frame is past the edge of the world. Which pixels those are is **measured, not assumed**: a
 * row counts as open sky when every pixel in it is exactly black in the capture with no sky set.
 * The checks below then run over those rows, and fail if there are too few of them to mean anything.
 *
 * Every colour is read as red, green and blue from the PNG a capture returned, and never alpha: a
 * capture's alpha is always 255, so a reading that took it would call the black horizon bright.
 *
 * Writes five pictures into `-Dudea.skyproof.dir` and fails, loudly, if any figure is not what the
 * sky has to be worth. The last line it prints is `sky-proof: all checks passed` and nothing else
 * prints it.
 */
object SkyProof {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(property("udea.skyproof.dir")).apply { mkdirs() }
        val fox = loadModel(Path.of(property("udea.moba.gameAssets")), MobaAssets.registry[GameAssets.models.fox])

        val camera = ModelCamera().apply { lookAt(0f, -6f, 1.4f, 0f, 0f, 1.1f) }
        val light = ModelLight(directionX = -1f, directionY = 0.6f, directionZ = -0.8f, intensity = 3.2f, ambient = Rgba.of(0.1f, 0.1f, 0.12f))
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light) })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "moba-sky-proof", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            host.loop.paused = true
            backend.drive(host)
            val slot = checkNotNull(backend.pipeline?.capture) { "this pipeline cannot be captured" }
            backend.onRenderThread {
                host.world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.plane(GROUND, GROUND), ModelMaterial(grass(), roughness = 0.9f))
                }
                host.world.entity {
                    it += Transform3D(rotationZ = FOX_HEADING, scaleX = FOX_SCALE, scaleY = FOX_SCALE, scaleZ = FOX_SCALE)
                    it += ModelRenderer(model = fox)
                }
            }

            // --- before: no sky, which is what every game has until it sets one ----------------
            val none = settle(slot)
            write(none, out, "issue267-1-no-sky.png")
            val open = openRows(none)
            say("before: ${open.size} of $HEIGHT rows are open sky, every pixel in them black")
            require(open.size >= MIN_OPEN_ROWS) {
                "only ${open.size} rows of the frame are open sky; the checks below would be measuring too little to mean anything"
            }
            require(open.first() == 0) { "the top row is not open sky, so the camera is not looking at the horizon" }
            val drawn = drawnPixels(none)
            say("before: $drawn pixels are drawn by the world (not black)")
            require(drawn > WIDTH * HEIGHT / 4) { "the world draws only $drawn pixels; there is nothing for the sky to go behind" }

            // --- a flat day sky ------------------------------------------------------------------
            registry.sky.background = SkyBackground.Solid(DAY)
            val day = settle(slot)
            write(day, out, "issue267-2-solid-day.png")
            val dayWorst = worstInRows(day, open) { DAY }
            say("solid: the furthest open-sky pixel is $dayWorst levels from the sky colour")
            require(dayWorst <= ONE_LEVEL) { "open sky is $dayWorst levels from ${channels(DAY)} under a solid sky" }
            val dayMoved = movedOfDrawn(none, day)
            say("solid: $dayMoved of the $drawn pixels the world draws changed")
            require(dayMoved <= drawn / EDGE_SHARE) {
                "$dayMoved of the world's $drawn pixels changed under the sky: the sky was drawn over the world, not behind it"
            }

            // --- a gradient ---------------------------------------------------------------------
            registry.sky.background = SkyBackground.Gradient(top = ZENITH, bottom = HAZE)
            val gradient = settle(slot)
            write(gradient, out, "issue267-3-gradient-day.png")
            val topWorst = worstInRows(gradient, listOf(0)) { ZENITH }
            say("gradient: the top row is at most $topWorst levels from the top colour")
            require(topWorst <= ONE_LEVEL) { "the top row of the gradient is $topWorst levels from ${channels(ZENITH)}" }
            val rowWorst = worstInRows(gradient, open) { row -> expectedGradient(ZENITH, HAZE, row) }
            say("gradient: every open-sky row is within $rowWorst levels of the blend at its height")
            require(rowWorst <= GRADIENT_LEVELS) {
                "an open-sky row is $rowWorst levels from where a blend from ${channels(ZENITH)} to ${channels(HAZE)} puts it"
            }

            // --- the night map: another sky, set while the game runs ------------------------------
            registry.sky.background = SkyBackground.Gradient(top = MIDNIGHT, bottom = DUSK)
            val night = settle(slot)
            write(night, out, "issue267-4-gradient-night.png")
            val nightWorst = worstInRows(night, open) { row -> expectedGradient(MIDNIGHT, DUSK, row) }
            say("night: every open-sky row is within $nightWorst levels of the night blend")
            require(nightWorst <= GRADIENT_LEVELS) { "the night sky did not replace the day one: $nightWorst levels out" }

            // --- and none again: the frame from before, to the byte -------------------------------
            registry.sky.background = SkyBackground.None
            val again = settle(slot)
            write(again, out, "issue267-5-no-sky-again.png")
            val back = differingPixels(none, again)
            say("none again: $back pixels differ from the frame before any sky was set")
            require(back == 0) { "setting no sky again did not give back the frame with none: $back pixels differ" }

            say("all checks passed; pictures in $out")
        } finally {
            backend.close()
        }
    }

    // --- measuring ----------------------------------------------------------------------------

    /** Rows in which every pixel is exactly black: nothing in the world is drawn there. */
    private fun openRows(image: BufferedImage): List<Int> =
        (0 until image.height).filter { y -> (0 until image.width).all { x -> rgb(image.getRGB(x, y)) == listOf(0, 0, 0) } }

    private fun drawnPixels(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (rgb(image.getRGB(x, y)) != listOf(0, 0, 0)) count++
        return count
    }

    /**
     * How many of the pixels the world drew in [before] are different in [after]. Only the edges of
     * a model should move: they are multisampled, so a silhouette pixel is part model and part
     * whatever is behind it, and behind it is now the sky instead of black.
     */
    private fun movedOfDrawn(before: BufferedImage, after: BufferedImage): Int {
        var count = 0
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                val was = before.getRGB(x, y)
                if (rgb(was) != listOf(0, 0, 0) && rgb(was) != rgb(after.getRGB(x, y))) count++
            }
        }
        return count
    }

    /** The largest per-channel distance, over [rows], from the colour [expected] gives for each row. */
    private fun worstInRows(image: BufferedImage, rows: List<Int>, expected: (Int) -> Rgba): Int {
        var worst = 0
        for (y in rows) {
            val want = channels(expected(y))
            for (x in 0 until image.width) {
                val got = rgb(image.getRGB(x, y))
                for (channel in 0 until CHANNELS) worst = maxOf(worst, abs(want[channel] - got[channel]))
            }
        }
        return worst
    }

    /** Where a blend from [top] to [bottom] down the frame's height puts row [y], by its middle. */
    private fun expectedGradient(top: Rgba, bottom: Rgba, y: Int): Rgba {
        val t = (y + 0.5f) / HEIGHT
        return Rgba.of(top.r + (bottom.r - top.r) * t, top.g + (bottom.g - top.g) * t, top.b + (bottom.b - top.b) * t)
    }

    private fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) count++
        return count
    }

    /** Red, green, blue in `0..255` from a `BufferedImage` ARGB pixel. The alpha byte is dropped on purpose. */
    private fun rgb(argb: Int): List<Int> = listOf((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    /** Red, green, blue in `0..255` from an [Rgba]. */
    private fun channels(colour: Rgba): List<Int> =
        listOf((colour.packed ushr 24) and 0xFF, (colour.packed ushr 16) and 0xFF, (colour.packed ushr 8) and 0xFF)

    // --- plumbing -----------------------------------------------------------------------------

    /**
     * Captures until two in a row are identical: the sky is read at the top of a frame on the render
     * thread, so a frame in flight when it is set finishes with the old one, and a fresh context's
     * first frames are not the scene.
     */
    private fun settle(slot: FrameCaptureSlot): BufferedImage {
        var previous = decode(slot.capture(CaptureRequest()).bytes)
        repeat(SETTLE_TRIES) {
            val next = decode(slot.capture(CaptureRequest()).bytes)
            if (differingPixels(previous, next) == 0) return next
            previous = next
        }
        error("the scene never settled over $SETTLE_TRIES captures; it is not a still picture")
    }

    private fun grass(): SpriteTexture {
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
        return SpriteTexture.fromRgba(size, size, rgba, "sky-proof-grass")
    }

    private fun property(name: String): String = checkNotNull(System.getProperty(name)) { "-D$name was not set" }

    private fun say(line: String) {
        println("sky-proof: $line")
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    private fun write(image: BufferedImage, dir: File, name: String) {
        ImageIO.write(image, "png", File(dir, name))
    }

    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val CHANNELS = 3
    private const val SETTLE_TRIES = 12

    /** A wide ground, so its far edge is small and the horizon is a line across the frame. */
    private const val GROUND = 80f
    private const val FOX_SCALE = 0.012f
    private const val FOX_HEADING = -2.2f

    /** Open-sky rows the checks need before their figures mean anything: a sixth of the frame. */
    private const val MIN_OPEN_ROWS = HEIGHT / 6

    /** A flat sky is exact, or one level out from rounding a float colour to a byte. */
    private const val ONE_LEVEL = 1

    /**
     * How far an open-sky row may be from the ideal blend at its height. The texture holds 256 rows
     * and a frame has 360, so a row shows the nearest texture row rather than its exact height - at
     * most half a texture row away, which for these blends is under one level - plus a level of
     * rounding each way. Any real fault is far outside it: a black sky is 60 or more levels out.
     */
    private const val GRADIENT_LEVELS = 2

    /** At most one drawn pixel in this many may change when a sky goes behind the world: edges only. */
    private const val EDGE_SHARE = 20

    private val DAY = Rgba.of(0.45f, 0.68f, 0.95f)
    private val ZENITH = Rgba.of(0.2f, 0.42f, 0.8f)
    private val HAZE = Rgba.of(0.85f, 0.9f, 0.93f)
    private val MIDNIGHT = Rgba.of(0.01f, 0.02f, 0.08f)
    private val DUSK = Rgba.of(0.55f, 0.25f, 0.35f)
}
