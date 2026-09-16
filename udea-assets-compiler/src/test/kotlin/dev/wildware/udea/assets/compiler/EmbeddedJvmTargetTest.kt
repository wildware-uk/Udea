package dev.wildware.udea.assets.compiler

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [EMBEDDED_JVM_TARGET] must name the bytecode level this module was actually compiled to.
 *
 * The defect this closes is a real one, found on the JDK 17 to 21 move (issue #186): two hardcoded
 * `"17"` strings fed the compilers this module embeds, the toolchain moved to 21, and the
 * *symptom* was `AccessorCompilationTest` failing with ten identical "cannot inline bytecode built
 * with JVM target 21 into bytecode that is being built with JVM target 17" messages — a long way
 * from the two literals that caused it.
 *
 * Read out of the artefact rather than asserted against another written-down number: a test
 * comparing [EMBEDDED_JVM_TARGET] to a second constant would only prove two strings match.
 */
class EmbeddedJvmTargetTest {

    @Test
    fun `the embedded jvm target is the bytecode level this module was compiled to`() {
        val major = classFileMajorVersion(AssetCompilerRules::class.java)
        assertEquals(
            EMBEDDED_JVM_TARGET,
            (major - CLASS_FILE_MAJOR_FOR_JAVA_1).toString(),
            "this module's own classes are class-file major $major, i.e. Java " +
                "${major - CLASS_FILE_MAJOR_FOR_JAVA_1}, but the compilers it embeds are told " +
                "-jvm-target $EMBEDDED_JVM_TARGET. A lower target cannot inline this module's " +
                "bytecode and every embedded compile fails. Move EMBEDDED_JVM_TARGET with " +
                "UdeaVersions.JVM_TOOLCHAIN.",
        )
    }

    @Test
    fun `the major-version reader agrees with a class-file version it is told`() {
        // The control. `classFileMajorVersion` returning a constant, or reading the wrong two
        // bytes, would make the test above pass by construction. `Object` comes from the JDK this
        // test runs on, whose class-file version the JVM itself reports, so the two are
        // independent readings of the same fact.
        val fromJvm = System.getProperty("java.class.version").substringBefore('.').toInt()
        assertEquals(
            fromJvm,
            classFileMajorVersion(Any::class.java),
            "java.lang.Object should be at the running JDK's own class-file version",
        )
        assertTrue(
            fromJvm >= CLASS_FILE_MAJOR_FOR_JAVA_1 + 21,
            "these tests need a JDK 21 or newer, was class-file major $fromJvm",
        )
    }

    private companion object {

        /** Class-file major 45 is Java 1, so `major - 44` is the Java release. */
        const val CLASS_FILE_MAJOR_FOR_JAVA_1 = 44

        /** Bytes 6 and 7 of a `.class` file, after the `0xCAFEBABE` magic and the minor version. */
        fun classFileMajorVersion(type: Class<*>): Int {
            val resource = "/${type.name.replace('.', '/')}.class"
            val stream = requireNotNull(type.getResourceAsStream(resource)) {
                "no class file for ${type.name} at $resource"
            }
            return stream.use {
                DataInputStream(it).run {
                    require(readInt() == CLASS_FILE_MAGIC) { "$resource is not a class file" }
                    readUnsignedShort() // minor
                    readUnsignedShort() // major
                }
            }
        }

        const val CLASS_FILE_MAGIC = -0x35014542 // 0xCAFEBABE as a signed Int
    }
}
