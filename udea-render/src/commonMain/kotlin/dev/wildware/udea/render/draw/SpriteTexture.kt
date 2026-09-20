package dev.wildware.udea.render.draw

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Uint8Buffer
import dev.wildware.udea.render.RenderResource

/**
 * An image a [SpriteBatch2D] can draw from: an atlas page, a background, a glyph sheet.
 *
 * ## Why this wraps Kool's texture instead of being it
 *
 * Kool is an `implementation` dependency of `udea-render`, so no Kool type may appear on this
 * module's public surface: a game registering a renderer would need Kool on its compile classpath
 * to name one, and UDEA-MG-002 is the rule that says it must not. This is the Udea-side handle.
 *
 * ## Why the Kool texture is made late
 *
 * [fromRgba] keeps the pixels and makes the Kool texture the first time a batch actually draws with
 * it, which is on the render thread inside a frame. Constructing a texture is not a GL call in Kool
 * - the upload happens when a draw first needs it - but deferring it anyway keeps every test of the
 * drawing *decisions* (which region, where, in what order) free of any Kool object at all.
 *
 * ## Pixels are top row first
 *
 * The same convention as every image format and as [SpriteRegion]: `y = 0` is the top of the
 * image. The one texture that is stored the other way up - a render pass's colour attachment, which
 * GL keeps bottom row first - is wrapped by [ofPassColour] with [bottomRowFirst] set, and the batch
 * flips its texture coordinates rather than anybody flipping pixels.
 */
public class SpriteTexture private constructor(
    /** Width in texels. */
    public val width: Int,
    /** Height in texels. */
    public val height: Int,
    private val name: String,
    private var pixels: ByteArray?,
    private var texture: Texture2d?,
    /** True for a render target's colour attachment, stored bottom row first. */
    internal val bottomRowFirst: Boolean,
) : RenderResource {

    private var released = false

    init {
        require(width > 0 && height > 0) { "texture '$name' is ${width}x$height" }
    }

    /** The Kool texture, made on first use. Render thread only. */
    internal fun kool(): Texture2d {
        check(!released) { "texture '$name' has been released and cannot be drawn" }
        texture?.let { return it }
        val rgba = checkNotNull(pixels) { "texture '$name' has neither pixels nor a Kool texture" }
        val buffer = Uint8Buffer(rgba.size)
        for (index in rgba.indices) buffer[index] = rgba[index].toUByte()
        val made = Texture2d(
            BufferedImageData2d(buffer, width, height, TexFormat.RGBA),
            mipMapping = MipMapping.Off,
            // Nearest, clamped: pixel art stays crisp, and a region at the edge of an atlas page
            // does not sample the opposite edge.
            samplerSettings = SamplerSettings().nearest().clamped(),
            name = name,
        )
        texture = made
        pixels = null
        return made
    }

    /**
     * The pixels, if this texture still has them, without consuming them the way [kool] does.
     *
     * For a reader that is not a Kool draw. A screen shader (issue #266) samples its textures
     * through OpenGL directly, and Kool uploads a texture only when one of *its* draws first binds
     * it - so a palette ramp that nothing else draws would never reach the graphics card at all.
     * `null` once [kool] has taken them, and for a wrapped pass attachment, which never had any.
     */
    internal fun peekRgba(): ByteArray? = pixels

    /** The Kool texture if one has been made, without making one. See [peekRgba]. */
    internal fun koolOrNull(): Texture2d? = texture

    /**
     * Releases the Kool texture if one was made. A wrapped pass colour attachment is the pass's to
     * release and is left alone.
     */
    override fun release() {
        if (released) return
        released = true
        if (!bottomRowFirst) texture?.release()
        texture = null
        pixels = null
    }

    override fun toString(): String = "SpriteTexture('$name', ${width}x$height)"

    public companion object {

        /**
         * A texture from RGBA8888 pixels, top row first.
         *
         * @param rgba `width * height * 4` bytes. Copied into the texture on first draw and not
         *   read again, so the caller may reuse the array afterwards.
         */
        public fun fromRgba(width: Int, height: Int, rgba: ByteArray, name: String): SpriteTexture {
            require(rgba.size == width * height * BYTES_PER_PIXEL) {
                "texture '$name' is ${width}x$height, which is ${width * height * BYTES_PER_PIXEL} " +
                    "RGBA bytes, but ${rgba.size} were given"
            }
            return SpriteTexture(width, height, name, rgba.copyOf(), null, bottomRowFirst = false)
        }

        /** One opaque white texel: what [SpriteBatch2D.fill] tints. */
        internal fun whitePixel(name: String): SpriteTexture =
            fromRgba(1, 1, byteArrayOf(-1, -1, -1, -1), name)

        /** A render pass's colour attachment, which GL stores bottom row first. */
        internal fun ofPassColour(texture: Texture2d, width: Int, height: Int): SpriteTexture =
            SpriteTexture(width, height, texture.name, null, texture, bottomRowFirst = true)

        private const val BYTES_PER_PIXEL = 4
    }
}

/**
 * A rectangle of a [SpriteTexture], in texels, origin at the **top left**.
 *
 * Replaces LibGDX's `TextureRegion`. The texture coordinates are computed once here rather than per
 * draw, because a region is drawn every frame and never changes.
 */
public class SpriteRegion(
    public val texture: SpriteTexture,
    /** Left edge, in texels. */
    public val x: Int,
    /** **Top** edge, in texels. */
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {

    init {
        require(width > 0 && height > 0) { "region is ${width}x$height" }
        require(x >= 0 && y >= 0 && x + width <= texture.width && y + height <= texture.height) {
            "region ${width}x$height at ($x, $y) does not fit $texture"
        }
    }

    /** The whole of [texture]. */
    public constructor(texture: SpriteTexture) : this(texture, 0, 0, texture.width, texture.height)

    internal val u0: Float = x.toFloat() / texture.width
    internal val v0: Float = y.toFloat() / texture.height
    internal val du: Float = width.toFloat() / texture.width
    internal val dv: Float = height.toFloat() / texture.height

    override fun toString(): String = "SpriteRegion(${width}x$height at ($x, $y) of $texture)"
}
