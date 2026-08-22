package dev.wildware.udea.codegen

import dev.wildware.udea.codegen.fixtures.QuantisedProbe
import dev.wildware.udea.codegen.fixtures.QuantisedProbeReplicator
import dev.wildware.udea.core.fixtures.ArrayBitWriter
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.net.bits.Q
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `@Q` folded into generated code: the constants are literals in the source, the error is
 * bounded by what the annotation bought, and the snapshot is untouched.
 *
 * Three separate claims, and the third is the one that is easy to get wrong. Quantisation is a
 * **wire** concern: `capture` stores the full-precision float, so a rewind replays exactly what
 * the simulation computed and a desync report compares real values. A generator that quantised
 * on the way into the `FieldStore` would round-trip perfectly through `write`/`read` — this
 * file's first test would still pass — and would silently degrade every snapshot in the ring.
 */
class GeneratedReplicatorQuantisationTest {

    private val replicator = QuantisedProbeReplicator

    /**
     * The four declared quantisations, restated from the fixture through `udea-net`'s own
     * resolver.
     *
     * Deliberately not a hand-written epsilon table: `Q.declared` is what `writeFixed` divides
     * the range by, so taking the bound from there is what makes this an assertion about the
     * *engine's* stated error rather than about a number this test made up.
     */
    private val declared: Map<String, Q.Fixed> = mapOf(
        "fraction" to Q.declared(bits = 8, min = 0f, max = 1f),
        "angle" to Q.declared(bits = 12, min = -3.1416f, max = 3.1416f),
        "pool" to Q.declared(bits = 14, min = 0f, max = 5000f),
        "axis" to Q.declared(bits = 16, min = -1024f, max = 1024f),
    )

    private fun set(probe: QuantisedProbe, field: String, value: Float) {
        replicator.setField(probe, replicator.fieldNames.indexOf(field), value)
    }

    private fun get(probe: QuantisedProbe, field: String): Float =
        replicator.getField(probe, replicator.fieldNames.indexOf(field)) as Float

    /** `capture` → `write` → `read` → `apply`, through the whole generated pipeline. */
    private fun roundTrip(source: QuantisedProbe): QuantisedProbe {
        val store = ArrayFieldStore(1, QuantisedProbeReplicator.FIELD_COUNT)
        replicator.capture(source, store, 0)
        val writer = ArrayBitWriter()
        replicator.write(store, 0, replicator.allMask, writer)

        val received = ArrayFieldStore(1, QuantisedProbeReplicator.FIELD_COUNT)
        val mask = replicator.read(writer.toReader(), received, 0)
        val restored = QuantisedProbe()
        replicator.apply(received, 0, restored, mask)
        return restored
    }

    @Test
    fun `every declared width round-trips within the error that width buys`() {
        for ((field, quantisation) in declared) {
            // 41 values across the range, endpoints included: clamping at both bounds is part
            // of the mapping, and the endpoints are exactly representable by construction.
            for (step in 0..40) {
                val value = quantisation.min +
                    (quantisation.max - quantisation.min) * (step / 40f)
                val source = QuantisedProbe()
                set(source, field, value)

                val restored = get(roundTrip(source), field)
                val error = abs(restored - value)

                assertTrue(
                    error <= quantisation.maxError + REPRESENTATION_SLACK,
                    "$field at $value came back as $restored, off by $error, but " +
                        "@Q(bits = ${quantisation.bits}) promises at most ${quantisation.maxError}",
                )
            }
        }
    }

    @Test
    fun `a wider declaration is actually more accurate than a narrower one`() {
        // The bound alone is satisfied by a generator that ignored `bits` and sent the raw
        // float: zero error passes every epsilon. This is the assertion that fails for a
        // generator that ignored `bits` in the other direction — one that used the same width
        // everywhere. Normalised to each field's own range, error must fall as bits rise.
        val source = QuantisedProbe()
        for ((field, quantisation) in declared) {
            // A value deliberately off the quantisation grid: mid-step is the worst case.
            val step = (quantisation.max - quantisation.min) / ((1L shl quantisation.bits) - 1L)
            set(source, field, quantisation.min + step * 10.5f)
        }
        val restored = roundTrip(source)

        val relative = declared.mapValues { (field, quantisation) ->
            abs(get(restored, field) - get(source, field)) / (quantisation.max - quantisation.min)
        }
        val byBits = declared.entries.sortedBy { it.value.bits }.map { it.key }
        for (index in 1 until byBits.size) {
            assertTrue(
                relative.getValue(byBits[index]) < relative.getValue(byBits[index - 1]),
                "${byBits[index]} (${declared.getValue(byBits[index]).bits} bits) was no more " +
                    "accurate than ${byBits[index - 1]}: $relative",
            )
        }
    }

