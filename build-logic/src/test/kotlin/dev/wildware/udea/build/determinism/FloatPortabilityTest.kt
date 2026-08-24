package dev.wildware.udea.build.determinism

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The float story `determinism-audit.md` section 3.1 rests on, re-measured rather than quoted.
 *
 * The audit's whole argument for banning `MathUtils.sin` is that LibGDX builds its lookup table
 * from `java.lang.Math.sin`, which is permitted a 1-ulp error and is **not** specified to agree
 * between JVM implementations - while `Math.sqrt` (which `Vector2.len` and `nor` use) is
 * correctly rounded by IEEE-754 and therefore is. Those two claims are the difference between
 * two green rows and two banned ones, so they are measured here.
 *
 * ## What this asserts, and what it deliberately does not
 *
 * It asserts the **exactly-specified** operations really are exact: `sqrt`, `abs`, `floor`,
 * `rint`. If one of those ever disagreed with `StrictMath` the audit's green rows would be
 * wrong, and that is a fact about the spec rather than about this machine, so a failure would
 * be real news on any runner.
 *
 * It does **not** assert that `Math.sin` diverges. On the JDK this was written against
 * (Corretto 17.0.8) it diverges on 67,912 of 2,000,000 sampled inputs, and that number is
 * recorded in the audit - but a JVM whose `Math.sin` happens to be the strict one is a perfectly
 * conforming JVM, and a test that failed on it would be testing the runner rather than the
 * property. So the divergence is measured and printed, and what is asserted is that the audit
 * documents the risk.
 */
class FloatPortabilityTest {

    private val samples = 200_000
    private fun x(i: Int) = i * 1e-4

    @Test
    fun `the exactly-specified operations are bit-identical to StrictMath`() {
        var sqrtDiff = 0
        var absDiff = 0
        var floorDiff = 0
        var rintDiff = 0
        for (i in 0 until samples) {
            val v = x(i)
            if (Math.sqrt(v) != StrictMath.sqrt(v)) sqrtDiff++
            if (Math.abs(v) != StrictMath.abs(v)) absDiff++
            if (Math.floor(v) != StrictMath.floor(v)) floorDiff++
            if (Math.rint(v) != StrictMath.rint(v)) rintDiff++
        }
        assertEquals(
            listOf(0, 0, 0, 0),
            listOf(sqrtDiff, absDiff, floorDiff, rintDiff),
            "an IEEE-754 correctly-rounded operation disagreed with StrictMath on this JVM. " +
                "determinism-audit.md rates Vector2.len() and nor() deterministic ON THAT BASIS; " +
                "if this fails, those rows are wrong and the replay gate is the only thing left.",
        )
    }

    @Test
    fun `the transcendental divergence the audit describes is measured, not asserted`() {
        var sinDiff = 0
        var atan2Diff = 0
        var firstDivergence = ""
        for (i in 0 until samples) {
            val v = x(i)
            if (Math.sin(v) != StrictMath.sin(v)) {
                if (sinDiff == 0) firstDivergence = "x=$v Math=${Math.sin(v)} Strict=${StrictMath.sin(v)}"
                sinDiff++
            }
            if (Math.atan2(v, 1.0) != StrictMath.atan2(v, 1.0)) atan2Diff++
        }
        println(
            "FloatPortabilityTest on ${System.getProperty("java.vendor")} " +
                "${System.getProperty("java.version")}: Math.sin differs from StrictMath.sin at " +
                "$sinDiff of $samples samples ($firstDivergence); Math.atan2 differs at " +
                "$atan2Diff of $samples.",
        )
        // Nothing is asserted about the counts. What IS asserted is that the audit tells the
        // reader this class of divergence exists and which CI job is the only thing that can
        // catch it, because that is the part a future edit could quietly delete.
        val audit = File(System.getProperty("user.dir"))
            .let { if (it.name == "build-logic") it.parentFile else it }
            .resolve(UdeaVerifyDeterminismTask.AUDIT_FILE)
            .readText()
        assertTrue(audit.contains("StrictMath"), "the audit does not mention StrictMath at all")
        assertTrue(
            audit.contains("MathUtils\$Sin") || audit.contains("MathUtils`\$`Sin") ||
                audit.contains("MathUtils$" + "Sin"),
            "the audit does not name the LibGDX sin table, which is the thing this measures",
        )
        assertTrue(
            audit.contains("replay-equality"),
            "the audit does not say which job catches float divergence; no bytecode rule can",
        )
    }
}
