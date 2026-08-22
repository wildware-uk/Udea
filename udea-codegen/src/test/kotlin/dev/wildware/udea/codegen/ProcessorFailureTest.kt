package dev.wildware.udea.codegen

import dev.wildware.udea.diagnostics.UdeaRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A component the processor cannot handle **fails the build**; it is never skipped.
 *
 * The generator this replaces wrapped each symbol in `catch (e: Exception)` and logged the
 * failure, so an unsupported component became a warning line plus a missing serializer, and the
 * first anyone heard of it was an entity that did not replicate at runtime. Every test here
 * asserts the two halves of the replacement policy together: an `error` naming the exact class
 * and property, **and** no generated file.
 */
class ProcessorFailureTest {

    @Test
    fun `an unsupported property type errors and generates nothing`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "Unsupported.kt" to """
                    package fixtures

                    import dev.wildware.udea.annotations.Net
                    import dev.wildware.udea.annotations.Replicated

                    @Replicated
                    class Nameplate(
                        @Net var label: String = "",
                    )
                """.trimIndent(),
            ),
        )

        val message = run.errors.single()
        assertTrue("Nameplate" in message, "the error must name the class: $message")
        assertTrue("label" in message, "the error must name the property: $message")
        assertTrue("kotlin.String" in message, "the error must name the offending type: $message")
        assertEquals(emptyList(), run.warnings, "a failure is an error, never a warning")
        assertEquals(
            emptyList(),
            run.generatedFiles,
            "a component with an error must produce no file at all, not a partial one",
        )
        assertFalse(run.succeeded, "an unsupported property type must fail the build")
    }

    @Test
    fun `@Net on a val is reported under UDEA0001`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "NetOnVal.kt" to """
                    package fixtures

                    import dev.wildware.udea.annotations.Net
                    import dev.wildware.udea.annotations.Replicated

                    @Replicated
                    class Shield(
                        @Net val capacity: Float = 1f,
                    )
                """.trimIndent(),
            ),
        )

        val message = run.errors.single()
        assertTrue(
            message.startsWith(UdeaRules.NET_ON_VAL.id),
            "the error must carry the stable rule id ${UdeaRules.NET_ON_VAL.id}: $message",
        )
        assertTrue("Shield" in message && "capacity" in message, message)
        assertEquals(emptyList(), run.generatedFiles)
        assertFalse(run.succeeded)
    }

    @Test
    fun `more than 64 fields is reported under UDEA0002 and says to split`(@TempDir workDir: File) {
        val fields = (0 until UdeaRules.MAX_COMPONENT_FIELDS + 1)
            .joinToString("\n") { "    @Net var f%02d: Int = 0,".format(it) }
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "TooWide.kt" to """
                    package fixtures

                    import dev.wildware.udea.annotations.Net
                    import dev.wildware.udea.annotations.Replicated

                    @Replicated
                    class TooWide(
                    $fields
                    )
                """.trimIndent(),
            ),
        )

        val message = run.errors.single { UdeaRules.COMPONENT_FIELD_LIMIT.id in it }
        assertTrue("TooWide" in message, message)
        assertTrue("65" in message, "the error must say how many fields were declared: $message")
        assertTrue(
            "SPLIT" in message,
            "the error must direct the developer to split the component, not to widen the mask: $message",
        )
        assertEquals(emptyList(), run.generatedFiles)
        assertFalse(run.succeeded)
    }

    @Test
    fun `exactly 64 fields is accepted`(@TempDir workDir: File) {
        val fields = (0 until UdeaRules.MAX_COMPONENT_FIELDS)
            .joinToString("\n") { "    @Net var f%02d: Int = 0,".format(it) }
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "AtLimit.kt" to """
                    package fixtures

                    import dev.wildware.udea.annotations.Net
                    import dev.wildware.udea.annotations.Replicated

                    @Replicated
                    class AtLimit(
                    $fields
                    )
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList(), run.errors, "64 fields is the limit, not one past it")
        assertEquals(listOf("AtLimitReplicator.kt"), run.generatedFiles.map { it.name })
    }

    @Test
    fun `@Q on a non-float is reported under UDEA0003`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "QuantisedInt.kt" to """
                    package fixtures

                    import dev.wildware.udea.annotations.Net
                    import dev.wildware.udea.annotations.Q
                    import dev.wildware.udea.annotations.Replicated

                    @Replicated
                    class Ammo(
                        @Net @Q(bits = 8, min = 0f, max = 255f) var rounds: Int = 0,
                    )
                """.trimIndent(),
            ),
        )

        val message = run.errors.single()
        assertTrue(
            message.startsWith(UdeaRules.QUANTIZED_NON_FLOAT.id),
            "the error must carry the stable rule id ${UdeaRules.QUANTIZED_NON_FLOAT.id}: $message",
        )
        assertTrue("Ammo" in message && "rounds" in message, message)
        assertEquals(emptyList(), run.generatedFiles)
        assertFalse(run.succeeded)
    }

    @Test
    fun `one broken component does not suppress a healthy one`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "Mixed.kt" to """
                    package fixtures

                    import dev.wildware.udea.annotations.Net
                    import dev.wildware.udea.annotations.Replicated

                    @Replicated
                    class Broken(
                        @Net var label: String = "",
                    )

                    @Replicated
                    class Healthy(
                        @Net var charge: Float = 0f,
                    )
                """.trimIndent(),
            ),
        )

        // The build still fails, but the developer sees the one real problem rather than a
        // cascade, and the components that are fine still have their Replicators on disk.
        assertEquals(1, run.errors.size, "expected exactly one diagnostic, got ${run.errors}")
        assertTrue("Broken" in run.errors.single())
        assertEquals(listOf("HealthyReplicator.kt"), run.generatedFiles.map { it.name })
        assertFalse(run.succeeded)
    }
}
