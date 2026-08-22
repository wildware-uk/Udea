package dev.wildware.udea.core

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rule that keeps `GameContext` meaningful.
 *
 * `GameContext` is only the sole injectable for as long as nobody adds a shortcut beside it.
 * The old tree's `lateinit var gameScreen` was not malicious — it was convenient, and 36
 * files came to depend on it. So the absence of a global is checked, twice and by two
 * different means:
 *
 * - **source**, because `lateinit var` at file level is the exact shape that was deleted;
 * - **bytecode**, because a mutable global has one signature no matter how it is spelled —
 *   a non-final static field — and that catches an `object` with a `var` in it, which a
 *   source grep for `lateinit` would sail straight past.
 */
class NoMutableGlobalsTest {

    @Test
    fun `udea-core declares no lateinit anywhere`() {
        val offenders = scanMainSources { path, line, text ->
            if (LATEINIT.containsMatchIn(text)) "$path:$line  $text" else null
        }

        assertEquals(
            emptyList(),
            offenders,
            "lateinit is how the old globals were spelled; a service belongs on GameContext",
        )
    }

    @Test
    fun `udea-core declares no top-level var`() {
        val offenders = scanMainSources { path, line, text ->
            if (TOP_LEVEL_VAR.containsMatchIn(text)) "$path:$line  $text" else null
        }

        assertEquals(
            emptyList(),
            offenders,
            "a file-level var is process-wide mutable state, which makes two worlds in one JVM impossible",
        )
    }

    @Test
    fun `no compiled class in udea-core holds mutable static state`() {
        val classFiles = compiledMainClasses()
        assertTrue(
            classFiles.size >= 10,
            "expected to find udea-core's compiled classes, found ${classFiles.size}",
        )

        val offenders = classFiles.flatMap { className ->
            Class.forName(className, false, javaClass.classLoader).declaredFields
                .filterNot { it.isSynthetic }
                .filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
                .map { "$className.${it.name}: ${it.type.simpleName}" }
        }.sorted()

        assertEquals(
            emptyList(),
            offenders,
            "a non-final static field is a mutable global however it is spelled in Kotlin",
        )
    }

    private fun scanMainSources(
        inspect: (path: String, line: Int, text: String) -> String?,
    ): List<String> {
        val offenders = ArrayList<String>()
        for (file in ModuleFiles.mainSources) {
            val path = ModuleFiles.relativePath(file)
            // Comments are stripped so this file's own prose about `lateinit var` does not
            // count as a declaration of one.
            val code = KotlinSource.stripCommentsAndStrings(file.readText())
            code.lineSequence().forEachIndexed { index, text ->
                inspect(path, index + 1, text.trimEnd())?.let(offenders::add)
            }
        }
        return offenders
    }

    /**
     * Fully qualified names of every class compiled from `src/main/kotlin`.
     *
     * Depending on how Gradle wired the test classpath this is either an exploded classes
     * directory or the module's jar, so both are handled.
     */
    private fun compiledMainClasses(): List<String> {
        val source = File(GameContext::class.java.protectionDomain.codeSource.location.toURI())

        val entries = when {
            source.isDirectory -> source.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(source).invariantSeparatorsPath }
                .toList()

            source.isFile -> java.util.jar.JarFile(source).use { jar ->
                jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList()
            }

            else -> error("expected a classes directory or jar for udea-core, got $source")
        }

        return entries
            .map { it.removeSuffix(".class").replace('/', '.') }
            .filter { it.startsWith("dev.wildware.udea.") }
            .sorted()
    }

    private companion object {
        /** `lateinit` in any position: there is no legitimate use of it in this module. */
        val LATEINIT = Regex("""\blateinit\b""")

        /** A `var` declared at column zero, optionally preceded by a visibility modifier. */
        val TOP_LEVEL_VAR = Regex("""^(public |internal |private )?(lateinit )?var\s""")
    }
}
