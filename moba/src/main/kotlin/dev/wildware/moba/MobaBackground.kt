package dev.wildware.moba

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.MathUtils
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig

/**
 * The ground the fight happens on: a tiled field under everything else in the frame.
 *
 * ## What it replaces, and what it is a port of
 *
 * `common/.../ecs/system/BackgroundDrawSystem.kt` - a Fleks `IntervalSystem` that constructed a
 * `SpriteBatch` of its own in a field initialiser, resolved `gameConfig.backgroundTexture`
 * through a `lazy` off a global `gameScreen`, and stretched one texture across
 * `Gdx.graphics.width/height` in **screen** space. Four things about that are wrong here and each
 * one is a rule this engine has:
 *
 *  * it was a Fleks system, so drawing ran inside `world.update(delta)` (spec 3.3 says
 *    presentation systems are not Fleks systems; a `RenderSystem` is not in the world's system
 *    list at all);
 *  * it owned a second `SpriteBatch`, which is the "three batches, three lifetimes" arrangement
 *    `RenderResources` exists to end - this draws through the frame's one batch and hands its
 *    texture to [RenderResources.own] so the pipeline disposes it in reverse construction order;
 *  * it drew in screen space, so the ground did not move under a moving camera and read as a
 *    backdrop painted on the inside of the lens rather than as terrain;
 *  * it drew nothing at all when `backgroundTexture` was null, which is what `moba` shipped:
 *    `config.udea.kts` names no background, so a captured frame was **83% to 99% pure black** -
 *    measured across four `render.screenshot` captures of the real level - and twenty-seven
 *    animated units floated in a void. The same capture with this registered is 0.00%, and its
 *    distinct-colour count goes from 30-125 to 281.
 *
 * ## Why the texture is generated rather than authored
 *
 * There is no ground art in this repository, and tilesheets are Phase 5. The alternatives were a
 * flat quad - which trades a black void for a green one and reads as an untextured plane - or a
 * PNG committed beside the sprites, which is art direction smuggled in as a fix. So the tile is
 * *computed*: a seamless 256x256 pixmap of banded grass with trodden-earth patches, blades and
 * stones, built
 * once at construction from a fixed seed, uploaded once, and thereafter drawn as a single
 * `TextureRegion` whose u/v span is wider than 1 so `Texture.TextureWrap.Repeat` tiles it.
 *
 * The seed is a constant and the generator is [java.util.Random], not [dev.wildware.udea.core.RngService]:
 * this runs on the render thread, once, before the first frame, and never again. It is not
 * simulation, it takes no draw from a seeded stream, and a `time.rewind` cannot change it - which
 * is the property that matters, because a background that differed between two captures of the
 * same paused world would put noise into every `render.compare_artifacts` diff in the game.
 *
 * ## One draw call, no per-frame allocation
 *
 * [render] computes the visible world rectangle from the camera, snaps it out to whole tiles, and
 * issues exactly one `batch.draw`. The `TextureRegion` is mutated in place rather than
 * reconstructed, so a frame allocates nothing here.
 */
