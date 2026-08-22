package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.testing.Fixtures
import dev.wildware.udea.compiler.testing.UdeaCheckerTest
import dev.wildware.udea.compiler.testing.source
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 5's "the K2 checkers emit the *same* rule ids as the asset validator", as a test.
 *
 * The contract has two halves and both are easy to break silently:
 *
 * 1. every id this plugin can raise is registered in `udea-diagnostics`, so no producer mints
 *    an id of its own;
 * 2. every diagnostic actually reaching a developer carries that id in its text, because the
 *    compiler prints a message and not a factory name - if the id is not in the message, the
 *    developer never sees it and the two producers *look* like two different tools.
 */
class UdeaRuleParityTest : UdeaCheckerTest() {

    @Test
    fun `every rule the plugin can raise is registered in UdeaRules`() {
        val declared = UdeaDiagnostics.factories.keys.map { it.id }.sorted()

        assertEquals(
            declared,
            declared.mapNotNull { UdeaRules.byId(it)?.id },
            "a rule id the checkers raise is not in the UdeaRules registry; an id minted " +
                "locally is not shared with udea-codegen or the asset validator at all",
        )
        assertTrue(
            declared.isNotEmpty(),
            "the factory map is empty, so this test cannot fail - the checkers are gone, " +
                "not the rules",
        )
    }

    @Test
    fun `every registered factory is an error factory matching its rule's default severity`() {
        for ((rule, _) in UdeaDiagnostics.factories) {
            assertEquals(
                Severity.Error,
                rule.defaultSeverity,
                "${rule.id} is registered against an error factory but its default severity " +
                    "is ${rule.defaultSeverity}; a suppression would not do what it says",
            )
        }
    }

    @Test
    fun `the rendered message carries the id, so a developer sees it`() {
        // The harness only recognises a diagnostic as a Udea one if the message begins
        // `UDEAnnnn: `, which is the shape udea-codegen's KSP errors print. This asserting at
        // all is what stops the id being an internal detail of the factory name.
        val run = compile(
            source(
                "Ided.kt",
                """
                package udea.fixtures

                import dev.wildware.udea.annotations.Net

                class Ided {
                    @Net
                    val speed: Float = 1f
                }
                """,
            ),
        )

        assertEquals(
            listOf(UdeaRules.NET_ON_VAL.id),
            run.diagnostics.map { it.ruleId },
            run.describe(),
        )
    }

    @Test
    fun `a clean component raises none of them`() {
        assertCompilesClean(*Fixtures.WELL_FORMED_COMPILATION)
    }
}
