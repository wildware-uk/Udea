package dev.wildware.udea.codegen

import dev.wildware.udea.diagnostics.UdeaRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The processor's gizmo pass over throwaway sources (issue #233): what a handle annotation it
 * cannot use costs the build, and what the two halves of a game - its module and its editor
 * source set - each generate.
 *
 * A misspelled field in `@PositionHandle(x = "...")` is invisible to the Kotlin compiler, because it
 * is a string. So the failure path is the one that matters here: `UDEA0017`, at the annotation, with
 * a did-you-mean, and no gizmo generated.
 */
class GizmoProcessorTest {

    private fun lineOf(source: String, predicate: (String) -> Boolean): Int =
        source.lineSequence().indexOfFirst { predicate(it.trim()) }
            .also { check(it >= 0) { "no line matched in:\n$source" } } + 1

    /** A Fleks component called [name] with [body] as its constructor, annotated with [annotations]. */
    private fun component(name: String, body: String, annotations: String = ""): String = """
        package fixtures

        import com.github.quillraven.fleks.Component
        import com.github.quillraven.fleks.ComponentType
        import dev.wildware.udea.annotations.PositionHandle
        import dev.wildware.udea.annotations.RadiusHandle
        import dev.wildware.udea.annotations.RangeHandle
        import dev.wildware.udea.annotations.RotationHandle
        import dev.wildware.udea.annotations.SizeHandle

        $annotations
        class $name($body) : Component<$name> {
            override fun type(): ComponentType<$name> = $name
            companion object : ComponentType<$name>()
        }
    """.trimIndent()

    /** The options an editor source set's run gets: a gizmo registry to write, and nothing else. */
    private val editorRun = mapOf(CodegenOptions.GIZMO_REGISTRY to "Harness")

    // --- UDEA0017 -------------------------------------------------------------------------------

    @Test
    fun `a misspelled field is UDEA0017 at the annotation, with a did-you-mean, and generates nothing`(
        @TempDir workDir: File,
    ) {
        val source = component("Mover", "var x: Float = 0f, var y: Float = 0f", "@PositionHandle(x = \"xx\")")
        val run = ProcessorHarness.run(workDir, mapOf("Mover.kt" to source), editorRun)

        val error = run.errorDiagnostics.single()
        assertTrue(error.message.startsWith("${UdeaRules.GIZMO_HANDLE_FIELD.id}:"), error.message)
        assertTrue("fixtures.Mover" in error.message, "the error must name the class: ${error.message}")
        assertTrue("'xx'" in error.message, "the error must quote the name it could not find: ${error.message}")
        assertTrue("did you mean 'x'?" in error.message, "the did-you-mean is mandatory: ${error.message}")
        assertEquals("Mover.kt:${lineOf(source) { it.startsWith("@PositionHandle") }}", error.position)
        assertEquals(emptyList(), run.generatedFiles, "a component with a bad handle must generate nothing")
        assertFalse(run.succeeded)
    }

    @Test
    fun `a field with nothing close to it lists the floats the component does have`(@TempDir workDir: File) {
        val source = component("Mover", "var left: Float = 0f, var top: Float = 0f", "@PositionHandle")
        val run = ProcessorHarness.run(workDir, mapOf("Mover.kt" to source), editorRun)

        // Both defaults are missing: one error each, and each names what is there.
        assertEquals(2, run.errors.size, "${run.errors}")
        for (message in run.errors) {
            assertTrue(message.startsWith(UdeaRules.GIZMO_HANDLE_FIELD.id), message)
            assertTrue("left, top" in message, "no suggestion, so the floats it has are listed: $message")
        }
    }

    @Test
    fun `a field that is not a float is UDEA0017, naming its type`(@TempDir workDir: File) {
        val source = component("Mover", "var x: Float = 0f, var y: Int = 0", "@PositionHandle")
        val run = ProcessorHarness.run(workDir, mapOf("Mover.kt" to source), editorRun)

        val message = run.errors.single()
        assertTrue(message.startsWith(UdeaRules.GIZMO_HANDLE_FIELD.id), message)
        assertTrue("'y'" in message && "kotlin.Int" in message, message)
        assertFalse(run.succeeded)
    }

    @Test
    fun `a field that is a val is UDEA0017, because a drag writes it`(@TempDir workDir: File) {
        val source = component("Turret", "val rotation: Float = 0f", "@RotationHandle")
        val run = ProcessorHarness.run(workDir, mapOf("Turret.kt" to source), editorRun)

        val message = run.errors.single()
        assertTrue(message.startsWith(UdeaRules.GIZMO_HANDLE_FIELD.id), message)
        assertTrue("'rotation'" in message && "val" in message, message)
    }

