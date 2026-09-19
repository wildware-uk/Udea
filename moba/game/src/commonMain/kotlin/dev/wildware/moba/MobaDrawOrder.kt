package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import dev.wildware.moba.render.PixelCanvas
import dev.wildware.moba.render.withAlpha
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture

/*
 * The two things a legible world pass needs and no single render system can own: the **order**
 * bodies are drawn in, and the **markers** drawn under them.
 *
 * ## Why the order is a shared object and not a `sortedBy` in each pass
 *
 * `CharacterRenderSystem`, `SpriteRenderSystem` and `HealthbarRenderSystem` all walk a Fleks
 * family, and a family's entity bag is in archetype-and-insertion order - which is to say, in the
 * order units happened to be spawned in. Three consequences, all of them measured rather than
 * theorised:
 *
 * - a unit standing *behind* another was drawn *over* it, because it spawned later. Eleven
 *   soldiers in two sprite-widths of each other then read as one indistinguishable mass with an
 *   arbitrary sprite on top;
 * - a corpse left on the field was drawn over the unit that killed it, for the same reason;
 * - the same fight drew differently after a `time.rewind`, because a scene restore repopulates
 *   the bag in a different order - so two captures of the same simulated state were not the same
 *   picture, which is the one property `render.compare_artifacts` depends on.
 *
 * A `sortedBy { it.y }` inside each pass would fix the picture and allocate a list, a comparator
 * and a boxed key per entity per frame, on the one path §4 says must be allocation-free. So the
 * sort is a reusable, primitive-keyed buffer, and there is one class of it rather than three
 * copies (the old blueprints' `spriteRenderer(order = 10, offset = ...)` is the capability this
 * restores, generalised: a per-entity layer *and* a per-entity depth, decided by the caller).
 *
 * ## Nothing here is a Fleks system, and nothing here writes
 *
 * Spec 3.3. Every type in this file is constructed by a `RenderSystem`, reads components, and
 * touches neither `SimClock`, `RngService` nor any component field. Draw order is presentation:
 * two processes that disagree about it must still agree about the fight.
 */

/**
 * A back-to-front draw order for one frame, sorted without allocating.
 *
 * ## How the key is built
 *
 * One `Long` per entity, sorted ascending with the stdlib's `LongArray.sort`, which is a dual-pivot
 * quicksort over a primitive array and allocates nothing:
 *
 * | bits | holds | why |
 * |---|---|---|
 * | 63..56 | [layer] | a corpse must be under every living body whatever its y, and a flash over every one |
 * | 55..24 | world y, order-reversed | higher y is further from the viewer in this top-down field, so it is drawn first |
 * | 23..0 | the slot [add] wrote to | makes the sort total, so two units at exactly one y draw in a stable order rather than whichever quicksort met first |
 *
 * The y bits are the standard total order over IEEE-754: flip the sign bit of a positive float,
 * invert every bit of a negative one, and the unsigned comparison of the results is the numeric
 * comparison of the floats. They are then inverted once more, which is what turns "ascending y"
 * into "descending y" without a reversed sort.
 *
 * ## Allocation
 *
 * The two arrays are grown by doubling and never shrunk, so a world whose unit count is stable -
 * which is every world after its first few frames - allocates nothing per frame. [begin] resets
 * the cursor; it does not clear, because a stale entry past [size] is never read.
 */
internal class WorldDrawOrder(initialCapacity: Int = DEFAULT_CAPACITY) {

    private var keys: LongArray = LongArray(initialCapacity)

    private var entities: Array<Entity?> = arrayOfNulls(initialCapacity)

    /** How many entities were added since the last [begin]. */
    var size: Int = 0
        private set

    /** Starts a frame. O(1): the buffers are reused, not cleared. */
    fun begin() {
        size = 0
    }

    /**
     * Adds [entity], to be drawn in [layer] at world [y].
     *
     * @param layer 0 is drawn first (furthest back). See [DrawLayer] for the ones this game uses.
     * @throws IllegalArgumentException when [layer] is outside `0..`[MAX_LAYER]. A layer that
     *   overflowed into the sign bit would silently invert the whole order, which is the hardest
     *   possible symptom to attribute back to a number a caller passed.
     */
    fun add(entity: Entity, layer: Int, y: Float) {
        require(layer in 0..MAX_LAYER) { "draw layer $layer is outside 0..$MAX_LAYER" }
        if (size == keys.size) grow()
        entities[size] = entity
        keys[size] = keyOf(layer, y, size)
        size++
    }

