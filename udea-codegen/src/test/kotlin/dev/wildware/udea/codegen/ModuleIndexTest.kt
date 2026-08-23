package dev.wildware.udea.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Cross-module discovery: one generated index object per module, plus the `META-INF/services`
 * line that lets `ServiceLoader` find it.
 *
 * **What this replaces.** The old generator listed a module's serializers by writing them as a
 * semicolon-joined string into an annotation on a class in the magic package
 * `dev.wildware._serializer_`, under a name containing `System.currentTimeMillis()`; read them
 * back with `Resolver.getDeclarationsFromPackage`; and, at run time, fell back to an
 * `org.reflections` classpath scan. None of that survives R8, none of it is deterministic, and
 * the timestamped name is why KSP incremental processing was off repository-wide.
 *
 * These tests run the real processor over throwaway sources, because the thing under test is
 * what the processor *emits* for a module — a file set and a resource path — and the fixture
 * source set can only show the configuration it is itself built with.
 */
class ModuleIndexTest {

    private val service = "dev.wildware.udea.net.NetModule"

    private fun sources(): Map<String, String> = mapOf(
        "Components.kt" to """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Zebra(@Net var stripes: Int = 0)

            @Replicated
            class Aardvark(@Net var snout: Float = 0f)
        """.trimIndent(),
    )

    /**
     * @param components the project's id space, which the build reads from the reviewed
     *   `net-components.lock` and which the processor requires of any module emitting a
     *   protocol. Supplied here whenever `udea.moduleName` is, exactly as the build does, so
     *   these tests run the configuration a real module runs in and not a fallback.
     */
    private fun run(
        workDir: File,
        options: Map<String, String>,
        sources: Map<String, String> = sources(),
        components: List<String> = DEFAULT_COMPONENTS,
    ): ProcessorHarness.Run = ProcessorHarness.run(
        workDir,
        sources,
        if (CodegenOptions.MODULE_NAME in options) {
            options + (CodegenOptions.PROJECT_COMPONENTS to components.joinToString(","))
        } else {
            options
        },
    )

    // --- the index and its service file ------------------------------------------------------

    @Test
    fun `a module emits one index class naming its replicators statically`(@TempDir workDir: File) {
        val run = run(
            workDir,
            mapOf(
                CodegenOptions.MODULE_NAME to "Moba",
                CodegenOptions.NET_MODULE_SERVICE to service,
            ),
        )

        assertEquals(emptyList(), run.errors)
        val index = run.generatedSource("MobaNetModule.kt")
        // A class, not an object: ServiceLoader constructs a classpath provider through its
        // public no-arg constructor, which a Kotlin object does not have.
        // `GeneratedNetModuleServiceTest` is the leg that actually loads one.
        assertTrue("public class MobaNetModule : NetModule" in index, index)
        assertTrue("moduleName: String = \"Moba\"" in index, index)
        // Ascending type id, which is ascending name: Aardvark is 0 and Zebra is 1.
        assertTrue(
            "listOf(AardvarkReplicator, ZebraReplicator)" in index,
            "the index must name its members statically, in id order:\n$index",
        )
    }

    @Test
    fun `the service resource is at the path ServiceLoader actually reads`(@TempDir workDir: File) {
        // The one assertion that catches the mistake with no symptom: a services file written
        // one directory out loads nothing, silently, with a green build.
        val run = run(
            workDir,
            mapOf(
                CodegenOptions.MODULE_NAME to "Moba",
                CodegenOptions.NET_MODULE_SERVICE to service,
            ),
        )

        assertEquals(
            "dev.wildware.udea.generated.MobaNetModule\n",
            run.generatedResources["META-INF/services/$service"],
            "generated resources were ${run.generatedResources.keys}",
        )
    }