    @Test
    fun `a one-field handle on a property that is not a float var is UDEA0017 at the property`(@TempDir workDir: File) {
        val source = component("Tower", "@RangeHandle var range: Int = 0")
        val run = ProcessorHarness.run(workDir, mapOf("Tower.kt" to source), editorRun)

        val error = run.errorDiagnostics.single()
        assertTrue(error.message.startsWith(UdeaRules.GIZMO_HANDLE_FIELD.id), error.message)
        assertTrue("@RangeHandle" in error.message && "'range'" in error.message, error.message)
        assertEquals("Tower.kt:${lineOf(source) { it.startsWith("class Tower") }}", error.position)
    }

    @Test
    fun `naming no field for a required axis, or one field twice, is UDEA0017`(@TempDir workDir: File) {
        val source = component(
            "Box",
            "var w: Float = 0f, var h: Float = 0f",
            "@SizeHandle(width = \"\", height = \"h\") @PositionHandle(x = \"w\", y = \"w\")",
        )
        val run = ProcessorHarness.run(workDir, mapOf("Box.kt" to source), editorRun)

        assertEquals(2, run.errors.size, "${run.errors}")
        assertTrue(run.errors.all { it.startsWith(UdeaRules.GIZMO_HANDLE_FIELD.id) }, "${run.errors}")
        assertTrue(run.errors.any { "width" in it && "names no field" in it }, "${run.errors}")
        assertTrue(run.errors.any { "'w'" in it && "twice" in it }, "${run.errors}")
    }

    @Test
    fun `a handle on a class that is not a Fleks component is UDEA0017`(@TempDir workDir: File) {
        val source = """
            package fixtures

            import dev.wildware.udea.annotations.PositionHandle

            @PositionHandle
            class Loose(var x: Float = 0f, var y: Float = 0f)
        """.trimIndent()
        val run = ProcessorHarness.run(workDir, mapOf("Loose.kt" to source), editorRun)

        val message = run.errors.single()
        assertTrue(message.startsWith(UdeaRules.GIZMO_HANDLE_FIELD.id), message)
        assertTrue("Component" in message && "ComponentType" in message, message)
    }

    @Test
    fun `a handle in a module that neither lists nor generates gizmos is an error rather than a handle nobody sees`(
        @TempDir workDir: File,
    ) {
        val source = component("Mover", "var x: Float = 0f, var y: Float = 0f", "@PositionHandle")
        val run = ProcessorHarness.run(workDir, mapOf("Mover.kt" to source))

        val message = run.errors.single()
        assertTrue(CodegenOptions.MODULE_NAME in message && CodegenOptions.GIZMO_REGISTRY in message, message)
        assertFalse(run.succeeded)
    }

    // --- the module half: the index ---------------------------------------------------------------