    /** Puts the entities added since [begin] in draw order. */
    fun sort() {
        // The stdlib's range sort rather than `java.util.Arrays.sort`, which is the same dual-pivot
        // quicksort on the JVM and is the one spelling that exists in common code.
        keys.sort(fromIndex = 0, toIndex = size)
    }

    /**
     * The entity at [index] of the sorted order, `0` being the one drawn first.
     *
     * Non-null for every `index < size`: [add] wrote the slot the key points at before the key
     * existed, and [sort] only permutes keys.
     */
    fun entityAt(index: Int): Entity = entities[(keys[index] and SLOT_MASK).toInt()]!!

    private fun grow() {
        keys = keys.copyOf(keys.size * 2)
        entities = entities.copyOf(entities.size * 2)
    }

    companion object {

        /**
         * Slots reserved up front.
         *
         * `level/test_level` fields twenty-seven units and the effect pass peaks at a few dozen
         * flashes, so this is one doubling of headroom over the busiest tick either pass sees.
         */
        const val DEFAULT_CAPACITY: Int = 64

        /** The highest [add] layer. 127 rather than 255: bit 63 is the sign bit of the key. */
        const val MAX_LAYER: Int = 127

        private const val LAYER_SHIFT: Int = 56

        private const val Y_SHIFT: Int = 24

        private const val SLOT_MASK: Long = 0xFFFFFFL

        /** The most entities one pass can order. Sixteen million; a Fleks world is far smaller. */
        const val MAX_ENTRIES: Int = SLOT_MASK.toInt()

        private const val Y_MASK: Long = 0xFFFFFFFFL

        /** @see WorldDrawOrder for the layout. Visible for `MobaDrawOrderTest`. */
        fun keyOf(layer: Int, y: Float, slot: Int): Long {
            require(slot in 0..MAX_ENTRIES) { "slot $slot does not fit the key's 24 bits" }
            return (layer.toLong() shl LAYER_SHIFT) or
                (farToNearBits(y) shl Y_SHIFT) or
                slot.toLong()
        }

        /**
         * [y] as 32 bits that sort ascending for a *descending* y.
         *
         * `toRawBits` rather than `toBits`: the latter collapses every NaN to one bit pattern,
         * which costs a branch on a per-entity path to normalise a value that cannot reach here as
         * anything but garbage anyway. A NaN y sorts somewhere consistent rather than throwing on
         * the render thread.
         */
        fun farToNearBits(y: Float): Long {
            val raw = y.toRawBits()
            val ascending = if (raw < 0) raw.inv() else raw xor Int.MIN_VALUE
            return ascending.inv().toLong() and Y_MASK
        }
    }
}

/**
 * Which band of the world pass a thing is drawn in.
 *
 * Bands and not a free integer, because "over" and "under" are decisions about the *game* - a
 * corpse is scenery, a body is the fight, a flash is feedback - and a caller choosing 7 has made
 * that decision by accident.
 */
internal object DrawLayer {

    /** A body left on the field. Under everything, so a corpse never hides a living unit. */
    const val CORPSE: Int = 0

    /** A unit that is still fighting. */
    const val UNIT: Int = 1

    /** An arrow in flight, a hit flash, a heal. Over the bodies they belong to. */
    const val EFFECT: Int = 2
}

/**
 * The flat shapes drawn under and over a unit, generated rather than authored.
 *
 * ## Why these are not in the asset tree
 *
 * A footprint ellipse and a selection ring are not art; they are the renderer's own vocabulary,
 * the way a health bar is. Authoring them would put a 64x64 PNG per shape into `assets/`, into
 * the atlas, into every bundle diff and into `MobaAssetsTest`'s counts, to say "a filled circle".
 * They are three shapes on one small texture, built once at bind time and owned by
 * [RenderResources] so the pipeline disposes them.
 *
 * ## What they buy
 *
 * A play agent measured eleven soldiers occupying about two sprite widths, fully overlapping.
 * Y-sorting fixes *which* sprite wins, and does nothing about the fact that eleven identical
 * soldiers overlapping still read as one shape. A team-coloured footprint does: the discs are on
 * the ground plane, they do not overlap the way upright bodies do, and a viewer counts them.
 * That is the same reason every RTS ever shipped drew one.
 */
internal class WorldMarkers(resources: RenderResources) {

    private val texture: SpriteTexture = resources.own(sheet().toTexture("moba-world-markers"))

    /** A filled circle. Drawn squashed, it is the footprint under a unit. */
    private val disc: SpriteRegion = SpriteRegion(texture, 0, 0, CELL, CELL)