    @Test
    fun `a quantised field costs its declared width on the wire and not thirty-two bits`() {
        val store = ArrayFieldStore(1, QuantisedProbeReplicator.FIELD_COUNT)
        replicator.capture(QuantisedProbe(), store, 0)
        val writer = ArrayBitWriter()

        replicator.write(store, 0, replicator.allMask, writer)

        // 4 mask bits, then 8 + 12 + 14 + 16. Unquantised this component would cost 132.
        assertEquals(4L + 8L + 12L + 14L + 16L, writer.bitPosition)
    }

    @Test
    fun `the snapshot keeps full precision, so quantisation never degrades a rewind`() {
        // The claim `dev.wildware.udea.annotations.Q` makes in its own KDoc, and the one a
        // round-trip test cannot see: capture must not go through the quantiser.
        val awkward = 0.123456789f
        val source = QuantisedProbe()
        set(source, "fraction", awkward)

        val store = ArrayFieldStore(1, QuantisedProbeReplicator.FIELD_COUNT)
        replicator.capture(source, store, 0)

        val stored = store.getFloat(0, replicator.fieldNames.indexOf("fraction"))
        assertEquals(awkward, stored, "capture must store the value the simulation computed")
        assertNotEquals(
            awkward,
            get(roundTrip(source), "fraction"),
            "the fixture value must not survive 8-bit quantisation, or this test proves nothing",
        )
    }

    @Test
    fun `a value past the declared range clamps to the bound it ran past`() {
        val source = QuantisedProbe()
        set(source, "fraction", 5f)
        set(source, "angle", -100f)

        val restored = roundTrip(source)

        assertEquals(1f, get(restored, "fraction"))
        assertEquals(declared.getValue("angle").min, get(restored, "angle"))
    }

    @Test
    fun `the generated source folds the constants in and never mentions the annotation`() {
        // The golden half of the acceptance criterion: quantisation is a *generation-time*
        // decision, which is precisely what a runtime codec cannot do. If `@Q` appeared in the
        // emitted file, the constants would be being read rather than folded.
        val source = GeneratedSources.files
            .single { it.name == "QuantisedProbeReplicator.kt" }
            .readText()

        assertTrue("writeFixed(" in source, "the generated write must call the folded codec:\n$source")
        assertTrue("readFixed(" in source, source)
        for (literal in listOf("-3.1416f", "3.1416f", "5000.0f", "-1024.0f", "1024.0f")) {
            assertTrue(literal in source, "the folded literal $literal is missing:\n$source")
        }
        for (bits in listOf("8)", "12)", "14)", "16)")) {
            assertTrue(bits in source, "a folded bit width is missing:\n$source")
        }
        assertTrue(
            "@Q" !in source && "annotations.Q" !in source,
            "the generated file must not reference the @Q annotation at run time:\n$source",
        )
    }

    @Test
    fun `the generated KDoc documents the epsilon each declaration buys`() {
        // "A documented epsilon per bit width" is an acceptance criterion, and documentation
        // that nothing checks drifts. The number in the KDoc is the number `Q.declared`
        // computes, so the two cannot disagree.
        val source = GeneratedSources.files
            .single { it.name == "QuantisedProbeReplicator.kt" }
            .readText()

        for ((field, quantisation) in declared) {
            assertTrue(
                "max error ${quantisation.maxError}" in source,
                "the KDoc does not state $field's max error of ${quantisation.maxError}:\n$source",
            )
        }
    }

    private companion object {
        /**
         * `dequantise` computes in `Double` and narrows once, so a result carries at most one
         * ulp of `Float` on top of the quantisation error. Scaled to the widest range under
         * test (2048 units), one ulp is about 1.2e-4.
         */
        const val REPRESENTATION_SLACK: Float = 2e-4f
    }
}
