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

    /**
     * The 1-based line of the first line of [source] satisfying [predicate], trimmed.
     *
     * Derived from the fixture text rather than written as a literal, so reflowing a fixture
     * does not break the assertion — while a processor that attached the diagnostic to a
     * different symbol, or to none, still fails it.
     */
    private fun lineOf(source: String, predicate: (String) -> Boolean): Int =
        source.lineSequence().indexOfFirst { predicate(it.trim()) }
            .also { check(it >= 0) { "no line matched in:\n$source" } } + 1

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
        assertTrue(
            message.startsWith(UdeaRules.UNSUPPORTED_FIELD_TYPE.id),
            "the error must carry the stable rule id ${UdeaRules.UNSUPPORTED_FIELD_TYPE.id}: $message",
        )
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
    fun `@Net on a val is reported under UDEA0001, at the property`(@TempDir workDir: File) {
        val source = """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Shield(
                @Net val capacity: Float = 1f,
            )
        """.trimIndent()
        val run = ProcessorHarness.run(workDir, mapOf("NetOnVal.kt" to source))

        val diagnostic = run.errorDiagnostics.single()
        assertTrue(
            diagnostic.message.startsWith(UdeaRules.NET_ON_VAL.id),
            "the error must carry the stable rule id ${UdeaRules.NET_ON_VAL.id}: ${diagnostic.message}",
        )
        assertTrue("Shield" in diagnostic.message && "capacity" in diagnostic.message, diagnostic.message)

        // Loud is only half of the claim. The message text is assembled from the owner and
        // property *names*, so it is byte-identical whether the diagnostic is attached to the
        // property, to the class, or to nothing at all — and only the attached symbol decides
        // what file and line the compiler prints in front of it. Located is asserted here.
        assertEquals("NetOnVal.kt", diagnostic.file, "reported at ${diagnostic.position}")
        assertEquals(
            lineOf(source) { "val capacity" in it },
            diagnostic.line,
            "UDEA0001 must land on the capacity property, not the class and not nowhere; " +
                "it landed at ${diagnostic.position}",
        )

        assertEquals(emptyList(), run.generatedFiles)
        assertFalse(run.succeeded)
    }

    @Test
    fun `@Sim on a val is reported under UDEA0005, at the property`(@TempDir workDir: File) {
        // The snapshot half of UDEA0001. It carried no id at all until UDEA0005 was registered,
        // which meant the one defect a developer could not suppress or filter in CI sat next to
        // three that they could. The id is asserted here rather than only the prose, because
        // the prose is what the stability contract says may be reworded freely.
        val source = """
            package fixtures

            import dev.wildware.udea.annotations.Replicated
            import dev.wildware.udea.annotations.Sim

            @Replicated
            class Blackboard(
                @Sim val respawnAt: Long = 0L,
            )
        """.trimIndent()
        val run = ProcessorHarness.run(workDir, mapOf("SimOnVal.kt" to source))

        val diagnostic = run.errorDiagnostics.single()
        assertTrue(
            diagnostic.message.startsWith(UdeaRules.SIM_ON_VAL.id),
            "the error must carry the stable rule id ${UdeaRules.SIM_ON_VAL.id}: ${diagnostic.message}",
        )
        assertTrue(
            "@Sim" in diagnostic.message,
            "the error must name the annotation that is wrong, not @Net: ${diagnostic.message}",
        )
        assertFalse(
            UdeaRules.NET_ON_VAL.id in diagnostic.message,
            "a @Sim defect must not be reported under the @Net rule id: ${diagnostic.message}",
        )
        assertTrue(
            "Blackboard" in diagnostic.message && "respawnAt" in diagnostic.message,
            diagnostic.message,
        )

        assertEquals("SimOnVal.kt", diagnostic.file, "reported at ${diagnostic.position}")
        assertEquals(
            lineOf(source) { "val respawnAt" in it },
            diagnostic.line,
            "UDEA0005 must land on the respawnAt property; it landed at ${diagnostic.position}",
        )

        assertEquals(emptyList(), run.generatedFiles)
        assertFalse(run.succeeded)
    }

    @Test
    fun `both @Net and @Sim on one property is rejected`(@TempDir workDir: File) {
        // The realistic path is a demotion: a developer adds @Sim to stop a field reaching
        // clients and forgets to delete @Net. Accepting the pair and letting @Net win makes
        // that a silent no-op that fails in the leaking direction — the field keeps
        // replicating with a green build, which is the exact case the two-mask split exists
        // for (spec 3.1: jungle respawn timers and bot blackboards).
        val source = """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated
            import dev.wildware.udea.annotations.Sim

            @Replicated
            class Jungle(
                @Net @Sim var respawnTick: Long = 0L,
            )
        """.trimIndent()
        val run = ProcessorHarness.run(workDir, mapOf("Jungle.kt" to source))

        val diagnostic = run.errorDiagnostics.single()
        assertTrue("Jungle" in diagnostic.message, diagnostic.message)
        assertTrue("respawnTick" in diagnostic.message, diagnostic.message)
        assertTrue("@Net" in diagnostic.message && "@Sim" in diagnostic.message, diagnostic.message)
        assertEquals("Jungle.kt", diagnostic.file)
        assertEquals(
            lineOf(source) { "respawnTick" in it },
            diagnostic.line,
            "reported at ${diagnostic.position}",
        )
        assertEquals(
            emptyList(),
            run.generatedFiles,
            "the field must not silently keep replicating: no file at all",
        )
        assertFalse(run.succeeded)
    }

    @Test
    fun `more than 64 fields is reported under UDEA0002, at the class, and says to split`(
        @TempDir workDir: File,
    ) {
        val fields = (0 until UdeaRules.MAX_COMPONENT_FIELDS + 1)
            .joinToString("\n") { "    @Net var f%02d: Int = 0,".format(it) }
        val source = """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class TooWide(
            $fields
            )
        """.trimIndent()
        val run = ProcessorHarness.run(workDir, mapOf("TooWide.kt" to source))

        val diagnostic = run.errorDiagnostics.single { UdeaRules.COMPONENT_FIELD_LIMIT.id in it.message }
        assertTrue("TooWide" in diagnostic.message, diagnostic.message)
        assertTrue(
            "65" in diagnostic.message,
            "the error must say how many fields were declared: ${diagnostic.message}",
        )
        assertTrue(
            "SPLIT" in diagnostic.message,
            "the error must direct the developer to split the component, not to widen the mask: " +
                diagnostic.message,
        )

        // The counterpart to the @Net-on-a-val position assertion: this defect is a property of
        // the component, so it must be reported at the class declaration and not at whichever
        // field happened to be the 65th.
        assertEquals("TooWide.kt", diagnostic.file, "reported at ${diagnostic.position}")
        assertEquals(
            lineOf(source) { it.startsWith("class TooWide") },
            diagnostic.line,
            "the field-count error must land on the class, not on a property; it landed at " +
                diagnostic.position,
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
