package dev.wildware.udea.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticSinkTest {

    private fun span(line: Int, file: String = "moba/src/main/kotlin/Spawn.kt") =
        SourceSpan(file, line, 17, line, 31)

    /** The fixture the spec calls out: five blueprints all referencing one id that does not exist. */
    private fun fiveReferrersToAMissingId(): List<UdeaDiagnostic> =
        (1..5).map { index ->
            UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
                message = "unresolved reference \"character/orc\" in blueprint $index",
                span = span(10 * index),
                assetId = "character/orc",
                causedBy = "character/orc",
            )
        }

    @Test
    fun `the default cap is the twenty-five the spec mandates`() {
        assertEquals(25, DiagnosticSink.MAX_DIAGNOSTICS)
        assertEquals(25, DiagnosticSink().cap)
    }

    @Test
    fun `a non-positive cap is rejected`() {
        assertFailsWith<IllegalArgumentException> { DiagnosticSink(cap = 0) }
        assertFailsWith<IllegalArgumentException> { DiagnosticSink(cap = -1) }
    }

    @Test
    fun `caps at twenty-five and reports how many were suppressed`() {
        val sink = DiagnosticSink()
        repeat(30) { index ->
            sink.report(
                UdeaRules.NET_ON_VAL.diagnostic(
                    message = "@Net on val field$index",
                    span = span(index + 1, "moba/src/main/kotlin/Health.kt"),
                ),
            )
        }

        val report = sink.build()

        assertEquals(30, sink.reportedCount)
        assertEquals(25, report.diagnostics.size)
        assertEquals(5, report.suppressedCount)
        // The cap truncates the ranked tail, so the survivors are the first 25 source lines.
        assertEquals((1..25).toList(), report.diagnostics.map { it.span!!.startLine })
        assertTrue(report.hasErrors)
    }

    @Test
    fun `dedupes on rule id and span`() {
        val sink = DiagnosticSink()
        val at = span(12, "moba/src/main/kotlin/Health.kt")

        assertTrue(sink.report(UdeaRules.NET_ON_VAL.diagnostic("@Net on val health", at)))
        assertFalse(sink.report(UdeaRules.NET_ON_VAL.diagnostic("reworded but the same defect", at)))

        val report = sink.build()
        assertEquals(1, report.diagnostics.size)
        assertEquals("@Net on val health", report.diagnostics.single().message)
        assertEquals(2, sink.reportedCount)
        // A duplicate hides no information, so it is not counted as suppressed.
        assertEquals(0, report.suppressedCount)
    }

    @Test
    fun `a different rule at the same span is not a duplicate`() {
        val sink = DiagnosticSink()
        val at = span(12, "moba/src/main/kotlin/Health.kt")
        sink.report(UdeaRules.NET_ON_VAL.diagnostic("@Net on a val", at))
        sink.report(UdeaRules.QUANTIZED_NON_FLOAT.diagnostic("@Q on an Int", at))

        assertEquals(2, sink.build().diagnostics.size)
    }

    @Test
    fun `unlocated diagnostics about different assets are not confused for each other`() {
        val sink = DiagnosticSink()
        sink.report(UdeaRules.UNRESOLVED_REFERENCE.diagnostic("missing", assetId = "character/orc"))
        sink.report(UdeaRules.UNRESOLVED_REFERENCE.diagnostic("missing", assetId = "character/elf"))
        sink.report(UdeaRules.UNRESOLVED_REFERENCE.diagnostic("missing", assetId = "character/elf"))

        val report = sink.build()
        assertEquals(2, report.diagnostics.size)
        assertEquals(
            listOf("character/elf", "character/orc"),
            report.diagnostics.mapNotNull { it.assetId }.sorted(),
        )
    }

    @Test
    fun `five referrers to one missing id yield one diagnostic`() {
        val sink = DiagnosticSink()
        sink.reportAll(fiveReferrersToAMissingId())

        val report = sink.build()

        assertEquals(1, report.diagnostics.size)
        assertEquals(4, report.suppressedCount)
        val kept = report.diagnostics.single()
        assertEquals(UdeaRules.UNRESOLVED_REFERENCE.id, kept.ruleId)
        assertEquals("character/orc", kept.causedBy)
        // The representative is the highest-ranked referrer, i.e. the earliest source location.
        assertEquals(10, kept.span!!.startLine)
    }

    @Test
    fun `consequences of two different missing ids collapse independently`() {
        val sink = DiagnosticSink()
        sink.reportAll(fiveReferrersToAMissingId())
        sink.reportAll(
            (1..3).map { index ->
                UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
                    message = "unresolved reference \"ability/fireball\" in blueprint $index",
                    span = span(100 + index),
                    assetId = "ability/fireball",
                    causedBy = "ability/fireball",
                )
            },
        )

        val report = sink.build()
        assertEquals(2, report.diagnostics.size)
        assertEquals(setOf("character/orc", "ability/fireball"), report.diagnostics.map { it.causedBy }.toSet())
        assertEquals(6, report.suppressedCount)
    }

    @Test
    fun `a reported root cause suppresses every consequence of it`() {
        val sink = DiagnosticSink()
        val rootCause = UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
            message = "asset \"character/orc\" is declared nowhere",
            span = SourceSpan("moba/assets/characters.udea.kts", 4, 1, 4, 20),
            assetId = "character/orc",
        )
        sink.report(rootCause)
        sink.reportAll(fiveReferrersToAMissingId())

        val report = sink.build()

        assertEquals(listOf(rootCause), report.diagnostics)
        assertEquals(5, report.suppressedCount)
    }

    @Test
    fun `root causes rank ahead of consequences of the same severity`() {
        val sink = DiagnosticSink()
        val consequence = UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
            message = "unresolved reference \"ability/fireball\"",
            // Deliberately the earliest location, so only root-cause ranking can put it second.
            span = SourceSpan("moba/src/main/kotlin/Aaa.kt", 1, 1, 1, 2),
            assetId = "ability/fireball",
            causedBy = "ability/fireball",
        )
        val rootCause = UdeaRules.NET_ON_VAL.diagnostic(
            message = "@Net on val health",
            span = SourceSpan("moba/src/main/kotlin/Zzz.kt", 900, 1, 900, 2),
        )
        sink.report(consequence)
        sink.report(rootCause)

        assertEquals(listOf(rootCause, consequence), sink.build().diagnostics)
    }

    @Test
    fun `severity outranks root-cause-ness so the cap never drops an error for an info`() {
        val sink = DiagnosticSink(cap = 1)
        val info = UdeaRules.NET_ON_VAL.diagnostic(
            message = "informational root cause",
            span = SourceSpan("moba/src/main/kotlin/Aaa.kt", 1, 1, 1, 2),
            severity = Severity.Info,
        )
        val derivedError = UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
            message = "derived error",
            span = SourceSpan("moba/src/main/kotlin/Zzz.kt", 9, 1, 9, 2),
            assetId = "character/orc",
            causedBy = "character/orc",
        )
        sink.report(info)
        sink.report(derivedError)

        val report = sink.build()
        assertEquals(listOf(derivedError), report.diagnostics)
        assertEquals(1, report.suppressedCount)
    }

    @Test
    fun `ranking does not depend on the order diagnostics were reported`() {
        val diagnostics = fiveReferrersToAMissingId() +
            UdeaRules.NET_ON_VAL.diagnostic("@Net on val health", span(3, "moba/src/main/kotlin/Health.kt")) +
            UdeaRules.QUANTIZED_NON_FLOAT.diagnostic(
                "@Q on an Int",
                span(3, "moba/src/main/kotlin/Health.kt"),
                severity = Severity.Warning,
            )

        val forwards = DiagnosticSink().apply { reportAll(diagnostics) }.build()
        val backwards = DiagnosticSink().apply { reportAll(diagnostics.reversed()) }.build()

        assertEquals(forwards, backwards)
        assertEquals(3, forwards.diagnostics.size)
        assertEquals(
            listOf(Severity.Error, Severity.Error, Severity.Warning),
            forwards.diagnostics.map { it.severity },
        )
    }

    @Test
    fun `build does not consume the sink and clear empties it`() {
        val sink = DiagnosticSink()
        sink.report(UdeaRules.NET_ON_VAL.diagnostic("@Net on val health", span(1)))

        assertEquals(sink.build(), sink.build())

        sink.clear()
        assertEquals(0, sink.reportedCount)
        assertEquals(DiagnosticReport(emptyList(), 0), sink.build())
        assertFalse(sink.build().hasErrors)
    }
}
