package dev.wildware.udea.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The generated list of components a level file can hold (issue #191).
 *
 * A level refuses to save a world holding a component no generated list names, so what this list
 * leaves out is what a user finds out about at the moment they press save. These run the real
 * processor over throwaway sources, as [ModuleIndexTest] does and for its reason: the claim is
 * about what the processor emits for a module - a class and a resource path - not about a fixture.
 */
class LevelIndexTest {

    private val service = "dev.wildware.udea.core.level.LevelComponentModule"

    private val options = mapOf(CodegenOptions.MODULE_NAME to "Moba", CodegenOptions.PROJECT_COMPONENTS to "")

    private fun source(body: String): Map<String, String> = mapOf(
        "Components.kt" to (
            """
            package fixtures

            import com.github.quillraven.fleks.Component
            import com.github.quillraven.fleks.ComponentType
            import kotlinx.serialization.Serializable

            """.trimIndent() + "\n" + body.trimIndent()
            ),
    )

    private fun component(name: String, annotations: String = "@Serializable", modifiers: String = ""): String = """
        $annotations
        ${modifiers}class $name(var value: Int = 0) : Component<$name> {
            override fun type(): ComponentType<$name> = $name
            companion object : ComponentType<$name>()
        }
    """.trimIndent()

    @Test
    fun `every serializable Fleks component is listed, in ascending name order, and nothing else is`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(
            workDir,
            source(
                listOf(
                    component("Zebra"),
                    component("Aardvark"),
                    component("Unsaved", annotations = ""),
                    "@Serializable\nclass NotAComponent(val value: Int = 0)",
                ).joinToString("\n\n"),
            ),
            options,
        )

        assertEquals(emptyList(), run.errors)
        val index = run.generatedSource("MobaLevelComponents.kt")
        assertTrue("public class MobaLevelComponents : LevelComponentModule" in index, index)
        assertTrue("moduleName: String = \"Moba\"" in index, index)
        assertTrue(
            "listOf(LevelComponent(Aardvark::class, Aardvark.serializer()), " +
                "LevelComponent(Zebra::class, Zebra.serializer()))" in index,
            "the list must name exactly the serializable components, statically, by name:\n$index",
        )
        assertEquals(
            "dev.wildware.udea.generated.MobaLevelComponents\n",
            run.generatedResources["META-INF/services/$service"],
            "generated resources were ${run.generatedResources.keys}",
        )
    }

    @Test
    fun `a module with no serializable component emits no level list`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(workDir, source(component("Unsaved", annotations = "")), options)

        assertEquals(emptyList(), run.errors)
        assertTrue(run.generatedFiles.none { it.name == "MobaLevelComponents.kt" }, "${run.generatedFiles}")
        assertTrue("META-INF/services/$service" !in run.generatedResources, "${run.generatedResources.keys}")
    }

    @Test
    fun `a serializable component the list cannot name is a located build error`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(workDir, source(component("Hidden", modifiers = "private ")), options)

        val error = run.errorDiagnostics.single()
        assertTrue("fixtures.Hidden" in error.message && "it is private" in error.message, error.message)
        assertEquals("Components.kt", error.file, "the error is not located at the class: ${error.position}")
    }

    @Test
    fun `serializable components in a module with no module name are a build error, not an empty list`(
        @TempDir workDir: File,
    ) {
        val run = ProcessorHarness.run(workDir, source(component("Orphan")))

        assertTrue(
            run.errors.any { CodegenOptions.MODULE_NAME in it && "level" in it },
            "expected the missing module name to be reported, got ${run.errors}",
        )
    }
}
