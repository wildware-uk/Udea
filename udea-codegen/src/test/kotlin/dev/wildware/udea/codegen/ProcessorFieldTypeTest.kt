package dev.wildware.udea.codegen

import dev.wildware.udea.diagnostics.UdeaRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What the generator accepts as a field type, and what it says when it will not.
 *
 * This is the issue that replaced the old generator's silent fallback: anything it did not
 * recognise became `data.putSerializable(property)`, so an unsupported field cost an
 * allocation and a full CBOR encode per tick, and a *wrong* field became a runtime failure on
 * somebody else's machine. Every refusal below is a located build failure that names the type,
 * the class, the property and the reason.
 */
class ProcessorFieldTypeTest {

    private fun source(body: String): String = """
        package fixtures

        import dev.wildware.udea.annotations.Net
        import dev.wildware.udea.annotations.Q
        import dev.wildware.udea.annotations.Replicated
        import dev.wildware.udea.annotations.Sim
        import dev.wildware.udea.core.Tick
        import dev.wildware.udea.core.fixtures.Vec2
        import dev.wildware.udea.core.identity.NetId

    """.trimIndent() + body.trimIndent()

    private fun run(workDir: File, body: String): ProcessorHarness.Run =
        ProcessorHarness.run(workDir, mapOf("Fixture.kt" to source(body)))

    // --- what is accepted ------------------------------------------------------------------

    @Test
    fun `a composite, a NetId and a Tick all generate`(@TempDir workDir: File) {
        val run = run(
            workDir,
            """
            @Replicated
            class Body(
                @Net val position: Vec2 = Vec2(),
                @Net var owner: NetId = NetId.NONE,
                @Sim var settledAt: Tick = Tick.ZERO,
            )
            """,
        )

        assertEquals(emptyList(), run.errors)
        // Sorted by lowered name: `owner`, then the two components of `position`, then
        // `settledAt`. A composite's components sort adjacent because `.` is below every
        // character an identifier may contain.
        val generated = run.generatedSource("BodyReplicator.kt").replace(Regex("""\s+"""), " ")
        assertTrue(
            """listOf("owner", "position.x", "position.y", "settledAt")""" in generated,
            generated,
        )
    }

    @Test
    fun `a single-property value type lowers too, so a game's own vector needs no registry`(
        @TempDir workDir: File,
    ) {
        // Lowering is structural rather than a table of blessed fully-qualified names. A table
        // would mean `udea-codegen` — a build-time-only module — naming LibGDX types it must
        // not depend on, and a game's own vector type could never be added to it.
        val run = run(
            workDir,
            """
            class Charge(var amount: Float = 0f)

            @Replicated
            class Battery(
                @Net var charge: Charge = Charge(),
            )
            """,
        )

        assertEquals(emptyList(), run.errors)
        assertTrue("\"charge.amount\"" in run.generatedSource("BatteryReplicator.kt"))
    }

    @Test
    fun `a composite's components are ordered by name, not by how the vector declares them`(
        @TempDir workDir: File,
    ) {
        // FieldOrder is the single source of bit indices, and it orders the *lowered* names.
        // A second sort inside the lowering would be a second rule that happened to agree —
        // and the wire format has to have exactly one. This vector declares y before x.
        val run = run(
            workDir,
            """
            class Reversed(var y: Float = 0f, var x: Float = 0f)

            @Replicated
            class Marker(
                @Net var at: Reversed = Reversed(),
            )
            """,
        )

        assertEquals(emptyList(), run.errors)
        val generated = run.generatedSource("MarkerReplicator.kt").replace(Regex("""\s+"""), " ")
        assertTrue("""listOf("at.x", "at.y")""" in generated, generated)
        assertTrue("FIELD_AT_X: Int = 0" in generated, generated)
        assertTrue("FIELD_AT_Y: Int = 1" in generated, generated)
    }

    @Test
    fun `a type that cannot be lowered suggests the storable type it is one typo from`(
        @TempDir workDir: File,
    ) {
        val run = run(
            workDir,
            """
            class Flout

            @Replicated
            class Gauge(
                @Net var reading: Flout = Flout(),
            )
            """,
        )

        val diagnostic = run.errorDiagnostics.single()
        assertTrue(
            diagnostic.message.startsWith(UdeaRules.UNSUPPORTED_FIELD_TYPE.id),
            diagnostic.message,
        )
        assertTrue("fixtures.Flout" in diagnostic.message, diagnostic.message)
        assertTrue("Gauge" in diagnostic.message && "reading" in diagnostic.message, diagnostic.message)
        assertTrue(
            "Did you mean Float?" in diagnostic.message,
            "the diagnostics contract makes a Levenshtein suggestion mandatory for an " +
                "unresolved name: ${diagnostic.message}",
        )
        assertEquals("Fixture.kt", diagnostic.file, "reported at ${diagnostic.position}")
        assertEquals(emptyList(), run.generatedFiles)
        assertFalse(run.succeeded)
    }

    @Test
    fun `a composite whose component is a val is refused, naming that component`(
        @TempDir workDir: File,
    ) {
        // The failure that would otherwise surface as generated code that does not compile:
        // `apply` writes `component.extent.width = …`, which needs `width` to be assignable.
        val run = run(
            workDir,
            """
            class Extent(val width: Float = 0f, var height: Float = 0f)

            @Replicated
            class Box(
                @Net var extent: Extent = Extent(),
            )
            """,
        )

        val message = run.errorDiagnostics.single().message
        assertTrue(message.startsWith(UdeaRules.UNSUPPORTED_FIELD_TYPE.id), message)
        assertTrue("width" in message, "the message must name the offending component: $message")
        assertTrue("val" in message, message)
        assertEquals(emptyList(), run.generatedFiles)
    }

