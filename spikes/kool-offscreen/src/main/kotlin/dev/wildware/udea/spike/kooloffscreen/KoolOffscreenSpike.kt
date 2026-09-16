package dev.wildware.udea.spike.kooloffscreen

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolContext
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.backend.BackendProvider
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.addTextureMesh
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.KoolDispatchers
import de.fabmax.kool.util.Uint8Buffer
import de.fabmax.kool.util.delayFrames
import kotlinx.coroutines.withContext
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.Configuration
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Issue #200: can Kool, on this box, open a hidden window, draw a textured quad into an
 * [OffscreenPass2d], read the pixels back and write a PNG?
 *
 * The program is its own assertion. It exits 0 only when the read-back matches [ExpectedFrame];
 * any mismatch, exception or hang is a non-zero exit, so `run`'s outcome is the answer.
 */
fun main() {
    val backendName = System.getProperty("spike.backend", "gl")
    val out = File(System.getProperty("spike.out", "kool-offscreen-quad.png"))
    val backend: BackendProvider = when (backendName) {
        "gl" -> RenderBackendGl
        "vk" -> RenderBackendVk
        else -> error("spike.backend must be gl or vk, was $backendName")
    }
    startWatchdog()
    initGlfwOnX11WhenNoWayland()

    val config = KoolConfigJvm(
        renderBackend = backend,
        // Off, so a Vulkan attempt that fails reports the failure instead of quietly answering
        // with OpenGL.
        useOpenGlFallback = false,
        showWindowOnStart = false,
        windowTitle = "udea-kool-offscreen-spike",
        windowSize = Vec2i(ExpectedFrame.SIZE, ExpectedFrame.SIZE),
        numSamples = 1,
        isVsync = false,
    )

    var exitCode = EXIT_NO_VERDICT
    KoolApplication(config) {
        val ctx = ctx
        println("spike: backend=${ctx.backend.name} device=${ctx.backend.deviceName} window-visible=${ctx.window.flags.isVisible}")
        try {
            val pass = offscreenPass()
            // Read through a frame copy rather than the attachment itself: Kool's Vulkan backend
            // creates a pass's colour image without VK_IMAGE_USAGE_TRANSFER_SRC_BIT unless it is
            // the source of a copy, and refuses to download it. One path serves both backends.
            val copy = pass.copyOutput(isCopyColor = true, isCopyDepth = false, isSingleShot = false).colorCopy2d
            addScene { addOffscreenPass(pass) }
            delayFrames(FRAMES_BEFORE_READ_BACK)
            val frame = readBack(copy, bottomRowFirst = ctx.backend is RenderBackendGl)
            writePng(frame, out)
            println("spike: wrote ${out.absolutePath}")
            val mismatches = ExpectedFrame.mismatches(frame)
            exitCode = if (mismatches.isEmpty()) {
                println("spike: PASS - the textured quad's known pixels are in the read-back")
                0
            } else {
                mismatches.forEach { println("spike: FAIL - $it") }
                EXIT_MISMATCH
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            exitCode = EXIT_EXCEPTION
        } finally {
            close(ctx)
        }
    }
    exitProcess(exitCode)
}

private fun offscreenPass(): OffscreenPass2d {
    val bg = ExpectedFrame.BACKGROUND
    val drawNode = Node("spike-draw-node")
    drawNode.addTexturedQuad()
    val pass = OffscreenPass2d(
        drawNode = drawNode,
        attachmentConfig = AttachmentConfig {
            addColor(TexFormat.RGBA, clearColor = ClearColorFill(Color(bg.r / 255f, bg.g / 255f, bg.b / 255f, 1f)))
            noDepth()
        },
        initialSize = Vec2i(ExpectedFrame.SIZE, ExpectedFrame.SIZE),
        name = "spike-offscreen",
    )
    pass.camera = OrthographicCamera().apply {
        // One world unit per pixel, origin at the bottom-left of the pass.
        isClipToViewport = true
        setupCamera(position = Vec3f(0f, 0f, 10f), lookAt = Vec3f.ZERO)
        clipNear = 1f
        clipFar = 100f
    }
    return pass
}

/**
 * The quad [ExpectedFrame] describes: centred in the pass, half its size on each axis, textured
 * with [ExpectedFrame.TEXELS] through Kool's own [KslUnlitShader].
 */
private fun Node.addTexturedQuad() {
    val texels = Uint8Buffer(2 * 2 * 4)
    ExpectedFrame.TEXELS.flatten().forEachIndexed { i, texel ->
        texels[i * 4] = texel.r.toUByte()
        texels[i * 4 + 1] = texel.g.toUByte()
        texels[i * 4 + 2] = texel.b.toUByte()
        texels[i * 4 + 3] = 255u
    }
    val texture = Texture2d(
        BufferedImageData2d(texels, 2, 2, TexFormat.RGBA),
        mipMapping = MipMapping.Off,
        samplerSettings = SamplerSettings().nearest().clamped(),
        name = "spike-2x2",
    )
    addTextureMesh("spike-quad") {
        generate {
            rect {
                isCenteredOrigin = true
                origin.set(ExpectedFrame.SIZE / 2f, ExpectedFrame.SIZE / 2f, 0f)
                size.set((ExpectedFrame.QUAD_MAX - ExpectedFrame.QUAD_MIN).toFloat(), (ExpectedFrame.QUAD_MAX - ExpectedFrame.QUAD_MIN).toFloat())
            }
        }
        shader = KslUnlitShader { color { textureColor(texture) } }
    }
}

/**
 * Downloads [texture] on the backend thread as an [RgbaFrame], top row first.
 *
 * Kool hands the rows over as the backend stores them. For the same scene its Vulkan backend
 * gives the top row first and its OpenGL backend the bottom row first (GL's texture origin), so
 * [bottomRowFirst] reverses the rows. Measured, not assumed: without this flip the OpenGL run
 * failed [ExpectedFrame] with every quadrant swapped top-for-bottom, while Vulkan on lavapipe
 * passed it unflipped.
 */
private suspend fun readBack(texture: Texture2d, bottomRowFirst: Boolean): RgbaFrame {
    val data = withContext(KoolDispatchers.Backend) { texture.download() }
    check(data.format == TexFormat.RGBA) { "read-back format is ${data.format}, expected RGBA" }
    val buffer = data.data as Uint8Buffer
    val rowBytes = data.width * 4
    val bytes = ByteArray(data.height * rowBytes)
    for (row in 0 until data.height) {
        val srcRow = if (bottomRowFirst) data.height - 1 - row else row
        for (i in 0 until rowBytes) bytes[row * rowBytes + i] = buffer[srcRow * rowBytes + i].toByte()
    }
    return RgbaFrame(data.width, data.height, bytes)
}

private fun writePng(frame: RgbaFrame, out: File) {
    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until frame.height) for (x in 0 until frame.width) {
        val i = (y * frame.width + x) * 4
        val p = frame.pixels
        val argb = ((p[i + 3].toInt() and 0xff) shl 24) or ((p[i].toInt() and 0xff) shl 16) or
            ((p[i + 1].toInt() and 0xff) shl 8) or (p[i + 2].toInt() and 0xff)
        image.setRGB(x, y, argb)
    }
    out.parentFile?.mkdirs()
    check(ImageIO.write(image, "png", out)) { "no PNG writer available" }
}

