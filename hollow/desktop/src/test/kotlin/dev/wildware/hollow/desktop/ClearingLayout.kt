package dev.wildware.hollow.desktop

import dev.wildware.hollow.Prop
import dev.wildware.hollow.Sunlight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The forest clearing as it was first laid out (issue #249): a list of props, each a place, a turn
 * and a size, computed from a fixed seed. [ClearingWriter] saves it as `clearing.udealevel`.
 *
 * An authoring tool, not the game: nothing here runs in a simulation, and the seeded `Random` is the
 * layout's, drawn once when the file is written. Once the level is edited in the editor, the file is
 * the source and this is its first draft.
 *
 * World axes, metres: Z is up and the ground is the XY plane. The fixed camera stands on -Y looking
 * towards +Y, so the ring is complete everywhere but behind it.
 */
internal object ClearingLayout {

    /** One prop: where it stands, its heading in radians about Z, and one uniform scale. */
    data class Placed(val prop: Prop, val x: Float, val y: Float, val heading: Float, val scale: Float)

    /** The sun: high in the south-west behind the camera, warm, with a cool sky light. */
    val SUN = Sunlight(
        directionX = 0.5f, directionY = 0.42f, directionZ = -0.76f,
        red = 1f, green = 0.93f, blue = 0.8f,
        intensity = 3.4f,
        ambientRed = 0.36f, ambientGreen = 0.42f, ambientBlue = 0.52f,
    )

    /** Where the sun's entity stands: nowhere that matters, up and out of the way. */
    const val SUN_HEIGHT = 30f

    fun props(seed: Int = SEED): List<Placed> {
        val random = Random(seed)
        return buildList {
            add(Placed(Prop.GROUND, 0f, 0f, 0f, 1f))
            ring(random, count = 26, radius = 14f..17.5f, props = INNER_TREES, scale = 4.6f..6.2f)
            ring(random, count = 44, radius = 21f..31f, props = OUTER_TREES, scale = 5.2f..7.4f)
            ring(random, count = 9, radius = 9.5f..12f, props = BIG_STONES, scale = 1.8f..2.8f)
            scatter(random, count = 10, radius = 5.5f..13f, props = SMALL_STONES, scale = 2f..3.2f)
            scatter(random, count = 3, radius = 10f..12.5f, props = listOf(Prop.STUMP), scale = 2.6f..3.4f)
            scatter(random, count = 2, radius = 9f..11f, props = listOf(Prop.LOG), scale = 2.4f..3f)
            fence(this)
            scatter(random, count = 48, radius = 6.5f..20f, props = GRASSES, scale = 2.2f..3.4f)
            scatter(random, count = 14, radius = 11.5f..19f, props = BUSHES, scale = 2.8f..4.2f)
            scatter(random, count = 16, radius = 7f..14f, props = FLOWERS, scale = 2.2f..3f)
            scatter(random, count = 5, radius = 12f..17f, props = listOf(Prop.MUSHROOMS), scale = 1.8f..2.4f)
        }
    }

    /** [count] props evenly round the circle, each nudged in angle and distance. */
    private fun MutableList<Placed>.ring(
        random: Random,
        count: Int,
        radius: ClosedFloatingPointRange<Float>,
        props: List<Prop>,
        scale: ClosedFloatingPointRange<Float>,
    ) {
        val step = (2 * PI / count).toFloat()
        for (index in 0 until count) {
            val angle = index * step + random.between(-step / 3f, step / 3f)
            place(random, angle, random.between(radius), props, scale)
        }
    }

    /** [count] props at random angles and distances. */
    private fun MutableList<Placed>.scatter(
        random: Random,
        count: Int,
        radius: ClosedFloatingPointRange<Float>,
        props: List<Prop>,
        scale: ClosedFloatingPointRange<Float>,
    ) {
        repeat(count) {
            place(random, random.between(0f, (2 * PI).toFloat()), random.between(radius), props, scale)
        }
    }

    private fun MutableList<Placed>.place(
        random: Random,
        angle: Float,
        distance: Float,
        props: List<Prop>,
        scale: ClosedFloatingPointRange<Float>,
    ) {
        add(
            Placed(
                prop = props[random.nextInt(props.size)],
                x = cos(angle) * distance,
                y = sin(angle) * distance,
                heading = random.between(0f, (2 * PI).toFloat()),
                scale = random.between(scale),
            ),
        )
    }

    /** A short run of fence along the clearing's edge, on the far left of the camera's view. */
    private fun fence(into: MutableList<Placed>) {
        for (section in 0 until FENCE_SECTIONS) {
            val angle = FENCE_START + section * FENCE_STEP
            // Each section lies along the circle: its long axis (the model's X) on the tangent.
            into += Placed(Prop.FENCE, cos(angle) * FENCE_RADIUS, sin(angle) * FENCE_RADIUS, angle + (PI / 2).toFloat(), FENCE_SCALE)
        }
    }

    private fun Random.between(range: ClosedFloatingPointRange<Float>): Float = between(range.start, range.endInclusive)

    private fun Random.between(from: Float, until: Float): Float = from + nextFloat() * (until - from)

    private const val SEED = 249

    private val INNER_TREES = listOf(Prop.OAK, Prop.TREE_DEFAULT, Prop.TREE_DETAILED, Prop.TREE_FAT, Prop.PINE_ROUND_C, Prop.PINE_TALL_A)
    private val OUTER_TREES = listOf(Prop.PINE_TALL_A, Prop.PINE_TALL_B, Prop.PINE_ROUND_C, Prop.PINE_ROUND_E, Prop.PINE_TALL_B, Prop.OAK)
    private val BIG_STONES = listOf(Prop.STONE_LARGE_A, Prop.STONE_LARGE_C, Prop.STONE_TALL_A, Prop.STONE_TALL_E)
    private val SMALL_STONES = listOf(Prop.STONE_SMALL_A, Prop.STONE_SMALL_C)
    private val GRASSES = listOf(Prop.GRASS, Prop.GRASS_LARGE, Prop.GRASS_LARGE)
    private val BUSHES = listOf(Prop.BUSH, Prop.BUSH_LARGE)
    private val FLOWERS = listOf(Prop.FLOWER_YELLOW, Prop.FLOWER_PURPLE)

    private const val FENCE_SECTIONS = 5
    private const val FENCE_RADIUS = 12.5f
    private const val FENCE_SCALE = 2f

    /** Radians: 110 degrees, left of straight ahead, then 9 degrees (about 2 m) a section. */
    private val FENCE_START = (110 * PI / 180).toFloat()
    private val FENCE_STEP = (9 * PI / 180).toFloat()
}
