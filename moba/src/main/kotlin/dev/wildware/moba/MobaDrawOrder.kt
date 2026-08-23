package dev.wildware.moba

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Entity
import dev.wildware.udea.render.RenderResources
import java.util.Arrays

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
 * One `Long` per entity, sorted ascending with `java.util.Arrays.sort`, which is a dual-pivot
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
        Arrays.sort(keys, 0, size)
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
         * `floatToRawIntBits` rather than `floatToIntBits`: the latter collapses every NaN to one
         * bit pattern, which costs a branch on a per-entity path to normalise a value that cannot
         * reach here as anything but garbage anyway. A NaN y sorts somewhere consistent rather
         * than throwing on the render thread.
         */
        fun farToNearBits(y: Float): Long {
            val raw = java.lang.Float.floatToRawIntBits(y)
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

    private val texture: Texture = upload(resources)

    /** A filled circle. Drawn squashed, it is the footprint under a unit. */
    private val disc: TextureRegion = TextureRegion(texture, 0, 0, CELL, CELL)

    /** An annulus. The ring under the unit the player is driving. */
    private val ring: TextureRegion = TextureRegion(texture, CELL, 0, CELL, CELL)

    /** A downward-pointing triangle. The marker over the player's head. */
    private val chevron: TextureRegion = TextureRegion(texture, CELL * 2, 0, CELL, CELL)

    /**
     * A flat ellipse centred on `(x, y)`, [width] wide.
     *
     * The height is [FLATTEN] of the width, which is what makes a circle read as lying on the
     * ground rather than standing up facing the camera.
     */
    fun footprint(batch: Batch, x: Float, y: Float, width: Float, colour: Color, alpha: Float) {
        quad(batch, disc, x, y, width, width * FLATTEN, colour, alpha)
    }

    /** The same ellipse, hollow. @see footprint */
    fun ring(batch: Batch, x: Float, y: Float, width: Float, colour: Color, alpha: Float) {
        quad(batch, ring, x, y, width, width * FLATTEN, colour, alpha)
    }

    /** A triangle pointing down at `(x, y)`, [width] wide, sitting on it. */
    fun chevron(batch: Batch, x: Float, y: Float, width: Float, colour: Color, alpha: Float) {
        quad(batch, chevron, x, y + width / 2f, width, width, colour, alpha)
    }

    private fun quad(
        batch: Batch,
        region: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        colour: Color,
        alpha: Float,
    ) {
        // `setColor(r, g, b, a)` and not `batch.color = colour`: the second would need a mutated
        // `Color` per draw to carry the alpha, and mutating a shared constant is how two callers
        // end up fighting over one object.
        batch.setColor(colour.r, colour.g, colour.b, alpha)
        batch.draw(region, x - width / 2f, y - height / 2f, width, height)
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
        val PLAYER_COLOUR: Color = Color(1f, 0.94f, 0.55f, 1f)

        /** One shape's cell, square, in pixels. */
        private const val CELL: Int = 64

        /** A footprint's height as a fraction of its width: the ground plane seen at an angle. */
        private const val FLATTEN: Float = 0.42f

        /** How thick the player's ring is, in cell pixels. */
        private const val RING_THICKNESS: Int = 7

        /**
         * The three shapes on one page, uploaded and handed to the pipeline to dispose.
         *
         * One texture rather than three, so the batch never flushes between a footprint and a
         * ring: a `Batch` flushes whenever the bound texture changes, and three textures
         * alternating per unit is three flushes per unit per frame.
         *
         * The `Pixmap` is disposed here: `Texture` copies the pixels on upload, so the decode
         * buffer is this function's to free, and leaving it is a native leak GL never reports -
         * the same reason `CharacterRenderSystem.loadFrames` disposes its atlas pages.
         */
        private fun upload(resources: RenderResources): Texture {
            val pixmap = sheet()
            try {
                val texture = resources.own(Texture(pixmap))
                // Linear, unlike the character pages: these shapes are generated at 64px and
                // drawn at roughly a third of that, and `Nearest` on a downscaled circle is a
                // ragged polygon.
                texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                return texture
            } finally {
                pixmap.dispose()
            }
        }

        private fun sheet(): Pixmap {
            val pixmap = Pixmap(CELL * 3, CELL, Pixmap.Format.RGBA8888)
            pixmap.blending = Pixmap.Blending.None
            pixmap.setColor(0f, 0f, 0f, 0f)
            pixmap.fill()
            pixmap.setColor(Color.WHITE)
            val centre = CELL / 2
            val radius = centre - 2
            pixmap.fillCircle(centre, centre, radius)
            pixmap.fillCircle(CELL + centre, centre, radius)
            // Punched out rather than drawn as a stroked circle: `drawCircle` is one pixel wide
            // and vanishes when the ring is scaled down to a unit's footprint.
            pixmap.setColor(0f, 0f, 0f, 0f)
            pixmap.fillCircle(CELL + centre, centre, radius - RING_THICKNESS)
            pixmap.setColor(Color.WHITE)
            // Pixmap y runs downwards, so the apex at the largest y is the point of a chevron
            // that appears to point down once the region is drawn.
            val left = CELL * 2
            pixmap.fillTriangle(
                left + 6,
                8,
                left + CELL - 6,
                8,
                left + centre,
                CELL - 8,
            )
            return pixmap
        }
    }
}
