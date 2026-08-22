package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.testing.Fixtures
import dev.wildware.udea.compiler.testing.UdeaCheckerTest
import dev.wildware.udea.compiler.testing.source
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mask ceiling: 64 compiles, 65 fails, and the message says *split the component*.
 *
 * The message wording is asserted verbatim because spec 7 requires the `Replicator<T>` API to
 * keep a future `LongArray` mask non-breaking. A message that called 64 a format limit would
 * teach a developer something the design has explicitly not committed to, and it would still
 * be there long after the mask had widened.
 */
class UdeaComponentFieldLimitCheckerTest : UdeaCheckerTest() {

    @Test
    fun `a component with exactly sixty-four Net fields compiles clean`() {
        assertCompilesClean(Fixtures.componentWithNetFields(UdeaRules.MAX_COMPONENT_FIELDS))
    }

    @Test
    fun `a component with sixty-five Net fields fails on the class declaration`() {
        val run = compile(Fixtures.componentWithNetFields(UdeaRules.MAX_COMPONENT_FIELDS + 1))

        // Line 7 column 1 is the `class` keyword: the ceiling is a property of the whole
        // component, so it is reported on the declaration and not on an arbitrary field.
        run.assertDiagnostic(
            ruleId = UdeaRules.COMPONENT_FIELD_LIMIT.id,
            severity = Severity.Error,
            line = 7,
            column = 1,
            messageContains = "SPLIT the component",
        )
    }

    @Test
    fun `the split-the-component message is exactly this`() {
        val run = compile(Fixtures.componentWithNetFields(UdeaRules.MAX_COMPONENT_FIELDS + 1))

        assertEquals(
            "udea.fixtures.Wide declares 65 @Net/@Sim fields, but a field mask addresses at " +
                "most 64. SPLIT the component into two or more components of at most 64 fields " +
                "each; there is no way to widen the mask for one component.",
            run.diagnostics.single { it.ruleId == UdeaRules.COMPONENT_FIELD_LIMIT.id }.message,
            "the wording is load-bearing (spec 7): it must name the fix, never a format limit",
        )
    }

    @Test
    fun `the ceiling counts Net and Sim together, because both take a bit of ALL_MASK`() {
        val half = UdeaRules.MAX_COMPONENT_FIELDS / 2
        val fixture = source(
            "Mixed.kt",
            buildString {
                append("package udea.fixtures\n\n")
                append("import dev.wildware.udea.annotations.Net\n")
                append("import dev.wildware.udea.annotations.Replicated\n")
                append("import dev.wildware.udea.annotations.Sim\n\n")
                append("@Replicated\n")
                append("class Mixed {\n")
                repeat(half) { append("    @Net var n").append(it).append(": Int = 0\n") }
                repeat(half + 1) { append("    @Sim var s").append(it).append(": Int = 0\n") }
                append("}\n")
            },
        )

        val run = compile(fixture)

        assertEquals(
            listOf(UdeaRules.COMPONENT_FIELD_LIMIT.id),
            run.diagnostics.map { it.ruleId },
            "32 @Net plus 33 @Sim is 65 bits of one mask:\n" + run.describe(),
        )
    }

    @Test
    fun `a wide class that is not Replicated is left alone`() {
        // The ceiling is a property of a generated Replicator, and nothing is generated for a
        // class KSP never sees. Reporting here would be a false positive on ordinary code.
        val fixture = source(
            "NotAComponent.kt",
            buildString {
                append("package udea.fixtures\n\n")
                append("class NotAComponent {\n")
                repeat(UdeaRules.MAX_COMPONENT_FIELDS + 1) {
                    append("    var f").append(it).append(": Int = 0\n")
                }
                append("}\n")
            },
        )

        assertCompilesClean(fixture)
    }
}
