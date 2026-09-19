package dev.wildware.udea.editor.gl

import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.ScreenTarget
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Reads the whole window back once, when asked. An overlay, so it runs at a defined point in the
 * frame; the default framebuffer then holds the previous frame fully drawn, interface included,
 * which is why [read] waits for frames after arming it. `glReadPixels` through LWJGL, as
 * `udea-render`'s `BackbufferProbe` does, because it reads whatever framebuffer is bound: the
 * window's.
 */
internal class WindowProbe : OverlaySystem {
    private val armed = AtomicBoolean(false)

    @Volatile
    private var last: BufferedImage? = null

    override fun render(target: ScreenTarget, dtSeconds: Float) {
        if (!armed.compareAndSet(true, false)) return
        val width = target.width
        val height = target.height
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            // GL's rows count up from the bottom.
            val i = ((height - 1 - y) * width + x) * 4
            image.setRGB(
                x,
                y,
                ((pixels.get(i).toInt() and 0xFF) shl 16) or ((pixels.get(i + 1).toInt() and 0xFF) shl 8) or (pixels.get(i + 2).toInt() and 0xFF),
            )
        }
        last = image
    }

    /** The window as it stands a few frames from now. */
    fun read(frames: FrameProbe): BufferedImage {
        last = null
        awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
        armed.set(true)
        awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
        return checkNotNull(last) { "the window was not read back" }
    }
}

/** Writes [image] as a PNG into the GL tests' report directory, for a person to look at. */
internal fun saveReport(name: String, image: BufferedImage) {
    val dir = System.getProperty("udea.render.glReportDir") ?: return
    File(dir).mkdirs()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    File(dir, name).writeBytes(out.toByteArray())
}
