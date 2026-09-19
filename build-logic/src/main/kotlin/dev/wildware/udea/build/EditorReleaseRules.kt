package dev.wildware.udea.build

import dev.wildware.udea.build.determinism.ClassHeader

/**
 * `UDEA-MG-012`: no release classpath carries a `udea-editor` class or a `Gizmo` (issue #233).
 *
 * `UDEA-MG-010` is the dependency half: `:udea-editor` on a scanned classpath fails the build. This
 * is the class half, because a gizmo can reach a release classpath with no dependency edge for a
 * graph rule to see - an `editor` source set's output added to `runtimeClasspath`, a gizmo compiled
 * into a directory some `files(...)` dependency names, a class packed into the jar by hand. A gizmo
 * implements `udea-editor`'s `Gizmo`, which is what a class is rather than what it mentions, so the
 * rule reads **supertypes**: a class whose name or string constants merely spell an editor type is
 * not a violation.
 *
 * The decisions are here, where unit tests call them; [UdeaVerifyEditorAbsentTask] is the I/O.
 */
public object EditorReleaseRules {

    /** The stable id, documented in `docs/module-graph.md`. */
    public val RULE_ID: RuleId = RuleId("UDEA-MG-012")

    /** Every `udea-editor` class lives under this package root. */
    public const val EDITOR_PACKAGE: String = "dev/wildware/udea/editor/"

    /** The gizmo interface, in internal form. */
    public const val GIZMO: String = "dev/wildware/udea/editor/gizmo/Gizmo"

    /** The editor module itself, whose own classes are the editor and are not scanned against it. */
    public const val EDITOR_PROJECT: String = ":udea-editor"

    /**
     * The release runtime classpaths: a JVM project's, and a multiplatform project's JVM target's.
     *
     * JVM only. `udea-editor` publishes no Android or Wasm variant, so no other target can resolve it,
     * and a class implementing `Gizmo` cannot be compiled for a target that cannot see `Gizmo`.
     */
    public val RELEASE_CLASSPATHS: Set<String> = setOf("runtimeClasspath", "jvmRuntimeClasspath")

    /** The tasks that package a project's own main classes, for the same two kinds of project. */
    public val RELEASE_JARS: Set<String> = setOf("jar", "jvmJar")

    /** One class found on a release classpath, and where: the jar or directory it came out of. */
    public data class ScannedClass(val origin: String, val header: ClassHeader)

    /** One class that must not be there, where it was, and why it counts. */
    public data class Violation(val origin: String, val className: String, val why: String)

    /**
     * Every class in [classes] that is `udea-editor`'s, or is a `Gizmo`, or extends or implements
     * anything of `udea-editor`'s - directly, or through supertypes that are themselves in [classes].
     * Sorted by class name, then origin.
     */
    public fun violations(classes: List<ScannedClass>): List<Violation> {
        val byName = classes.associateBy { it.header.name }
        return classes.mapNotNull { scanned ->
            val name = scanned.header.name
            val why = when {
                name.startsWith(EDITOR_PACKAGE) -> "is a udea-editor class"
                else -> editorAncestor(scanned.header, byName)?.let { ancestor ->
                    if (ancestor == GIZMO) "is a Gizmo" else "extends or implements udea-editor's $ancestor"
                }
            } ?: return@mapNotNull null
            Violation(scanned.origin, name, why)
        }.sortedWith(compareBy({ it.className }, { it.origin }))
    }

    /**
     * The first `udea-editor` type among [header]'s supertypes, walked breadth-first through the
     * scanned classes, preferring [GIZMO] when it is there; `null` when there is none.
     */
    private fun editorAncestor(header: ClassHeader, byName: Map<String, ScannedClass>): String? {
        val seen = HashSet<String>()
        val pending = ArrayDeque(supertypes(header))
        var found: String? = null
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!seen.add(type)) continue
            if (type == GIZMO) return GIZMO
            if (type.startsWith(EDITOR_PACKAGE)) {
                if (found == null) found = type
                continue
            }
            byName[type]?.let { pending.addAll(supertypes(it.header)) }
        }
        return found
    }

    private fun supertypes(header: ClassHeader): List<String> = listOfNotNull(header.superName) + header.interfaces

    /**
     * The message to fail with when nothing was scanned, or `null` when something was. A gate with
     * no input passes forever, so "no editor class" and "nobody looked" must not read the same.
     */
    public fun brokenCheck(projectPath: String, scannedClasses: Int): String? {
        if (scannedClasses > 0) return null
        return "${RULE_ID.value} $projectPath: udeaVerifyEditorAbsent found no class on the release " +
            "classpath to scan. A gate with no input passes forever; fix the classpath selection " +
            "rather than the project."
    }

    /** The build-failure message for [violations], or `null` when there are none. */
    public fun report(projectPath: String, violations: List<Violation>): String? {
        if (violations.isEmpty()) return null
        return buildString {
            append(RULE_ID.value)
            append(' ')
            append(projectPath)
            append(": ")
            append(violations.size)
            appendLine(if (violations.size == 1) " editor class on the release classpath." else " editor classes on the release classpath.")
            for (violation in violations) {
                append("    ")
                append(violation.className)
                append(' ')
                append(violation.why)
                append(", in ")
                appendLine(violation.origin)
            }
            append(
                "    udea-editor and every Gizmo are debug-only: they belong in a game's editor source " +
                    "set, which nothing shipped runs on. Remove whatever put that source set's output, " +
                    "or the editor, on this classpath.",
            )
        }
    }
}
