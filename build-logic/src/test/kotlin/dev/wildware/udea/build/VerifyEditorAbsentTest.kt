package dev.wildware.udea.build

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `udeaVerifyEditorAbsent` (`UDEA-MG-012`, issue #233), run against builds that really put a gizmo
 * class on a release classpath.
 *
 * `UDEA-MG-010` already fails `:udea-editor` as a dependency. What it cannot see is a class: a
 * source set's output added to the runtime classpath, or a gizmo packed into the jar, carries no
 * coordinate at all. These builds do exactly that, with real bytecode, and the gate has to go red
 * for each of them and green for the same build without the gizmo.
 */
class VerifyEditorAbsentTest {

    private val gate = "udea.module-graph-check"
    private val task = ":moba:udeaVerifyEditorAbsent"

    /** Writes a class called [name] implementing [interfaces] under [dir], as a compiler would. */
    private fun writeClass(dir: File, name: String, vararg interfaces: String) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", interfaces)
        writer.visitEnd()
        File(dir, "$name.class").apply { parentFile.mkdirs() }.writeBytes(writer.toByteArray())
    }

    /** A `:moba` that runs on the class directories [runtimeDirs] and whatever its jar packs. */
    private fun moba(root: File, runtimeDirs: List<String>, jarContent: Boolean = false): GradleFixture {
        val fixture = GradleFixture(root)
        fixture.project(
            "moba",
            gatedProject(
                gate,
                """
                dependencies { ${runtimeDirs.joinToString("\n") { "runtimeOnly(files(\"$it\"))" }} }
                ${if (jarContent) fixture.jarFrom() else ""}
                """.trimIndent(),
            ),
        )
        writeClass(File(root, "moba/clean"), "dev/wildware/moba/Main")
        return fixture
    }

    @Test
    fun `a gizmo on the release runtime classpath fails the gate, naming the class and where it was`(@TempDir root: File) {
        val fixture = moba(root, listOf("clean", "editor"))
        writeClass(File(root, "moba/editor"), "dev/wildware/moba/PositionPositionGizmo", EditorReleaseRules.GIZMO)

        val result = fixture.buildAndFail(task)

        assertTrue("UDEA-MG-012" in result.output, result.output)
        assertTrue("dev/wildware/moba/PositionPositionGizmo" in result.output, result.output)
        // Forward slashes on every platform, because `gateLocation` puts them there. Written as
        // the platform's own separator this line said nothing on the platform it was not run on,
        // and it turned every `windows-latest` job that runs these tests red for exactly that:
        // the gate was right and the assertion was about its author's machine.
        assertTrue("moba/editor" in result.output, "the failure must say where the class was:\n${result.output}")
    }

    @Test
    fun `the same build without the gizmo passes`(@TempDir root: File) {
        val fixture = moba(root, listOf("clean"))

        val result = fixture.build(task)

        assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome, result.output)
    }

    @Test
    fun `a gizmo packed into the project's own jar fails the gate`(@TempDir root: File) {
        val fixture = moba(root, listOf("clean"), jarContent = true)
        writeClass(File(root, "moba/packaged"), "game/RangeRing", EditorReleaseRules.GIZMO)

        val result = fixture.buildAndFail(task)

        assertTrue("UDEA-MG-012" in result.output && "game/RangeRing" in result.output, result.output)
        assertTrue("moba.jar" in result.output, "the failure must name the jar:\n${result.output}")
    }

    @Test
    fun `an editor class in a jar on the runtime classpath fails the gate`(@TempDir root: File) {
        val fixture = moba(root, listOf("clean", "libs/editor.jar"))
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/wildware/udea/editor/EditorSession", null, "java/lang/Object", null)
            visitEnd()
        }.toByteArray()
        File(root, "moba/libs").mkdirs()
        ZipOutputStream(File(root, "moba/libs/editor.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("dev/wildware/udea/editor/EditorSession.class"))
            zip.write(bytes)
            zip.closeEntry()
        }

        val result = fixture.buildAndFail(task)

        assertTrue("UDEA-MG-012" in result.output && "dev/wildware/udea/editor/EditorSession" in result.output, result.output)
    }

    @Test
    fun `check runs the gate, so a plain build cannot pass with a gizmo on the classpath`(@TempDir root: File) {
        val fixture = moba(root, listOf("clean", "editor"))
        writeClass(File(root, "moba/editor"), "game/Ring", EditorReleaseRules.GIZMO)

        val result = fixture.buildAndFail(":moba:check")

        assertTrue("UDEA-MG-012" in result.output, result.output)
    }
}
