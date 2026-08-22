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
 * The `@Net`/`@Sim`/`@Q` property rules, positive and negative, with positions asserted.
 *
 * Every case here is the *in-editor* half of a rule `udea-codegen` also raises. The pair is
 * what spec 5's "the K2 checkers emit the same rule ids" buys a developer: the same id whether
 * the defect was caught while typing or at the KSP task boundary.
 */
class UdeaReplicatedPropertyCheckerTest : UdeaCheckerTest() {

    @Test
    fun `Net on a val is an error at the property name`() {
        val fixture = source(
            "Health.kt",
            """
            package udea.fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Health {
                // expect: UDEA0001 @ 10:9
                @Net
                val max: Float = 100f
            }
            """,
        )

        val run = assertMatchesInlineExpectations(fixture)
        run.assertDiagnostic(
            ruleId = UdeaRules.NET_ON_VAL.id,
            severity = Severity.Error,
            line = 10,
            column = 9,
            messageContains = "@Net annotates the val udea.fixtures.Health.max",
        )
    }

    @Test
    fun `Sim on a val is the same defect on the snapshot mask`() {
        val fixture = source(
            "Blackboard.kt",
            """
            package udea.fixtures

            import dev.wildware.udea.annotations.Replicated
            import dev.wildware.udea.annotations.Sim

            @Replicated
            class Blackboard {
                // expect: UDEA0005 @ 10:9
                @Sim
                val lastSeenTick: Long = 0L
            }
            """,
        )

        val run = assertMatchesInlineExpectations(fixture)
        run.assertDiagnostic(
            ruleId = UdeaRules.SIM_ON_VAL.id,
            severity = Severity.Error,
            line = 10,
            column = 9,
            messageContains = "it can never be snapshotted",
        )
    }

    @Test
    fun `a val outside a Replicated class is still reported`() {
        // KSP never looks at this class, so without the checker the annotation is a silent
        // no-op - the exact failure mode section 1 of the engineering standards names.
        val fixture = source(
            "Loose.kt",
            """
            package udea.fixtures

            import dev.wildware.udea.annotations.Net

            class Loose {
                // expect: UDEA0001 @ 8:9
                @Net
                val speed: Float = 1f
            }
            """,
        )

        assertMatchesInlineExpectations(fixture)
    }

    @Test
    fun `Q on a non-float property is an error naming the actual type`() {
        val fixture = source(
            "Charges.kt",
            """
            package udea.fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Q
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Charges {
                // expect: UDEA0003 @ 12:9
                @Net
                @Q(bits = 8, min = 0f, max = 8f)
                var remaining: Int = 0
            }
            """,
        )

        val run = assertMatchesInlineExpectations(fixture)
        run.assertDiagnostic(
            ruleId = UdeaRules.QUANTIZED_NON_FLOAT.id,
            severity = Severity.Error,
            line = 12,
            column = 9,
            messageContains = "which is kotlin.Int, not Float",
        )
    }

    @Test
    fun `a val of a composite type is not reported, because apply restores it in place`() {
        // The one false positive that would matter most: spec 3.1's Transform declares
        // `@Net var position: Vector2`, and udea-codegen lowers it to position.x/position.y,
        // so a val of that shape is legal. UdeaFieldTypes explains why the plugin declines to
        // decide anything the generator's lowering table owns.
        assertCompilesClean(*Fixtures.WELL_FORMED_COMPILATION)
    }

    @Test
    fun `an unannotated val is not reported`() {
        assertCompilesClean(
            source(
                "Plain.kt",
                """
                package udea.fixtures

                class Plain {
                    val max: Float = 100f
                }
                """,
            ),
        )
    }

    @Test
    fun `an unresolved type is left to the compiler's own error`() {
        // Reporting a replication rule on top of "unresolved reference" would name the wrong
        // defect. The Udea diagnostic list must be empty even though the compilation fails.
        val run = compile(
            source(
                "Broken.kt",
                """
                package udea.fixtures

                import dev.wildware.udea.annotations.Net

                class Broken {
                    @Net
                    val thing: NoSuchType = NoSuchType()
                }
                """,
            ),
        )

        assertEquals(emptyList(), run.diagnostics, run.describe())
        assertTrue(
            run.otherMessages.any { "NoSuchType" in it },
            "the compiler's own unresolved-reference error should still be there:\n" + run.describe(),
        )
    }
}