    /** An annulus. The ring under the unit the player is driving. */
    private val ring: SpriteRegion = SpriteRegion(texture, CELL, 0, CELL, CELL)

    /** A downward-pointing triangle. The marker over the player's head. */
    private val chevron: SpriteRegion = SpriteRegion(texture, CELL * 2, 0, CELL, CELL)

    /**
     * A flat ellipse centred on `(x, y)`, [width] wide.
     *
     * The height is [FLATTEN] of the width, which is what makes a circle read as lying on the
     * ground rather than standing up facing the camera.
     */
    fun footprint(batch: SpriteBatch2D, x: Float, y: Float, width: Float, colour: Rgba, alpha: Float) {
        quad(batch, disc, x, y, width, width * FLATTEN, colour, alpha)
    }

    /** The same ellipse, hollow. @see footprint */
    fun ring(batch: SpriteBatch2D, x: Float, y: Float, width: Float, colour: Rgba, alpha: Float) {
        quad(batch, ring, x, y, width, width * FLATTEN, colour, alpha)
    }

    /** A triangle pointing down at `(x, y)`, [width] wide, sitting on it. */
    fun chevron(batch: SpriteBatch2D, x: Float, y: Float, width: Float, colour: Rgba, alpha: Float) {
        quad(batch, chevron, x, y + width / 2f, width, width, colour, alpha)
    }

    @Suppress("LongParameterList")
    private fun quad(
        batch: SpriteBatch2D,
        region: SpriteRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        colour: Rgba,
        alpha: Float,
    ) {
        // The tint travels with the draw rather than being set on the batch: `SpriteBatch2D`
        // records one packed tint per instance, so there is no batch-wide colour for two callers
        // to fight over and nothing to put back afterwards.
        batch.draw(region, x - width / 2f, y - height / 2f, width, height, colour.withAlpha(alpha))
    }

    companion object {

        /**
         * The colour of every marker that means "this one is you": the ring, the chevron, and the
         * box `HealthbarRenderSystem` draws around the player's own rail.
         *
         * One constant with three readers rather than three constants, because they mean the same
         * thing to a viewer and a design that changed one of them would silently stop them
         * reading as a set. White-gold, and deliberately not any team's colour: the player's unit
         * is an orc elite on `Team.ORC`, so a team-coloured marker would be the same orange as
         * the four orcs beside it and would mark nothing.
         */
        val PLAYER_COLOUR: Rgba = Rgba.of(1f, 0.94f, 0.55f, 1f)

        /** One shape's cell, square, in pixels. */
        private const val CELL: Int = 64

        /** A footprint's height as a fraction of its width: the ground plane seen at an angle. */
        private const val FLATTEN: Float = 0.42f

        /** How thick the player's ring is, in cell pixels. */
        private const val RING_THICKNESS: Int = 7

        /**
         * The three shapes on one page.
         *
         * One texture rather than three, so the batch never starts a new run between a footprint
         * and a ring: `SpriteBatch2D` ends a run whenever the texture changes, and three textures
         * alternating per unit is three draw calls per unit per frame.
         *
         * [PixelCanvas] anti-aliases each shape as it writes it. The LibGDX version did not - it
         * drew hard-edged discs and asked for `TextureFilter.Linear` to smooth them on the way
         * down - and `SpriteTexture` samples nearest for every texture, so the smoothing is in the
         * image now. The shapes are the same shapes, at the same places, in the same cells.
         */
        private fun sheet(): PixelCanvas {
            val canvas = PixelCanvas(CELL * 3, CELL)
            val centre = CELL / 2f
            val radius = centre - 2f
            canvas.fillCircle(centre, centre, radius, WHITE_R, WHITE_G, WHITE_B)
            canvas.fillCircle(CELL + centre, centre, radius, WHITE_R, WHITE_G, WHITE_B)
            // Punched out rather than drawn as a stroked circle: a one-texel outline vanishes when
            // the ring is scaled down to a unit's footprint.
            canvas.eraseCircle(CELL + centre, centre, radius - RING_THICKNESS)
            // Canvas y runs downwards, so the apex at the largest y is the point of a chevron that
            // appears to point down once the region is drawn.
            val left = CELL * 2
            canvas.fillTriangle(
                left + 6f, 8f,
                left + CELL - 6f, 8f,
                left + centre, CELL - 8f,
                WHITE_R, WHITE_G, WHITE_B,
            )
            return canvas
        }

        /** The shapes are drawn white and tinted at draw time, so every marker is one page. */
        private const val WHITE_R: Int = 0xFF
        private const val WHITE_G: Int = 0xFF
        private const val WHITE_B: Int = 0xFF
    }
}
