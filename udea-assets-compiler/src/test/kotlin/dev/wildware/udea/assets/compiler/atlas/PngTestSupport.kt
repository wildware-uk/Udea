package dev.wildware.udea.assets.compiler.atlas

import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/** Decoding helpers for tests. Never used by the packer, which has its own reader. */
internal object PngTestSupport {

    fun decode(png: ByteArray): RgbaImage = toRgba(
        checkNotNull(ImageIO.read(ByteArrayInputStream(png))) { "these bytes are not a decodable PNG" },
    )

    fun read(file: Path): RgbaImage = toRgba(
        checkNotNull(ImageIO.read(file.toFile())) { "$file is not a decodable image" },
    )

    private fun toRgba(image: java.awt.image.BufferedImage): RgbaImage {
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return RgbaImage(image.width, image.height, argb)
    }
}
