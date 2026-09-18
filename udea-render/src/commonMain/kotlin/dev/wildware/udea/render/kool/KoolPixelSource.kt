package dev.wildware.udea.render.kool

import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.ImageData
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.util.Uint8Buffer
import dev.wildware.udea.render.capture.PixelSource
import dev.wildware.udea.render.capture.PngEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The real pixel path: the capturable pass's colour texture, read back, flipped to top row first,
 * alpha stomped, PNG encoded.
 *
 * ## It reads the pass, and only the pass
 *
 * The texture read is [pass]'s colour attachment. The window's scene - where the agent overlay is
 * drawn - is a different render target that this class holds no reference to, which is the whole
 * of why a capture cannot contain the overlay (spec section 4, "never into a captured frame").
 *
 * ## When it is called
 *
 * At the top of the frame *after* the one that claimed the capture, before that frame has drawn
 * anything (`FrameCaptureSlot.collect`). Kool renders a frame after the pipeline has recorded it,
 * so the pixels a frame produced exist from the end of that frame until the pass is drawn again.
 *
 * ## Row order
 *
 * GL keeps a texture bottom row first, so the rows come back that way and are reversed here. The
 * #200 spike measured that Kool's Vulkan backend hands them over top row first instead, and
 * `GlCaptureTest` pins the order this class assumes. Udea starts Kool on its OpenGL backend only
 * (issue #210), and this class refuses any other rather than guessing.
 */
internal class KoolPixelSource(private val pass: OffscreenPass2d) : PixelSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun readPng(x: Int, y: Int, width: Int, height: Int): ByteArray {
        val backend = KoolSystem.requireContext().backend
        check(backend.name.contains(OPEN_GL, ignoreCase = true)) {
            "captures read back bottom row first, which holds for Kool's OpenGL backend; this " +
                "context is on ${backend.name}"
        }
        val texture = checkNotNull(pass.colorTexture) { "the capturable pass has no colour attachment" }
        val download = CompletableDeferred<ImageData>()
        backend.downloadTextureData(texture, download)
        // Kool's GL backend reads the texture inside this call; a backend that only promised to
        // would hand an agent a capture of whenever it got round to it.
        check(download.isCompleted) { "the backend did not read the capture synchronously" }
        val image = download.getCompleted() as? de.fabmax.kool.pipeline.BufferedImageData2d
            ?: error("the capture read back something other than a 2D image")
        check(image.format == TexFormat.RGBA) { "the capture read back ${image.format}, not RGBA" }
        require(x >= 0 && y >= 0 && x + width <= image.width && y + height <= image.height) {
            "a ${width}x$height region at ($x, $y) does not fit the ${image.width}x${image.height} pass"
        }
        return PngEncoder.encode(width, height, topRowFirst(image.data as Uint8Buffer, image.width, x, y, width, height))
    }

    private companion object {

        const val OPEN_GL = "OpenGL"
        const val BYTES_PER_PIXEL = 4

        /**
         * Copies the region out of a bottom-row-first buffer, top row first, with every alpha
         * byte 255.
         *
         * The alpha stomp is not cosmetic. Blending with `SRC_ALPHA, ONE_MINUS_SRC_ALPHA` writes a
         * product into the destination's alpha wherever anything is drawn, so a read-back frame has
         * partial alpha scattered through it; a viewer that composites over white then washes the
         * whole picture out. The colour channels are what was drawn; alpha in a capture means
         * nothing and is fixed at opaque.
         */
        fun topRowFirst(buffer: Uint8Buffer, stride: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
            val out = ByteArray(width * height * BYTES_PER_PIXEL)
            for (row in 0 until height) {
                // Output row 0 is the region's top, which is source row y + height - 1.
                val sourceRow = y + height - 1 - row
                val from = (sourceRow * stride + x) * BYTES_PER_PIXEL
                val to = row * width * BYTES_PER_PIXEL
                for (column in 0 until width) {
                    val source = from + column * BYTES_PER_PIXEL
                    val target = to + column * BYTES_PER_PIXEL
                    out[target] = buffer[source].toByte()
                    out[target + 1] = buffer[source + 1].toByte()
                    out[target + 2] = buffer[source + 2].toByte()
                    out[target + 3] = -1
                }
            }
            return out
        }
    }
}
