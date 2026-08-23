package dev.wildware.udea.agent

import java.io.File

/**
 * Locates this module's own sources, so the architecture rules can read them.
 *
 * Some rules here are about *source*, not about types: "no reflection on the query path", "no
 * HTTP server in this module". Neither can be checked by reflection, because the thing being
 * forbidden is something the compiler is perfectly happy with. So they are checked by reading
 * the tree, and this is how the tree is found.
 *
 * A near-copy of `udea-core`'s `ModuleFiles`, which lives in that module's `test` source set
 * rather than its published fixtures. Sharing it would mean editing a module this issue does not
 * own; the duplication is thirty lines and it is honest about which module it points at.
 */
internal object ModuleSources {

    /** The `udea-agent` project directory. */
    val moduleDir: File = locateModuleDir()

    /** The repository root. */
    val repoRoot: File = moduleDir.parentFile

    /** Every `.kt` file in `src/main/kotlin`. */
    val mainSources: List<File> = kotlinFilesIn(moduleDir.resolve("src/main/kotlin"))

    /** Path relative to the repo root, with forward slashes, for readable failure messages. */
    fun relativePath(file: File): String = file.relativeTo(repoRoot).invariantSeparatorsPath

    private fun kotlinFilesIn(root: File): List<File> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        }

    private fun locateModuleDir(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (candidate.name == MODULE && candidate.resolve("build.gradle.kts").isFile) return candidate
            val nested = candidate.resolve(MODULE)
            if (nested.resolve("build.gradle.kts").isFile) return nested
            candidate = candidate.parentFile
        }
        error("Could not locate the $MODULE module directory from ${System.getProperty("user.dir")}")
    }

    private const val MODULE: String = "udea-agent"
}

/**
 * Just enough Kotlin lexing for the architecture rules here: comments and string literals are
 * blanked, so a rule does not trip on the KDoc that explains it.
 *
 * Offsets and newlines are preserved, so a line number in a failure message still points at the
 * line a reader will open.
 */
internal object KotlinSourceText {

    fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0

        fun blankTo(end: Int) {
            while (index < end && index < source.length) {
                out.append(if (source[index] == '\n') '\n' else ' ')
                index++
            }
        }

        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when {
                char == '/' && next == '/' -> {
                    val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    blankTo(end)
                }

                char == '/' && next == '*' -> {
                    var depth = 1
                    var cursor = index + 2
                    while (cursor < source.length && depth > 0) {
                        if (source.startsWith("/*", cursor)) {
                            depth++
                            cursor += 2
                        } else if (source.startsWith("*/", cursor)) {
                            depth--
                            cursor += 2
                        } else {
                            cursor++
                        }
                    }
                    blankTo(cursor)
                }

                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    blankTo(if (end < 0) source.length else end + 3)
                }

                char == '"' -> {
                    var cursor = index + 1
                    while (cursor < source.length && source[cursor] != '"' && source[cursor] != '\n') {
                        cursor += if (source[cursor] == '\\') 2 else 1
                    }
                    blankTo(minOf(cursor + 1, source.length))
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    /** 1-based line number of [offset]. */
    fun lineOf(source: String, offset: Int): Int =
        source.substring(0, offset.coerceAtMost(source.length)).count { it == '\n' } + 1
}