private fun close(ctx: KoolContext) {
    (ctx as Lwjgl3Context).close()
}

/**
 * Kool 0.19.0's `GlfwWindowSubsystem.onEarlyInit` hints `GLFW_PLATFORM_WAYLAND` whenever GLFW was
 * *built* with Wayland support, which LWJGL's GLFW always is on Linux, and never falls back. Under
 * xvfb there is no Wayland compositor, so `glfwInit` fails with "Wayland: Failed to connect to
 * display" and Kool throws "Unable to initialize GLFW".
 *
 * `glfwInit` returns true at once when GLFW is already initialised, so initialising it here on
 * X11 first makes Kool's own init hint a no-op. Only done when no Wayland display is advertised.
 */
private fun initGlfwOnX11WhenNoWayland() {
    if (System.getenv("WAYLAND_DISPLAY") != null) return
    // Kool's createContext sets this before it first touches LWJGL. Calling GLFW earlier creates
    // this thread's MemoryStack at LWJGL's default size instead, and Kool's Vulkan instance
    // creation then fails with "OutOfMemoryError: Out of stack space".
    Configuration.STACK_SIZE.set(KOOL_STACK_SIZE_KB)
    GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
    check(GLFW.glfwInit()) { "glfwInit failed on X11 (DISPLAY=${System.getenv("DISPLAY")})" }
    println("spike: GLFW pre-initialised on X11 (DISPLAY=${System.getenv("DISPLAY")})")
}

/** A render loop that never produces a frame must still end the run, and end it red. */
private fun startWatchdog() {
    Thread({
        Thread.sleep(WATCHDOG_MILLIS)
        System.err.println("spike: FAIL - no verdict after ${WATCHDOG_MILLIS / 1000}s")
        Runtime.getRuntime().halt(EXIT_TIMEOUT)
    }, "spike-watchdog").apply { isDaemon = true }.start()
}

/** The value Kool 0.19.0's `createContext` gives `Configuration.STACK_SIZE`. */
private const val KOOL_STACK_SIZE_KB = 128
private const val FRAMES_BEFORE_READ_BACK = 5
private const val WATCHDOG_MILLIS = 120_000L
private const val EXIT_NO_VERDICT = 4
private const val EXIT_MISMATCH = 1
private const val EXIT_EXCEPTION = 2
private const val EXIT_TIMEOUT = 3
