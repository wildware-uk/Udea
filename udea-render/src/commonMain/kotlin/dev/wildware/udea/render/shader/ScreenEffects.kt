package dev.wildware.udea.render.shader

import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture

/**
 * The two screen effects the engine ships, because #259 says every pixel-art game will want them.
 *
 * Each is an ordinary [UdeaShader]: the same API a game writes its own with, and the same
 * `RenderRegistry.screenPass` to register it. That is on purpose - the engine's own effects are
 * the first users of the surface, so an ergonomic problem in it is one the engine hits too.
 *
 * ```kotlin
 * registry.screenPass(ScreenEffects.palette(listOf(Rgba.BLACK, sand, moss, sky)))
 * registry.screenPass(ScreenEffects.outline(Rgba.BLACK))
 * ```
 *
 * In that order the outline is drawn in its own colour over an already-quantised picture; the
 * other order quantises the outline too, and on a palette that does not contain the outline colour
 * the line comes out as whichever palette entry is nearest. Both are legitimate looks, which is
 * why the order is the game's and not the engine's.
 *
 * ## Their source, and why it is here rather than in a `.frag`
 *
 * Each body is a raw string in this file. A game authors a `.frag` and hands the engine its text,
 * which is the whole point of the surface; the engine's own two are constants because
 * `udea-render` is a multiplatform module whose `commonMain` resources are packaged differently
 * by the JVM jar and the Android library, and a built-in effect that loaded on the desktop and
 * was missing on a phone would be a packaging defect wearing a rendering defect's clothes. A raw
 * string is text somebody wrote, not code built by concatenation, and it is on every target.
 */
public object ScreenEffects {

    /**
     * Snaps every pixel to the nearest of [colours].
     *
     * Nearest by squared distance in linear RGB, which is what a fixed-palette renderer means by
     * nearest and is cheap enough to do per pixel against a palette of tens of entries. Alpha is
     * ignored: a screen effect replaces the frame, which is opaque.
     *
     * The palette becomes a texture one pixel high and [colours]`.size` wide, read with
     * `texelFetch`, so a colour reaches the shader exactly as it was given rather than through a
     * filter. It holds pixels and no graphics-card object until the chain uploads its own copy,
     * so there is nothing here for a caller to release.
     *
     * @param colours at least two: a palette of one is a solid colour, which is not a palette, and
     *   an empty one would leave every pixel as whatever the loop never assigned.
     * @param path what a compile failure names. The default says the shader is the engine's.
     */
    public fun palette(colours: List<Rgba>, path: String = "udea-render/shader/palette.frag"): UdeaShader {
        require(colours.size >= MIN_PALETTE) {
            "a palette needs at least $MIN_PALETTE colours, was ${colours.size}"
        }
        val pixels = ByteArray(colours.size * BYTES_PER_PIXEL)
        for (index in colours.indices) {
            val colour = colours[index]
            val at = index * BYTES_PER_PIXEL
            pixels[at] = channel(colour.r)
            pixels[at + 1] = channel(colour.g)
            pixels[at + 2] = channel(colour.b)
            pixels[at + 3] = OPAQUE
        }
        val ramp = SpriteTexture.fromRgba(colours.size, 1, pixels, "udea-palette-${colours.size}")
        return UdeaShader.fragment(path, PALETTE_SOURCE) {
            texture("uPalette", ramp)
            int("uPaletteSize", colours.size)
        }
    }

    /**
     * Draws a line in [colour] around everything in the engine's object mask.
     *
     * The line is **outside** the silhouette: a pixel that is not masked but touches one that is,
     * which is #259's "a pixel that is not a unit but touches one". So an outline never eats into
     * the thing it surrounds, and the ground - which nothing put in the mask - gets none, however
     * much of the frame it fills.
     *
     * What goes in the mask is the game's: an entity whose `ModelRenderer.mask` is set is drawn
     * into it and nothing else is. With an empty mask this shader is a no-op, and that is the
     * honest answer for a game that has not said what its objects are.
     *
     * @param colour the line, its alpha deciding how much of the original pixel survives: opaque
     *   replaces it, and `Rgba.of(0f, 0f, 0f, 0.55f)` is #259's "keeps 45% of its brightness".
     * @param width how far the search reaches, in pixels. `1f` is a one-pixel line.
     * @param path what a compile failure names.
     */
    public fun outline(
        colour: Rgba = Rgba.BLACK,
        width: Float = 1f,
        path: String = "udea-render/shader/outline.frag",
    ): UdeaShader {
        require(width > 0f) { "an outline of $width pixels would search nowhere" }
        return UdeaShader.fragment(path, OUTLINE_SOURCE) {
            color("uOutline", colour)
            float("uOutlineWidth", width)
        }
    }

    /**
     * The palette body.
     *
     * `texelFetch` rather than `texture`: the palette is a lookup table, and an interpolated read
     * between two entries would hand back a colour the palette does not contain - which is the one
     * thing a palette shader must never do.
     */
    private val PALETTE_SOURCE: String = """
        uniform sampler2D uPalette;
        uniform int uPaletteSize;

        vec4 udeaMain(vec2 uv) {
            vec3 colour = texture(uColor, uv).rgb;
            vec3 nearest = colour;
            float nearestDistance = 1000.0;
            for (int index = 0; index < uPaletteSize; index++) {
                vec3 entry = texelFetch(uPalette, ivec2(index, 0), 0).rgb;
                vec3 delta = entry - colour;
                float distance = dot(delta, delta);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = entry;
                }
            }
            return vec4(nearest, 1.0);
        }
    """.trimIndent()

    /** The outline body. `udeaOutline` is the engine's helper; see [ScreenShaderSource]. */
    private val OUTLINE_SOURCE: String = """
        uniform vec4 uOutline;
        uniform float uOutlineWidth;

        vec4 udeaMain(vec2 uv) {
            vec3 colour = texture(uColor, uv).rgb;
            float edge = udeaOutline(uv, uOutlineWidth) * uOutline.a;
            return vec4(mix(colour, uOutline.rgb, edge), 1.0);
        }
    """.trimIndent()

    /** Below this it is a colour, not a palette. */
    private const val MIN_PALETTE: Int = 2

    private const val BYTES_PER_PIXEL: Int = 4

    private const val OPAQUE: Byte = -1

    private fun channel(value: Float): Byte = ((value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()).toByte()
}
