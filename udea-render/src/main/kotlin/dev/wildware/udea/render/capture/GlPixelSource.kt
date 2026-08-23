package dev.wildware.udea.render.capture

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * The real pixel path: `glReadPixels` out of the bound framebuffer, alpha stomped, PNG encoded.
 *
 * Must be called on the GL thread, inside the bound region -- see [FrameSurface] for the
 * ordering and why the capture point sits before the unbind rather than after it.
 */
internal class GlPixelSource : PixelSource {

    override fun readPng(x: Int, y: Int, width: Int, height: Int): ByteArray {
        // `Pixmap.createFromFrameBuffer` and not `ScreenUtils.getFrameBufferPixmap`: the
        // latter is the name the reference implementation used and is deprecated as of
        // LibGDX 1.13, where it delegates here. Same `glReadPixels`, same bottom-up rows,
        // same destination-alpha problem below.
        val pixmap = Pixmap.createFromFrameBuffer(x, y, width, height)
        try {
            forceOpaque(pixmap)
            return encode(pixmap)
        } finally {
            pixmap.dispose()
        }
    }

    /**
     * Encodes [pixmap] as PNG bytes.
     *
     * `PixmapIO.PNG` rather than `PixmapIO.writePNG(file, ...)` -- which is a three-line
     * wrapper around exactly this -- because a capture must not invent a file name. The old
     * `ScreenCapture` wrote to `../build/debug-screenshots/$name.png`, kept the path in a
     * mutable `lastPath` and announced it through an event-log string the caller had to parse;
     * storage is the agent host's artifact store's decision, not the pixel path's.
     *
     * `setFlipY(true)`: framebuffer rows are bottom-up and PNG rows are top-down.
     *
     * The compression level is `Deflater.DEFAULT_COMPRESSION`, matching the reference
     * implementation. Note it is the *DEFLATE level* and not a pixel format: `PixmapIO`'s PNG
     * writer always emits colour type 6 (RGBA), so the alpha channel ships whatever is passed
     * here -- which is why [forceOpaque] is not optional.
     */
    private fun encode(pixmap: Pixmap): ByteArray {
        val bytes = ByteArrayOutputStream(estimatedPngBytes(pixmap))
        val writer = PixmapIO.PNG(estimatedPngBytes(pixmap))
        try {
            writer.setFlipY(true)
            writer.setCompression(Deflater.DEFAULT_COMPRESSION)
            writer.write(bytes, pixmap)
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }

    private companion object {

        /**
         * Stomp the alpha channel to 255.
         *
         * glReadPixels hands back the framebuffer's *destination* alpha, and libGDX's
         * default blend func (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) writes nonsense into it -
         * captures were coming out with alpha as low as 101/255 and only 0.6% of pixels
         * opaque. Any viewer that composites over white then washed the whole frame out,
         * which corrupted eight rounds of visual review. The colour channels were always
         * correct; only alpha was junk.
         */
        fun forceOpaque(pixmap: Pixmap) {
            if (pixmap.format != Pixmap.Format.RGBA8888) return
            val pixels = pixmap.pixels
            var i = 3
            val n = pixels.capacity()
            while (i < n) {
                pixels.put(i, 0xFF.toByte())
                i += 4
            }
        }

        /**
         * A starting size for the encoder's buffer: one byte per channel per pixel.
         *
         * Deliberately an over-estimate of the compressed result rather than a magic constant.
         * DEFLATE on a game frame reliably lands well under the raw size, so this grows the
         * stream zero times for a typical capture and is bounded by the frame either way.
         */
        fun estimatedPngBytes(pixmap: Pixmap): Int = pixmap.width * pixmap.height * BYTES_PER_PIXEL

        const val BYTES_PER_PIXEL: Int = 4
    }
}