    @Test
    fun `no generated name or resource path carries a timestamp or any other varying part`(
        @TempDir first: File,
        @TempDir second: File,
    ) {
        val options = mapOf(
            CodegenOptions.MODULE_NAME to "Moba",
            CodegenOptions.NET_MODULE_SERVICE to service,
        )
        val a = run(first, options)
        val b = run(second, options)

        val names = a.generatedFiles.map { it.name } + a.generatedResources.keys
        assertEquals(
            names,
            b.generatedFiles.map { it.name } + b.generatedResources.keys,
            "two runs over identical sources produced different names",
        )
        for (name in names) {
            assertFalse(
                VARYING.containsMatchIn(name),
                "'$name' contains a digit run that looks like a timestamp; the generator this " +
                    "replaces named its index UdeaSerializerRegistry_<currentTimeMillis>, which " +
                    "is what made incremental processing impossible",
            )
        }
    }

    // --- the protocol constant ---------------------------------------------------------------

    @Test
    fun `the protocol hash is a folded constant and the lock file is emitted beside it`(
        @TempDir workDir: File,
    ) {
        val run = run(workDir, mapOf(CodegenOptions.MODULE_NAME to "Moba"))

        val protocol = run.generatedSource("MobaNetProtocol.kt")
        assertTrue(Regex("""const val HASH: Int = 0x[0-9a-f]{4}""").containsMatchIn(protocol), protocol)
        assertTrue("const val COMPONENT_COUNT: Int = 2" in protocol, protocol)

        val lock = run.generatedResources.getValue(LOCK)
        assertTrue("component 0 fixtures.Aardvark" in lock, lock)
        assertTrue("component 1 fixtures.Zebra" in lock, lock)
        assertTrue("field 0 snout f32:32" in lock, lock)
    }

    @Test
    fun `adding a component renumbers its successors and moves the protocol hash`(
        @TempDir before: File,
        @TempDir after: File,
    ) {
        // The stated cost of dense ids, and the reason the lock is checked in: this is a
        // wire-breaking change and it has to be visible in a reviewed diff.
        val options = mapOf(CodegenOptions.MODULE_NAME to "Moba")
        val original = run(before, options)
        val extended = run(
            after,
            options,
            mapOf(
                "Components.kt" to sources().getValue("Components.kt") + """

                    @Replicated
                    class Mongoose(@Net var alertness: Float = 0f)
                """.trimIndent(),
            ),
            components = listOf("fixtures.Aardvark", "fixtures.Mongoose", "fixtures.Zebra"),
        )

        assertTrue("component 1 fixtures.Zebra" in original.generatedResources.getValue(LOCK))
        assertTrue("component 2 fixtures.Zebra" in extended.generatedResources.getValue(LOCK))
        assertFalse(
            hashOf(original.generatedSource("MobaNetProtocol.kt")) ==
                hashOf(extended.generatedSource("MobaNetProtocol.kt")),
            "adding a component must move the protocol hash",
        )
    }

    @Test
    fun `widening a quantised range moves the protocol hash, though no bit moves`(
        @TempDir before: File,
        @TempDir after: File,
    ) {
        // The wire break with no bit-level symptom. `@Q(bits = 12, ...)` costs 12 bits either
        // way; `min` and `max` are what those 12 bits *mean*, because writeFixed maps
        // [min, max] onto 0 until 2^bits. A lock that recorded only `q:12` hashed identically
        // across this change, so a server and a client built either side of it agreed on
        // connect and then decoded every rotation through a different affine map.
        val narrow = quantised(min = "-3.1416f", max = "3.1416f")
        val wide = quantised(min = "-100f", max = "100f")

        val original = run(before, OPTIONS, narrow, SPIN)
        val changed = run(after, OPTIONS, wide, SPIN)

        assertEquals(emptyList(), changed.errors)
        assertNotEquals(
            original.generatedResources.getValue(LOCK),
            changed.generatedResources.getValue(LOCK),
            "the lock must record the range, not only the width",
        )
        assertNotEquals(
            hashOf(original.generatedSource("MobaNetProtocol.kt")),
            hashOf(changed.generatedSource("MobaNetProtocol.kt")),
            "a @Q range change reinterprets every packet and must move protoHash",
        )
    }

