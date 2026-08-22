package dev.wildware.udea.compiler.testing

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The self-test issue #37 asks for: **a deliberately mis-positioned inline expectation fails
 * with a message naming expected vs actual `line:column`.**
 *
 * Without this, the whole marker mechanism is a test that cannot fail - the fixtures would
 * declare expectations nobody had ever seen rejected.
 */
class InlineExpectationsTest {

    private fun diagnostic(ruleId: String, line: Int, column: Int) = UdeaDiagnostic(
        severity = Severity.Error,
        ruleId = ruleId,
        message = "irrelevant",
        span = SourceSpan("src/Health.kt", line, column, line, column),
    )

    @Test
    fun `a matching expectation reports no mismatch`() {
        assertNull(
            InlineExpectations.mismatch(
                "Health.kt",
                listOf(InlineExpectation(UdeaRules.NET_ON_VAL.id, 10, 9)),
                listOf(diagnostic(UdeaRules.NET_ON_VAL.id, 10, 9)),
            ),
        )
    }

    @Test
    fun `a mis-positioned expectation names the expected and the actual position`() {
        val failure = assertNotNull(InlineExpectations.mismatch(
            "Health.kt",
            listOf(InlineExpectation(UdeaRules.NET_ON_VAL.id, 7, 9)),
            listOf(diagnostic(UdeaRules.NET_ON_VAL.id, 10, 9)),
        ))

        assertTrue("7:9" in failure, "the message must name the expected position: $failure")
        assertTrue("10:9" in failure, "the message must name the actual position: $failure")
        assertTrue("Health.kt" in failure, "the message must name the file: $failure")
    }

    @Test
    fun `a diagnostic that fired with the wrong rule id at the right place says so`() {
        val failure = assertNotNull(InlineExpectations.mismatch(
            "Health.kt",
            listOf(InlineExpectation(UdeaRules.NET_ON_VAL.id, 10, 9)),
            listOf(diagnostic(UdeaRules.SIM_ON_VAL.id, 10, 9)),
        ))

        assertTrue(
            UdeaRules.SIM_ON_VAL.id in failure && "reported there instead" in failure,
            failure,
        )
    }

    @Test
    fun `an expectation with nothing reported at all says so`() {
        val failure = assertNotNull(InlineExpectations.mismatch(
            "Health.kt",
            listOf(InlineExpectation(UdeaRules.NET_ON_VAL.id, 10, 9)),
            emptyList(),
        ))

        assertTrue("nothing was reported for it" in failure, failure)
        assertTrue("(none)" in failure, failure)
    }

    @Test
    fun `a diagnostic nobody expected is a mismatch too`() {
        val failure = assertNotNull(InlineExpectations.mismatch(
            "Health.kt",
            emptyList(),
            listOf(diagnostic(UdeaRules.NET_ON_VAL.id, 10, 9)),
        ))

        assertTrue("unexpected ${UdeaRules.NET_ON_VAL.id} @ 10:9" in failure, failure)
    }

    @Test
    fun `markers are parsed with their positions`() {
        val parsed = InlineExpectations.parse(
            source(
                "Fixture.kt",
                """
                class Fixture {
                    // expect: UDEA0001 @ 3:9
                    val a = 1
                    // expect:UDEA0003@4:11
                    val b = 2
                }
                """,
            ),
        )

        assertEquals(
            listOf(
                InlineExpectation("UDEA0001", 3, 9),
                InlineExpectation("UDEA0003", 4, 11),
            ),
            parsed,
        )
    }

    @Test
    fun `a marker naming an unregistered id fails loudly rather than never matching`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            InlineExpectations.parse(source("Fixture.kt", "// expect: UDEA9999 @ 1:1"))
        }

        assertTrue("UDEA9999" in failure.message.orEmpty(), failure.message)
    }
}
