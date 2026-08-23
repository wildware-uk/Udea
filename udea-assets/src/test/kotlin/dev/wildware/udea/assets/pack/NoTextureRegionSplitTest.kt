package dev.wildware.udea.assets.pack

import java.io.DataInputStream
import java.io.File
import java.net.URLClassLoader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No compiled class in this module names `TextureRegion.split`.
 *
 * ## What the rule is for
 *
 * `common/.../animationSets.kt:25-46` split a sheet into frames at runtime, on the texture it
 * had just uploaded. One texture per sheet, therefore one bind per unit type per draw. Issue
 * #89 moves the split to pack time ([AtlasIndex]), and the way that stops being true is not a
 * deliberate decision - it is one call slipping back in.
 *
 * ## What this test does and does not cover
 *
 * It scans **this module's own** compiled classes. Issue #89 asks for `udea-assets` *and*
 * `udea-render`; the `udea-render` half is not here, and cannot be: a test in `udea-assets`
 * that read `udea-render`'s class output would need `udea-render` built first, and
 * `udea-render` depends on `udea-assets`, so the task edge would be a cycle. That half belongs
 * in `udea-render`'s own test source set and is **not implemented by this wave**.
 *
 * For this module the scan is also, honestly, belt and braces: `UDEA-MG-006` allows
 * `udea-assets` to resolve `udea-annotations`, `udea-diagnostics` and the stdlib, so
 * `com.badlogic.gdx.graphics.g2d.TextureRegion` is not on its compile classpath and a call
 * could not compile. The scan is here anyway because it is the check that keeps working if the
 * module rule is ever relaxed, and because it is what makes the claim mechanical rather than an
 * argument.
 */
class NoTextureRegionSplitTest {

    /** Class-file constant-pool references that would mean the runtime split came back. */
    private val forbidden = listOf(
        "com/badlogic/gdx/graphics/g2d/TextureRegion",
        "com.badlogic.gdx.graphics.g2d.TextureRegion",
    )

    /**
     * This module's compiled output, found from its own classes rather than from a path.
     *
     * `AtlasIndex::class` is loaded from `build/classes/kotlin/main`, so its code source is the
     * directory to walk. A hard-coded path would silently scan nothing when the build layout
     * moved, which is exactly the "green because it ran nothing" failure this test must not
     * have - [`the scan actually reads class files`] is the guard.
     */
    private fun classesDirectory(): File {
        val source = AtlasIndex::class.java.protectionDomain.codeSource
            ?: error("no code source for AtlasIndex; cannot locate this module's compiled output")
        return File(source.location.toURI())
    }

    private fun classFiles(): List<File> = classesDirectory().walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .toList()

    /**
     * The guard that makes the assertion below mean something.
     *
     * A scan over an empty file list passes. This asserts the scan found this module's classes
     * and that the reader can actually read one - a reviewer has already caught a gate in this
     * repository that was green because everything under it skipped.
     */
    @Test
    fun `the scan actually reads class files`() {
        val files = classFiles()

        assertTrue(files.size > MINIMUM_CLASSES, "only ${files.size} class files found in ${classesDirectory()}")
        assertTrue(
            files.any { it.name.startsWith("AtlasIndex") },
            "AtlasIndex is not among the scanned classes; the scan is looking in the wrong place",
        )
        // A constant this module certainly has, proving the pool reader returns real strings.
        val cursor = files.single { it.name == "BundleFormat.class" }
        assertTrue(
            stringsIn(cursor).any { it == BundleFormat.GRAPH_SECTION },
            "the constant-pool reader did not find a string this class certainly holds",
        )
    }

    @Test
    fun `no class in udea-assets references TextureRegion`() {
        val offenders = classFiles().filter { file ->
            stringsIn(file).any { constant -> forbidden.any { it in constant } }
        }

        assertEquals(emptyList(), offenders.map { it.name }, "runtime TextureRegion.split is back")
    }

    /**
     * Every UTF-8 constant in a class file.
     *
     * Written out rather than pulled in with ASM: `udea-assets` may resolve three things and ASM
     * is not one of them ([UDEA-MG-006][dev.wildware.udea.assets.AssetRegistry]), and a test
     * dependency is still a dependency someone has to justify. A class file's constant pool is
     * a documented format and this is forty lines of it.
     */
    private fun stringsIn(file: File): List<String> = DataInputStream(file.inputStream().buffered()).use { input ->
        require(input.readInt() == MAGIC) { "$file is not a class file" }
        input.readUnsignedShort() // minor version
        input.readUnsignedShort() // major version
        val count = input.readUnsignedShort()
        val strings = mutableListOf<String>()
        var at = 1
        while (at < count) {
            when (val tag = input.readUnsignedByte()) {
                CONSTANT_UTF8 -> strings += input.readUTF()
                CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELDREF, CONSTANT_METHODREF,
                CONSTANT_INTERFACE_METHODREF, CONSTANT_NAME_AND_TYPE, CONSTANT_INVOKE_DYNAMIC,
                CONSTANT_DYNAMIC,
                -> input.skipNBytes(4)

                // A Long or Double takes two pool slots. Getting this wrong desynchronises the
                // whole rest of the pool, which is why it is called out rather than folded in.
                CONSTANT_LONG, CONSTANT_DOUBLE -> {
                    input.skipNBytes(8)
                    at++
                }

                CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE,
                CONSTANT_PACKAGE,
                -> input.skipNBytes(2)

                CONSTANT_METHOD_HANDLE -> input.skipNBytes(3)
                else -> error("unknown constant pool tag $tag at entry $at of $file")
            }
            at++
        }
        strings
    }

    private companion object {
        const val MAGIC = -0x35014542 // 0xCAFEBABE

        /** This module has far more than this; the number only has to rule out "nothing". */
        const val MINIMUM_CLASSES = 20

        const val CONSTANT_UTF8 = 1
        const val CONSTANT_INTEGER = 3
        const val CONSTANT_FLOAT = 4
        const val CONSTANT_LONG = 5
        const val CONSTANT_DOUBLE = 6
        const val CONSTANT_CLASS = 7
        const val CONSTANT_STRING = 8
        const val CONSTANT_FIELDREF = 9
        const val CONSTANT_METHODREF = 10
        const val CONSTANT_INTERFACE_METHODREF = 11
        const val CONSTANT_NAME_AND_TYPE = 12
        const val CONSTANT_METHOD_HANDLE = 15
        const val CONSTANT_METHOD_TYPE = 16
        const val CONSTANT_DYNAMIC = 17
        const val CONSTANT_INVOKE_DYNAMIC = 18
        const val CONSTANT_MODULE = 19
        const val CONSTANT_PACKAGE = 20
    }
}