    @Test
    fun `a module lists its handle components on its registry, sorted, and generates no gizmo`(
        @TempDir workDir: File,
    ) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "Zeta.kt" to component("Zeta", "@RadiusHandle var r: Float = 0f"),
                "Alpha.kt" to component("Alpha", "var x: Float = 0f, var y: Float = 0f", "@PositionHandle"),
                "Plain.kt" to component("Plain", "var x: Float = 0f"),
            ),
            mapOf(CodegenOptions.MODULE_NAME to "Moba", CodegenOptions.REGISTRY_MODULES to "Moba"),
        )

        assertTrue(run.succeeded, "${run.errors}")
        val registry = run.generatedSource("MobaModuleRegistry.kt")
        assertTrue(
            "@HandleIndex(components = [Alpha::class, Zeta::class])" in registry,
            "the index must list exactly the handle components, sorted:\n$registry",
        )
        assertTrue(run.generatedFiles.none { it.name.endsWith("Gizmo.kt") }, "a module's run generated a gizmo: ${run.generatedFiles}")
    }

    @Test
    fun `a module with no handles puts no index on its registry`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf("Plain.kt" to component("Plain", "var x: Float = 0f")),
            mapOf(CodegenOptions.MODULE_NAME to "Moba", CodegenOptions.REGISTRY_MODULES to "Moba"),
        )

        assertTrue(run.succeeded, "${run.errors}")
        assertFalse("HandleIndex" in run.generatedSource("MobaModuleRegistry.kt"))
    }

    // --- the editor half: gizmos and the registry ---------------------------------------------------

    @Test
    fun `an editor run generates one gizmo per annotation and a registry listing them with the hand-written ones`(
        @TempDir workDir: File,
    ) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "Mover.kt" to component(
                    "Mover",
                    "var x: Float = 0f, var y: Float = 0f, @RadiusHandle var r: Float = 1f",
                    "@PositionHandle",
                ),
                "Custom.kt" to """
                    package fixtures

                    import com.github.quillraven.fleks.ComponentType
                    import dev.wildware.udea.editor.gizmo.Gizmo
                    import dev.wildware.udea.editor.gizmo.GizmoScope
                    import dev.wildware.udea.editor.gizmo.GizmoTarget

                    class Custom : Gizmo<Mover> {
                        override val component: ComponentType<Mover> = Mover
                        override fun GizmoScope<Mover>.build(target: GizmoTarget<Mover>) = Unit
                    }

                    object Another : Gizmo<Mover> {
                        override val component: ComponentType<Mover> = Mover
                        override fun GizmoScope<Mover>.build(target: GizmoTarget<Mover>) = Unit
                    }

                    private object Hidden : Gizmo<Mover> {
                        override val component: ComponentType<Mover> = Mover
                        override fun GizmoScope<Mover>.build(target: GizmoTarget<Mover>) = Unit
                    }

                    abstract class Base : Gizmo<Mover>
                """.trimIndent(),
            ),
            editorRun,
        )

        assertTrue(run.succeeded, "${run.errors}")
        assertEquals(
            listOf("HarnessGizmoRegistry.kt", "MoverPositionGizmo.kt", "MoverRRadiusGizmo.kt"),
            run.generatedFiles.map { it.name }.sorted(),
        )
        val registry = run.generatedSource("HarnessGizmoRegistry.kt").replace(Regex("""\s+"""), " ")
        assertTrue(
            "listOf(Another, Custom(), MoverPositionGizmo, MoverRRadiusGizmo)" in registry,
            "the registry must list every listable gizmo, objects as themselves, classes constructed, by name:\n$registry",
        )
        assertFalse("Hidden" in registry || "Base" in registry, "a private or abstract gizmo was listed:\n$registry")
    }

    @Test
    fun `a hand-written gizmo the registry cannot construct is an error at the class`(@TempDir workDir: File) {
        val source = """
            package fixtures

            import com.github.quillraven.fleks.Component
            import com.github.quillraven.fleks.ComponentType
            import dev.wildware.udea.editor.gizmo.Gizmo
            import dev.wildware.udea.editor.gizmo.GizmoScope
            import dev.wildware.udea.editor.gizmo.GizmoTarget

            class Thing(var v: Float = 0f) : Component<Thing> {
                override fun type(): ComponentType<Thing> = Thing
                companion object : ComponentType<Thing>()
            }

            class Needy(val scale: Float) : Gizmo<Thing> {
                override val component: ComponentType<Thing> = Thing
                override fun GizmoScope<Thing>.build(target: GizmoTarget<Thing>) = Unit
            }
        """.trimIndent()
        val run = ProcessorHarness.run(workDir, mapOf("Needy.kt" to source), editorRun)

        val error = run.errorDiagnostics.single()
        assertTrue("fixtures.Needy" in error.message && "no-argument constructor" in error.message, error.message)
        assertEquals("Needy.kt:${lineOf(source) { it.startsWith("class Needy") }}", error.position)
    }

    @Test
    fun `an editor run generates the gizmos another module indexed, reading the compiled registry`(
        @TempDir workDir: File,
    ) {
        // `CodegenFixturesModuleRegistry` is compiled into this test classpath by `kspTest`, with an
        // index naming `fixtures.Beacon` and `fixtures.Crate`: exactly the shape of `:moba:game`'s
        // registry as `:moba:desktop`'s editor source set sees it.
        val run = ProcessorHarness.run(
            workDir,
            mapOf("Nothing.kt" to "package elsewhere\n\nclass Nothing\n"),
            editorRun + (CodegenOptions.REGISTRY_MODULES to "CodegenFixtures"),
        )

        assertTrue(run.succeeded, "${run.errors}")
        assertEquals(
            listOf(
                "BeaconPositionGizmo.kt",
                "BeaconReachRadiusGizmo.kt",
                "BeaconSightRangeGizmo.kt",
                "CratePositionGizmo.kt",
                "CrateRotationGizmo.kt",
                "CrateSizeGizmo.kt",
                "HarnessGizmoRegistry.kt",
            ),
            run.generatedFiles.map { it.name }.sorted(),
        )
    }

    @Test
    fun `a listed module whose registry is not on the classpath fails the editor run, naming it`(
        @TempDir workDir: File,
    ) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf("Nothing.kt" to "package elsewhere\n\nclass Nothing\n"),
            editorRun + (CodegenOptions.REGISTRY_MODULES to "Nowhere"),
        )

        val message = run.errors.single()
        assertTrue("NowhereModuleRegistry" in message, message)
        assertFalse(run.succeeded)
    }
}