    @Test
    fun `lowering is one level deep, and a nested composite says so`(@TempDir workDir: File) {
        val run = run(
            workDir,
            """
            class Nested(var inner: Vec2 = Vec2())

            @Replicated
            class Holder(
                @Net var nested: Nested = Nested(),
            )
            """,
        )

        val message = run.errorDiagnostics.single().message
        assertTrue(message.startsWith(UdeaRules.UNSUPPORTED_FIELD_TYPE.id), message)
        assertTrue("inner" in message, message)
        assertTrue("one level deep" in message, message)
    }

    @Test
    fun `a nullable field is refused rather than given a sentinel`(@TempDir workDir: File) {
        val run = run(
            workDir,
            """
            @Replicated
            class Optional(
                @Net var maybe: Float? = null,
            )
            """,
        )

        val message = run.errorDiagnostics.single().message
        assertTrue(message.startsWith(UdeaRules.UNSUPPORTED_FIELD_TYPE.id), message)
        assertTrue("kotlin.Float?" in message, message)
    }

    // --- the 64-field budget counts lowered fields -------------------------------------------

    @Test
    fun `the field limit counts lowered fields, not annotated properties`(@TempDir workDir: File) {
        // 33 vectors is 33 properties and 66 fields. Counting properties would accept it and
        // then emit a component whose mask cannot address its own last two fields.
        val properties = (0 until 33).joinToString("\n") { "    @Net val v%02d: Vec2 = Vec2(),".format(it) }
        val run = run(
            workDir,
            """
            @Replicated
            class TooWide(
            $properties
            )
            """,
        )

        val message = run.errorDiagnostics.single { UdeaRules.COMPONENT_FIELD_LIMIT.id in it.message }.message
        assertTrue("66" in message, "the error must count lowered fields: $message")
        assertTrue("SPLIT" in message, message)
        assertEquals(emptyList(), run.generatedFiles)
    }

    @Test
    fun `thirty-two vectors is sixty-four fields and is accepted`(@TempDir workDir: File) {
        val properties = (0 until 32).joinToString("\n") { "    @Net val v%02d: Vec2 = Vec2(),".format(it) }
        val run = run(
            workDir,
            """
            @Replicated
            class AtLimit(
            $properties
            )
            """,
        )

        assertEquals(emptyList(), run.errors, "64 lowered fields is the limit, not one past it")
        assertEquals(listOf("AtLimitReplicator.kt"), run.generatedFiles.map { it.name })
    }

    // --- @Q's own arguments -------------------------------------------------------------------

    @Test
    fun `a bit width the wire cannot carry is refused at the property`(@TempDir workDir: File) {
        // `writeFixed` requires 1..32. Unchecked, this is a file that compiles and throws from
        // `write` on the first tick the field changes — on a server, in front of players.
        val run = run(
            workDir,
            """
            @Replicated
            class Wide(
                @Net @Q(bits = 33, min = 0f, max = 1f) var value: Float = 0f,
            )
            """,
        )

        val diagnostic = run.errorDiagnostics.single()
        assertTrue(diagnostic.message.startsWith(QUANTISATION_RULE), diagnostic.message)
        assertTrue("33" in diagnostic.message, diagnostic.message)
        assertTrue("1..32" in diagnostic.message, diagnostic.message)
        assertEquals("Fixture.kt", diagnostic.file, "reported at ${diagnostic.position}")
        assertEquals(emptyList(), run.generatedFiles)
    }

    @Test
    fun `an inverted range is refused rather than clamping everything to one value`(
        @TempDir workDir: File,
    ) {
        val run = run(
            workDir,
            """
            @Replicated
            class Backwards(
                @Net @Q(bits = 8, min = 1f, max = 0f) var value: Float = 0f,
            )
            """,
        )

        val message = run.errorDiagnostics.single().message
        assertTrue(message.startsWith(QUANTISATION_RULE), message)
        assertTrue("min" in message && "max" in message, message)
        assertEquals(emptyList(), run.generatedFiles)
    }

    @Test
    fun `zero bits is refused`(@TempDir workDir: File) {
        val run = run(
            workDir,
            """
            @Replicated
            class Empty(
                @Net @Q(bits = 0, min = 0f, max = 1f) var value: Float = 0f,
            )
            """,
        )

        val message = run.errorDiagnostics.single().message
        assertTrue(message.startsWith(QUANTISATION_RULE), message)
        assertTrue("1..32" in message, message)
        assertEquals(emptyList(), run.generatedFiles)
    }

    private companion object {
        /**
         * Every malformed-`@Q` error carries this id, and the assertions above check the
         * *prefix* rather than merely containing it: a rule id is only usable by a CI filter
         * or an editor if it is where the id always is, which is the front of the message.
         *
         * Spelled out rather than read from `UdeaRules` on purpose. Reading the constant would
         * make the test agree with the producer by construction even if both moved to
         * `UDEA9999`, and the point of pinning an id is that it cannot move.
         */
        const val QUANTISATION_RULE: String = "UDEA0007: "
    }
}
