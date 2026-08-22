package dev.wildware.udea.compiler

import java.io.File

/**
 * Spec 7's guard: **the K2 plugin is never allowed to be required for the project to compile.**
 *
 * The mitigation that makes decision D8 survivable is that CI proves the build is green with
 * the plugin disabled, so a Kotlin release that breaks the plugin degrades the project to
 * checkers-off instead of stopping it. That proof is worthless the moment some production
 * source references a `dev.wildware.udea.compiler` type, because from then on the
 * plugin-disabled leg fails for a reason that has nothing to do with the plugin working.
 *
 * This is the rule, as a pure function over a file scan, so that `udeaVerifyPluginOptional` is
 * a check with tests rather than a `doLast` block nothing can execute.
 */
object PluginOptionalRule {

    /**
     * Referencing anything under this package from production code makes the plugin
     * load-bearing.
     *
     * The trailing dot matters: `udea-gradle` legitimately carries the plugin *id*
     * (`dev.wildware.udea`) and the artifact coordinates as strings, and neither is a type
     * reference.
     */
    const val FORBIDDEN_PREFIX: String = "dev.wildware.udea.compiler."

    /** The one module allowed to name its own types: the plugin itself. */
    const val OWNING_MODULE: String = "udea-compiler-plugin"

    /** One offending line. */
    data class Reference(val path: String, val line: Int, val text: String) {
        override fun toString(): String = "$path:$line: ${text.trim()}"
    }

    /**
     * Every production source file that must not reference the plugin.
     *
     * "Production" is `src/main` of every `udea-*` module and of `moba`. Test sources are
     * excluded deliberately: this module's own suite compiles against the plugin, and so may a
     * future module's, without making anything load-bearing - a test is not on the path a
     * plugin-disabled build has to walk.
     */
    fun productionSources(repoRoot: File): List<File> =
        repoRoot.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name.startsWith("udea-") || it.name == "moba") }
            .filter { it.name != OWNING_MODULE }
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .sortedBy { it.invariantSeparatorsPath }

    /** Every [FORBIDDEN_PREFIX] reference in [files], relative to [repoRoot] for the message. */
    fun scan(repoRoot: File, files: List<File>): List<Reference> = files.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, text ->
            if (FORBIDDEN_PREFIX in text) {
                Reference(
                    path = file.relativeTo(repoRoot).invariantSeparatorsPath,
                    line = index + 1,
                    text = text,
                )
            } else {
                null
            }
        }
    }

    /**
     * The message to fail with, or `null` when the tree is clean.
     *
     * An **empty scan is a failure**, not a pass. A check that walks nothing and compares it
     * against an empty expectation stays green forever while the thing it guards rots - the
     * same trap `UdeaLeafCheck` names in `build-logic`.
     */
    fun violation(scannedFiles: Int, references: List<Reference>): String? {
        if (scannedFiles == 0) {
            return "udeaVerifyPluginOptional scanned no production sources at all - the check " +
                "is broken, not the tree. A scan that finds nothing to look at passes forever."
        }
        if (references.isEmpty()) return null
        return "the K2 compiler plugin must never be required for the project to compile " +
            "(spec 7), but ${references.size} production line(s) reference " +
            "$FORBIDDEN_PREFIX:\n" + references.joinToString(separator = "\n") { "  $it" } +
            "\nMove what is needed into a module that does not live inside the compiler, or " +
            "flip udea.compilerPlugin.enabled=false and watch this build stop compiling."
    }
}