    @Test
    fun `reordering an enum's constants moves the protocol hash, though no bit moves`(
        @TempDir before: File,
        @TempDir after: File,
    ) {
        // Capture writes `.ordinal` and apply reads `entries[ordinal]`, so the constant *order*
        // is the mapping on the wire. Both spellings below are `enum:32`; the generated
        // read-side range check only catches a shrunk enum, never a permuted one.
        val original = run(before, OPTIONS, enumerated("Standing, Crouching, Sprinting"), POSTURE)
        val permuted = run(after, OPTIONS, enumerated("Crouching, Standing, Sprinting"), POSTURE)

        assertEquals(emptyList(), permuted.errors)
        assertNotEquals(
            original.generatedResources.getValue(LOCK),
            permuted.generatedResources.getValue(LOCK),
            "the lock must record the constants, not only that the field is an enum",
        )
        assertNotEquals(
            hashOf(original.generatedSource("MobaNetProtocol.kt")),
            hashOf(permuted.generatedSource("MobaNetProtocol.kt")),
            "reordering enum constants remaps every ordinal and must move protoHash",
        )
    }

    private fun quantised(min: String, max: String): Map<String, String> = mapOf(
        "Components.kt" to """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Q
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Spin(@Net @Q(bits = 12, min = $min, max = $max) var rotation: Float = 0f)
        """.trimIndent(),
    )

    private fun enumerated(constants: String): Map<String, String> {
        val first = constants.substringBefore(',').trim()
        return mapOf(
            "Components.kt" to """
                package fixtures

                import dev.wildware.udea.annotations.Net
                import dev.wildware.udea.annotations.Replicated

                enum class Stance { $constants }

                @Replicated
                class Posture(@Net var stance: Stance = Stance.$first)
            """.trimIndent(),
        )
    }

    // --- the gates ----------------------------------------------------------------------------

    @Test
    fun `a module without the option emits replicators and nothing module-level`(
        @TempDir workDir: File,
    ) {
        // The configuration `udea-codegen`'s own harness runs in, and the one a library module
        // that contributes components but is not the game runs in.
        val run = run(workDir, emptyMap())

        assertEquals(emptyList(), run.errors)
        assertEquals(
            listOf("AardvarkReplicator.kt", "ZebraReplicator.kt"),
            run.generatedFiles.map { it.name },
        )
        assertEquals(emptyMap(), run.generatedResources)
    }

    @Test
    fun `the index is emitted only when the module actually has the service on its classpath`(
        @TempDir workDir: File,
    ) {
        // Generated code may only implement an interface that exists. Emitting the index
        // unconditionally would make every module that contributes a component fail to compile
        // unless it depended on udea-net.
        val run = run(workDir, mapOf(CodegenOptions.MODULE_NAME to "Moba"))

        assertEquals(emptyList(), run.errors)
        assertFalse(
            run.generatedFiles.any { it.name == "MobaNetModule.kt" },
            "the index must not be emitted without ${CodegenOptions.NET_MODULE_SERVICE}",
        )
        assertTrue(run.generatedResources.keys.none { it.startsWith("META-INF/") })
    }

    @Test
    fun `a module name that cannot be part of an object name fails the build, naming the rule`(
        @TempDir workDir: File,
    ) {
        // Silently sanitising `my-game` to `MyGame` would make the generated object's name
        // depend on a rule nobody can see, and two modules could sanitise onto one name.
        val run = run(workDir, mapOf(CodegenOptions.MODULE_NAME to "my-game"))

        val message = run.errors.single()
        assertTrue("my-game" in message, message)
        assertTrue(CodegenOptions.MODULE_NAME_FORMAT.pattern in message, message)
        assertFalse(run.succeeded)
    }

    private fun hashOf(protocolSource: String): String =
        Regex("""HASH: Int = (0x[0-9a-f]{4})""").find(protocolSource)?.groupValues?.get(1)
            ?: error("no HASH constant in:\n$protocolSource")

    private companion object {
        const val LOCK = "udea/Moba-net-protocol.lock"

        val OPTIONS = mapOf(CodegenOptions.MODULE_NAME to "Moba")

        /** The id space for [sources]; the single-module case, where it equals the module. */
        val DEFAULT_COMPONENTS = listOf("fixtures.Aardvark", "fixtures.Zebra")
        val SPIN = listOf("fixtures.Spin")
        val POSTURE = listOf("fixtures.Posture")

        /** Four or more consecutive digits: a timestamp, a hash or a round number. */
        val VARYING = Regex("""\d{4,}""")
    }
}
