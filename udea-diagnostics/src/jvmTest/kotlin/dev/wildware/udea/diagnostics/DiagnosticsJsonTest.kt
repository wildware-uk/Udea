package dev.wildware.udea.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsJsonTest {

    private val goldenReport: DiagnosticReport = DiagnosticReport(
        diagnostics = listOf(
            UdeaDiagnostic(
                severity = Severity.Error,
                ruleId = UdeaRules.NET_ON_VAL.id,
                message = "@Net annotates a val: \"health\" can never change",
                span = SourceSpan("moba/src/main/kotlin/Health.kt", 12, 5, 12, 24),
                fix = Fix(
                    description = "change `val` to `var`",
                    replacements = listOf(
                        Replacement(SourceSpan("moba/src/main/kotlin/Health.kt", 12, 5, 12, 8), "var"),
                    ),
                ),
            ),
            UdeaDiagnostic(
                severity = Severity.Error,
                ruleId = UdeaRules.UNRESOLVED_REFERENCE.id,
                // The em dash is here on purpose: it must survive as an ASCII \u escape.
                message = "unresolved reference \"charater/orc\" — did you mean \"character/orc\"?",
                span = SourceSpan("moba/src/main/kotlin/Spawn.kt", 30, 17, 30, 31),
                assetId = "charater/orc",
                causedBy = "charater/orc",
            ),
            UdeaDiagnostic(
                severity = Severity.Warning,
                ruleId = UdeaRules.QUANTIZED_NON_FLOAT.id,
                message = "@Q on a non-float property",
                assetId = "character/orc",
            ),
        ),
        suppressedCount = 4,
    )

    private fun goldenBytes(): ByteArray {
        val stream = checkNotNull(javaClass.getResourceAsStream(GOLDEN_RESOURCE)) {
            "missing golden resource $GOLDEN_RESOURCE"
        }
        return stream.use { it.readBytes() }
    }

    @Test
    fun `encoding matches the golden fixture byte for byte`() {
        val encoded = DiagnosticsJson.encode(goldenReport).encodeToByteArray()
        val golden = goldenBytes()

        // Compared as text first, because a diff of the text is readable and a diff of the
        // byte arrays is not.
        assertEquals(golden.decodeToString(), encoded.decodeToString())
        assertTrue(golden.contentEquals(encoded), "golden and encoded bytes differ")
    }

    @Test
    fun `the golden fixture pins LF endings and pure ASCII`() {
        val golden = goldenBytes()

        assertTrue(golden.none { it == '\r'.code.toByte() }, "golden contains a CR")
        assertTrue(golden.all { it.toInt() in 0x20..0x7E || it.toInt() == 0x0A }, "golden is not ASCII")
        assertEquals('\n'.code.toByte(), golden.last())
    }

    @Test
    fun `encoding is pure ASCII and LF-terminated whatever the message contains`() {
        val encoded = DiagnosticsJson.encode(
            DiagnosticReport(
                listOf(
                    UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
                        "tab\there, newline\nhere, quote\"here, backslash\\here, emoji 👍, cyrillic Ж",
                    ),
                ),
                suppressedCount = 0,
            ),
        )

        assertTrue(encoded.all { it.code in 0x20..0x7E || it == '\n' }, encoded)
        assertTrue(encoded.endsWith("}\n"))
        assertTrue("\\t" in encoded && "\\n" in encoded && "\\\"" in encoded && "\\\\" in encoded)
        assertTrue("\\u0416" in encoded, "non-ASCII must be escaped, got: $encoded")
        // A surrogate pair escapes as its two code units.
        assertTrue("\\ud83d\\udc4d" in encoded, encoded)
    }

    @Test
    fun `field order is fixed so two producers can be byte-compared`() {
        val encoded = DiagnosticsJson.encode(goldenReport)
        val keyOrder = Regex("\"(\\w+)\":").findAll(encoded).map { it.groupValues[1] }.toList()

        assertEquals(
            listOf(
                "version", "suppressed", "diagnostics",
                // a diagnostic with a span and a fix
                "severity", "ruleId", "message",
                "span", "path", "startLine", "startColumn", "endLine", "endColumn",
                "assetId", "causedBy",
                "fix", "description", "replacements",
                "span", "path", "startLine", "startColumn", "endLine", "endColumn", "newText",
                // a diagnostic with a span and no fix
                "severity", "ruleId", "message",
                "span", "path", "startLine", "startColumn", "endLine", "endColumn",
                "assetId", "causedBy", "fix",
                // a diagnostic with neither
                "severity", "ruleId", "message", "span", "assetId", "causedBy", "fix",
            ),
            keyOrder,
        )
    }

    @Test
    fun `an empty report still round-trips to a stable document`() {
        assertEquals(
            "{\n  \"version\": 1,\n  \"suppressed\": 0,\n  \"diagnostics\": []\n}\n",
            DiagnosticsJson.encode(DiagnosticReport(emptyList(), 0)),
        )
    }

    @Test
    fun `encoding is a pure function of the report`() {
        assertEquals(DiagnosticsJson.encode(goldenReport), DiagnosticsJson.encode(goldenReport))
    }

    private companion object {
        const val GOLDEN_RESOURCE = "/golden/diagnostics.json"
    }
}