internal class BackgroundRenderSystem(
    resources: RenderResources,
    private val camera: CameraRig,
) : RenderSystem {

    private val batch = resources.batch

    /** The one tile, uploaded once and wrapped in both axes. */
    private val texture: Texture = resources.own(buildTile())

    /**
     * The quad drawn each frame, re-pointed in place.
     *
     * A field and not a local: `TextureRegion` is a mutable holder of four floats, and building
     * one per frame is a per-frame allocation on the one path in this system that runs every
     * frame.
     */
    private val region = TextureRegion(texture)

    override fun render(target: OffscreenTarget, alpha: Float) {
        val view = camera.camera
        // The visible rectangle, widened by one tile on every side so a camera that has moved a
        // fraction of a tile still has ground under the edge of the frame.
        val halfWidth = view.viewportWidth * view.zoom * 0.5f + TILE_WORLD
        val halfHeight = view.viewportHeight * view.zoom * 0.5f + TILE_WORLD
        val left = MathUtils.floor((view.position.x - halfWidth) / TILE_WORLD) * TILE_WORLD
        val bottom = MathUtils.floor((view.position.y - halfHeight) / TILE_WORLD) * TILE_WORLD
        val right = MathUtils.ceil((view.position.x + halfWidth) / TILE_WORLD) * TILE_WORLD
        val top = MathUtils.ceil((view.position.y + halfHeight) / TILE_WORLD) * TILE_WORLD
        val tilesX = (right - left) / TILE_WORLD
        val tilesY = (top - bottom) / TILE_WORLD
        // u/v beyond 1 with a Repeat wrap: the GPU tiles, so the whole field is one quad rather
        // than one draw call per tile. `setRegion(u, v, u2, v2)` writes the existing region.
        region.setRegion(0f, 0f, tilesX, tilesY)
        batch.projectionMatrix = view.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            batch.draw(region, left, bottom, right - left, top - bottom)
        } finally {
            // A batch left begun poisons every later pass in the frame with a failure that names
            // the wrong system. See `CharacterRenderSystem` for the same `finally`.
            batch.end()
            batch.color = Color.WHITE
        }
    }

    override fun toString(): String = "BackgroundRenderSystem(${TILE_PIXELS}px tile)"

    internal companion object {

        /**
         * The tile's edge in texels. A power of two, so the `Repeat` wrap is exact.
         *
         * 256, and the number is doing real work: the period of the repeat *is* this many screen
         * pixels, and a smaller tile is read by the eye as a lattice rather than as ground. At 128
         * the earth patches came out as a visible grid of identical dots; at 256 the frame carries
         * twenty of them and no two neighbours are the same shape.
         */
        const val TILE_PIXELS: Int = 256

        /**
         * How much world one tile covers.
         *
         * Paired with [TILE_PIXELS] this is the texel-to-pixel ratio, and it is deliberately 1:1:
         * `MobaScene.WORLD_WIDTH` keeps 320 world units across a 1280-pixel frame, so four screen
         * pixels are one world unit and `256 / 64` puts one texel on one pixel. A ground magnified
         * two-to-one under `TextureFilter.Nearest` sprites reads as a lower-resolution game than
         * the units standing on it.
         */
        const val TILE_WORLD: Float = 64f

        /**
         * Fixed, and the reason two captures of one paused world are byte-identical here.
         *
         * Any constant would do; what must not happen is a time-, address- or entity-derived seed,
         * which would make the ground differ between two frames a diff is comparing.
         */
        private const val SEED: Long = 0x6D6F_6261L

        /** How many shades of grass the bands quantise to. */
        private const val BANDS: Int = 6

        /** How far apart, in 8-bit steps, one shade is from the next. Low, so the bands blend. */
        private const val BAND_LIFT: Int = 5

        /** Base grass red, as an 8-bit channel: the darkest of the [BANDS] shades. */
        private const val GRASS_R: Int = 0x3C

        /** @see GRASS_R */
        private const val GRASS_G: Int = 0x60

        /** @see GRASS_R */
        private const val GRASS_B: Int = 0x34

        /**
         * Trodden earth red. What a patch fully blends to at its centre.
         *
         * Deliberately close in hue to the grass and only darker: a browner, redder earth was
         * tried and every patch read as a *spot* rather than as ground, which put the tile's
         * repeat back on show - five identical spots, on a lattice, all the way across the frame.
         * A feature in a tiled texture repeats by construction; what decides whether a human sees
         * the lattice is how far that feature sits from its surroundings.
         */
        private const val DIRT_R: Int = 0x48

        /** @see DIRT_R */
        private const val DIRT_G: Int = 0x52

        /** @see DIRT_R */
        private const val DIRT_B: Int = 0x36

        /** Patches of trodden earth per tile. */
        private const val PATCHES: Int = 7

        /** How far toward [DIRT_R] the very centre of a patch gets. Never all the way. */
        private const val PATCH_STRENGTH: Float = 0.8f

        /** The smallest earth patch, in texels. */
        private const val PATCH_MIN_RADIUS: Int = 18

        /** How much larger than [PATCH_MIN_RADIUS] the largest patch can be. */
        private const val PATCH_RADIUS_SPREAD: Int = 26

        /** How many grass blades one tile carries. */
        private const val BLADES: Int = 900

        /** How many pebbles one tile carries. */
        private const val STONES: Int = 90

        /**
         * Builds the tile.
         *
         * Seamless by construction rather than by mirroring: a texel's shade is a function of a
         * deterministic hash of its lattice cell, and every feature drawn on top is plotted
         * through `Math.floorMod`, so the right edge meets the left with no seam and there is no
         * wrapped blur pass to get wrong.
         *
         * Composed in an `IntArray` and written out afterwards rather than through
         * `Pixmap.setColor` plus `drawPixel` per texel: at 256 square that is 65,536 pairs of
         * calls across the JNI boundary, on the render thread, before the first frame - which is
         * start-up time `udeaBenchStartup` gates.
         */
        private fun buildTile(): Texture {
            val random = java.util.Random(SEED)
            val size = TILE_PIXELS
            val texels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    texels[y * size + x] = grassAt(x, y)
                }
            }
            // Trodden earth, under the blades so a blade can stand on one. Blended rather than
            // stamped: a hard-edged disc of flat colour reads as a sticker, and five stickers
            // repeating on a short period was worse to look at than plain grass.
            repeat(PATCHES) {
                stampPatch(
                    texels = texels,
                    cx = random.nextInt(size),
                    cy = random.nextInt(size),
                    radius = PATCH_MIN_RADIUS + random.nextInt(PATCH_RADIUS_SPREAD),
                )
            }
            val blade = rgb(0x54, 0x7C, 0x40)
            repeat(BLADES) {
                val x = random.nextInt(size)
                val y = random.nextInt(size)
                val height = 2 + random.nextInt(4)
                for (step in 0 until height) {
                    texels[Math.floorMod(y + step, size) * size + x] = blade
                }
            }
            val stone = rgb(0x6B, 0x67, 0x5E)
            repeat(STONES) {
                val x = random.nextInt(size)
                val y = random.nextInt(size)
                texels[y * size + x] = stone
                texels[y * size + Math.floorMod(x + 1, size)] = stone
                texels[Math.floorMod(y + 1, size) * size + x] = stone
            }
            val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixmap.drawPixel(x, y, texels[y * size + x])
                }
            }
            val texture = Texture(pixmap)
            // The `Texture` copies the pixels on upload, so the decode buffer is this function's
            // to free; leaving it is a native leak GL never reports.
            pixmap.dispose()
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
            // Nearest, for the reason `CharacterRenderSystem` uses it: the sprites over this are
            // pixel art, and a linearly filtered ground under them looks like a different game.
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
            return texture
        }

        /**
         * The grass shade at one texel: three octaves of value noise, quantised to [BANDS].
         *
         * Quantised on purpose - a continuous gradient reads as a blurred photograph next to pixel
         * art, and flat bands read as ground. Three octaves rather than one because a single
         * coarse octave at any usable contrast is visible as *blocks*: the eye finds the lattice,
         * and a lattice is the one thing a tiled ground must not show.
         */
        private fun grassAt(x: Int, y: Int): Int {
            val coarse = noise(x / 48, y / 48)
            val middle = noise(x / 12, y / 12)
            val fine = noise(x / 3, y / 3)
            val blend = coarse * 0.42f + middle * 0.36f + fine * 0.22f
            val step = (blend * BANDS).toInt().coerceIn(0, BANDS - 1)
            val lift = step * BAND_LIFT
            return rgb(GRASS_R + lift, GRASS_G + lift, GRASS_B + lift / 2)
        }

        /**
         * Blends a patch of earth into [texels], centred on ([cx], [cy]) and wrapped at the edges.
         *
         * The weight falls off with distance and the rim is perturbed by [noise] of the offset, so
         * the boundary is ragged and the raggedness tiles with everything else. A weight rather
         * than a replacement, so the grass banding still shows through the middle of a patch and a
         * patch that lands on another does not erase it.
         */
        private fun stampPatch(texels: IntArray, cx: Int, cy: Int, radius: Int) {
            val size = TILE_PIXELS
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val distance = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
                    // The rim wobbles by up to nearly half the radius, which is what stops the
                    // patch reading as a circle stamped by a tool.
                    val edge = radius * (0.55f + 0.45f * noise(dx / 5, dy / 5))
                    if (distance >= edge) continue
                    val weight = (1f - distance / edge).coerceIn(0f, 1f) * PATCH_STRENGTH
                    val index = Math.floorMod(cy + dy, size) * size + Math.floorMod(cx + dx, size)
                    texels[index] = blend(texels[index], DIRT_R, DIRT_G, DIRT_B, weight)
                }
            }
        }

        /** [under] moved [weight] of the way toward ([r], [g], [b]). */
        private fun blend(under: Int, r: Int, g: Int, b: Int, weight: Float): Int {
            val ur = (under ushr 24) and 0xFF
            val ug = (under ushr 16) and 0xFF
            val ub = (under ushr 8) and 0xFF
            return rgb(
                ur + ((r - ur) * weight).toInt(),
                ug + ((g - ug) * weight).toInt(),
                ub + ((b - ub) * weight).toInt(),
            )
        }

        /** Opaque RGBA8888, which is the byte order `Pixmap.drawPixel(x, y, colour)` takes. */
        private fun rgb(r: Int, g: Int, b: Int): Int =
            (r.coerceIn(0, 255) shl 24) or
                (g.coerceIn(0, 255) shl 16) or
                (b.coerceIn(0, 255) shl 8) or
                0xFF

        /**
         * A `[0, 1)` value for a lattice cell, from an integer hash.
         *
         * Deliberately not [java.util.Random]: a hash is a *function of the coordinate*, so the
         * value at cell `(0, n)` is the same however the loop is walked, and the tile comes out
         * seamless with no wrapping logic in the caller.
         */
        private fun noise(cellX: Int, cellY: Int): Float {
            var hash = cellX * 374761393 + cellY * 668265263 + SEED.toInt()
            hash = (hash xor (hash shr 13)) * 1274126177
            return ((hash xor (hash shr 16)) and 0xFFFF) / 65536f
        }
    }
}
