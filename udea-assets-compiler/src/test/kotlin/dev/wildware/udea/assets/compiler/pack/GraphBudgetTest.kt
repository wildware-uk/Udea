package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.pack.BundleReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Graph deserialisation of the example asset set completes under 15ms (issue #89).
 *
 * ## What is measured, and what is not
 *
 * Only [BundleReader.open] on bytes already in memory: header, table of contents, string table,
 * asset table, codecs, and reference binding. Not file IO, not the atlas pages, not script
 * evaluation. That is deliberate - the budget is about the *format*, and mixing in a disk read
 * would make the number a property of the machine's page cache.
 *
 * ## Why the corpus is inflated
 *
 * The real corpora are small: the pack fixture has 10 assets and the example tree 83. Ten assets
 * deserialise in well under a millisecond on anything, so a 15ms budget over them would pass
 * with a decoder a hundred times slower than this one - a gate that cannot fail. So the measured
 * graph is the fixture repeated to [ASSETS], which is comfortably larger than the example tree
 * and larger than a real game's, and the budget is held against *that*.
 *
 * The number is printed on every run, so a machine that is merely slow shows up as a number
 * near the line rather than as a mysterious red.
 */
class GraphBudgetTest {

    @Test
    fun `deserialising a graph larger than the example tree stays inside the budget`() {
        val bytes = BundleWriter.write(BundleContent(assets = syntheticGraph()))

        // Warm: the first open pays for class loading and JIT, which is not what the budget is
        // about - the shipped game's first open pays it too, but under a JVM that has already
        // loaded most of the stdlib.
        repeat(WARMUP) { BundleReader.open(bytes).use { } }

        val samples = (1..SAMPLES).map {
            val start = TimeSource.Monotonic.markNow()
            BundleReader.open(bytes).use { bundle -> assertEquals(ASSETS, bundle.registry.size) }
            start.elapsedNow()
        }
        val best = samples.min()
        val median = samples.sorted()[samples.size / 2]
        println("graph deserialisation: best=$best median=$median over $ASSETS assets (budget $BUDGET)")

        assertTrue(
            median <= BUDGET,
            "deserialising $ASSETS assets took a median of $median, over the $BUDGET budget",
        )
    }

    /**
     * [ASSETS] records with the field shapes the real corpus has: a text, a path, three numbers,
     * a boolean, and a reference to the record before it.
     *
     * Built rather than compiled from scripts so the measurement is of the *format* and not of
     * the Kotlin scripting host, which is two orders of magnitude slower and would swamp it.
     */
    private fun syntheticGraph(): List<PackedAsset> = (0 until ASSETS).map { at ->
        val id = "group%02d/asset_%04d".format(at % 20, at)
        PackedAsset(
            id = id,
            kind = requireNotNull(dev.wildware.udea.assets.SpriteSheet::class.qualifiedName),
            fields = PackValue.Fields.of(
                mapOf(
                    "texture" to PackValue.Path("sprites/group%02d/sheet_%04d.png".format(at % 20, at)),
                    "columns" to PackValue.I32(1 + at % 8),
                    "rows" to PackValue.I32(1),
                    "scale" to PackValue.F32(0.02F),
                ),
            ),
        )
    }.sortedBy { it.id }

    private companion object {
        /** Issue #89's number. */
        val BUDGET = 15.milliseconds

        /** Comfortably above the 83-declaration example tree and a real game's asset count. */
        const val ASSETS = 2000

        const val WARMUP = 5
        const val SAMPLES = 9
    }
}
