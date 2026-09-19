package dev.wildware.udea.build

import dev.wildware.udea.build.determinism.ClassScanner
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `UDEA-MG-012`'s decision (issue #233): which classes on a release classpath are the editor's.
 *
 * The classes are real bytecode, written by ASM and read back through [ClassScanner.header] - the
 * same path the task takes - so what is tested is the rule over what a compiler would produce, not
 * over hand-written names.
 */
class EditorReleaseRulesTest {

    /** A class called [name] extending [superName] and implementing [interfaces], as bytecode. */
    private fun classBytes(
        name: String,
        superName: String = "java/lang/Object",
        vararg interfaces: String,
        constant: String? = null,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, interfaces)
        if (constant != null) {
            writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "NAME", "Ljava/lang/String;", null, constant)
                .visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun scanned(origin: String, bytes: ByteArray) = EditorReleaseRules.ScannedClass(origin, ClassScanner.header(bytes))

    @Test
    fun `a class implementing Gizmo is a violation, named with where it was found`() {
        val classes = listOf(
            scanned("game.jar", classBytes("dev/wildware/moba/Clean")),
            scanned("editor-classes", classBytes("dev/wildware/moba/PositionPositionGizmo", interfaces = arrayOf(EditorReleaseRules.GIZMO))),
        )

        val violation = EditorReleaseRules.violations(classes).single()

        assertEquals("editor-classes", violation.origin)
        assertEquals("dev/wildware/moba/PositionPositionGizmo", violation.className)
        assertTrue("Gizmo" in violation.why, violation.why)
    }

    @Test
    fun `a gizmo through a base class is found, and so is the base`() {
        val classes = listOf(
            scanned("a", classBytes("game/BaseGizmo", interfaces = arrayOf(EditorReleaseRules.GIZMO))),
            scanned("a", classBytes("game/RangeRing", superName = "game/BaseGizmo")),
        )

        assertEquals(
            listOf("game/BaseGizmo", "game/RangeRing"),
            EditorReleaseRules.violations(classes).map { it.className },
        )
    }

    @Test
    fun `an editor class, and a class extending one, are violations`() {
        val classes = listOf(
            scanned("udea-editor.jar", classBytes("dev/wildware/udea/editor/EditorSession")),
            scanned("game.jar", classBytes("game/Panel", superName = "dev/wildware/udea/editor/AssetPanel")),
        )

        val violations = EditorReleaseRules.violations(classes)

        assertEquals(listOf("dev/wildware/udea/editor/EditorSession", "game/Panel"), violations.map { it.className })
        assertTrue("dev/wildware/udea/editor/AssetPanel" in violations[1].why, violations[1].why)
    }

    @Test
    fun `a class that only mentions the editor in a string is not one - the control`() {
        // A release class may well name the editor in a message or a constant. That is text, and
        // the rule reads supertypes, so it must pass.
        val classes = listOf(
            scanned("game.jar", classBytes("game/Notes", constant = "dev/wildware/udea/editor/gizmo/Gizmo")),
            scanned("game.jar", classBytes("dev/wildware/udea/editorial/Page")),
        )

        assertEquals(emptyList(), EditorReleaseRules.violations(classes))
    }

    @Test
    fun `a class newer than this build's ASM is still read, so a gizmo cannot hide in one`() {
        // LWJGL 3.4.3 ships Java 27 classes under META-INF/versions/27, on every GL module's
        // release classpath. Major version 71 is Java 27.
        val writer = ClassWriter(0)
        writer.visit(JAVA_27, Opcodes.ACC_PUBLIC, "game/FutureRing", null, "java/lang/Object", arrayOf(EditorReleaseRules.GIZMO))
        writer.visitEnd()

        val header = ClassScanner.header(writer.toByteArray())

        assertEquals(listOf(EditorReleaseRules.GIZMO), header.interfaces)
        assertEquals("game/FutureRing", EditorReleaseRules.violations(listOf(EditorReleaseRules.ScannedClass("a", header))).single().className)
    }

    @Test
    fun `the report names the rule, the project, each class and where it was`() {
        val violations = EditorReleaseRules.violations(
            listOf(scanned("/x/editor/classes", classBytes("game/Ring", interfaces = arrayOf(EditorReleaseRules.GIZMO)))),
        )

        val report = assertNotNull(EditorReleaseRules.report(":moba:desktop", violations))

        assertTrue("UDEA-MG-012" in report, report)
        assertTrue(":moba:desktop" in report, report)
        assertTrue("game/Ring" in report && "/x/editor/classes" in report, report)
        assertNull(EditorReleaseRules.report(":moba:desktop", emptyList()))
    }

    @Test
    fun `a scan of no classes at all is a broken check, not a clean one`() {
        assertNotNull(EditorReleaseRules.brokenCheck(":moba:desktop", scannedClasses = 0))
        assertNull(EditorReleaseRules.brokenCheck(":moba:desktop", scannedClasses = 1))
    }

    private companion object {
        /** The class-file major version of Java 27. */
        const val JAVA_27 = 71
    }
}
