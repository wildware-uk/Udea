package dev.wildware.udea.build.determinism

import java.io.File
import javax.tools.ToolProvider
import kotlin.test.assertTrue

/**
 * Compiles Java sources to real class files, so the scanner's tests read **bytecode** rather
 * than a hand-built `MemberRef`.
 *
 * That distinction is the difference between a test of the rule table and a test of the gate. A
 * test that constructs `MemberRef("java.lang.System", "nanoTime", ...)` and asserts `DET001`
 * fires asserts nothing about whether `System.nanoTime()` in a source file *produces* that
 * reference - which is where a bytecode scanner actually goes wrong. These fixtures go through
 * `javac -g`, so they carry a `SourceFile` attribute and line numbers exactly as the Kotlin
 * compiler's output does, and the scan resolves real spans against the real files on disk.
 *
 * Java rather than Kotlin because `build-logic` has no Kotlin compiler it can invoke as a
 * library, and because the two produce the same shape for everything the rules match on. Where
 * they do NOT - `Random.Default`, which Kotlin emits and Java does not have sugar for - the
 * fixture writes the Java form of the same reference (`Random.Default.INSTANCE.nextFloat()`),
 * and `LegacyRegressionFixtureTest` documents each such case.
 *
 * LibGDX is not on this build's classpath, so the fixtures that need it compile **stubs** of the
 * gdx types beside them. A stub is honest here: the rules match on owner and member NAMES, so a
 * stub with the same fully-qualified name produces byte-for-byte the same reference the real jar
 * would.
 */
internal object FixtureCompiler {

    /** Stub gdx types, so a fixture can reference the names the rules match on. */
    val GDX_STUBS: Map<String, String> = mapOf(
        "com/badlogic/gdx/Graphics.java" to """
            package com.badlogic.gdx;
            public class Graphics {
                public float getDeltaTime() { return 0F; }
                public long getFrameId() { return 0L; }
            }
        """.trimIndent(),
        "com/badlogic/gdx/Input.java" to """
            package com.badlogic.gdx;
            public class Input {
                public boolean isKeyPressed(int key) { return false; }
            }
        """.trimIndent(),
        "com/badlogic/gdx/Gdx.java" to """
            package com.badlogic.gdx;
            public class Gdx {
                public static Graphics graphics;
                public static Input input;
            }
        """.trimIndent(),
        "com/badlogic/gdx/math/MathUtils.java" to """
            package com.badlogic.gdx.math;
            public class MathUtils {
                public static float random() { return 0F; }
                public static int random(int range) { return 0; }
                public static float sin(float radians) { return 0F; }
            }
        """.trimIndent(),
        "com/badlogic/gdx/physics/box2d/World.java" to """
            package com.badlogic.gdx.physics.box2d;
            public class World {
                public void step(float dt, int velocityIterations, int positionIterations) { }
            }
        """.trimIndent(),
        "com/badlogic/gdx/utils/ObjectMap.java" to """
            package com.badlogic.gdx.utils;
            public class ObjectMap<K, V> {
                public java.util.Iterator<K> keys() { return null; }
            }
        """.trimIndent(),
    )

    /**
     * Writes [sources] (path relative to a source root, to content) under `<root>/src`, compiles
     * them to `<root>/classes`, and returns the two directories.
     *
     * Fails the test rather than returning silently on a compile error: a fixture that did not
     * compile would make the scan see no classes, and a scan of nothing passes forever.
     */
    fun compile(root: File, sources: Map<String, String>): Compiled {
        val sourceDir = root.resolve("src").apply { mkdirs() }
        val classesDir = root.resolve("classes").apply { mkdirs() }
        val files = sources.map { (path, content) ->
            sourceDir.resolve(path).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "No system Java compiler. These tests need a JDK, not a JRE."
        }
        val fileManager = compiler.getStandardFileManager(null, null, null)
        val units = fileManager.getJavaFileObjectsFromFiles(files)
        val diagnostics = StringWriterCollector()
        val task = compiler.getTask(
            diagnostics.writer,
            fileManager,
            null,
            // `-g` is the whole point: without it there is no SourceFile attribute and no line
            // numbers, and every span the scan produced would degrade to `<ClassName>`.
            listOf("-g", "-d", classesDir.absolutePath, "-classpath", System.getProperty("java.class.path")),
            null,
            units,
        )
        val ok = task.call()
        assertTrue(ok, "fixture sources did not compile:\n${diagnostics.text()}")
        fileManager.close()
        return Compiled(sourceDir, classesDir)
    }

    /** A compiled fixture: where its sources are, and where its classes are. */
    data class Compiled(val sourceDir: File, val classesDir: File)

    /** Builds a [DeterminismScan.ScopeInput] over a compiled fixture. */
    fun scopeInput(
        compiled: Compiled,
        project: String = ":fixture",
        packagePrefixes: List<String> = emptyList(),
    ): DeterminismScan.ScopeInput = DeterminismScan.ScopeInput(
        scope = SimScope(
            project = project,
            sourceSet = "main",
            packagePrefixes = packagePrefixes,
            why = "test fixture",
        ),
        classRoots = listOf(compiled.classesDir),
        sourceRoots = listOf(compiled.sourceDir),
    )

    private class StringWriterCollector {
        val writer = java.io.StringWriter()
        fun text(): String = writer.toString()
    }
}
