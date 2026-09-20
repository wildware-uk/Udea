package dev.wildware.udea.gradle

/**
 * Reads the one line of wiring that puts `build-logic`'s own tests inside `./gradlew build`.
 *
 * `build-logic` is an **included build**, so the outer build compiles its classes to configure
 * itself and reaches none of its tasks. Nothing but an explicit dependency from the outer build
 * can run its tests, and until issue #265's follow-up nothing did.
 *
 * The scanner lives beside the test rather than inside it because it is the part that decides
 * what the assertion sees, and a slicer that reads raw lines is how a source-reading fence gets
 * defeated: a `//` comment that merely mentions the wiring would satisfy a naive `contains`.
 * `BuildLogicGateTest` runs that case as a control.
 */
internal object BuildLogicGate {

    /** The name of the included build, as `settings.gradle.kts` includes it. */
    const val INCLUDED_BUILD: String = "build-logic"

    /** The aggregate in `build-logic/build.gradle.kts` that the outer `check` hangs off. */
    const val AGGREGATE_TASK: String = "udeaBuildLogicCheck"

    /** The outer task the aggregate is attached to. `build` depends on it; every developer runs it. */
    const val OUTER_TASK: String = "check"

    /** The expression the outer build needs, spelt exactly as a build script spells it. */
    val WIRING: String =
        """dependsOn(gradle.includedBuild("$INCLUDED_BUILD").task(":$AGGREGATE_TASK"))"""

    private val OUTER_TASK_SELECTOR = """tasks.named("$OUTER_TASK")"""

    /**
     * True when [script] attaches [WIRING] to [OUTER_TASK] as code.
     *
     * The body of the `tasks.named("check") { ... }` block is taken by matching braces, so a
     * `dependsOn` on some *other* task elsewhere in the file does not answer for this one.
     */
    fun wiresOuterTask(script: String): Boolean {
        val block = outerTaskBlock(script) ?: return false
        return block.withoutWhitespace().contains(WIRING.withoutWhitespace())
    }

    /**
     * The body of the outer `check` configuration block, comments removed, or null if the script
     * does not configure it at all.
     */
    fun outerTaskBlock(script: String): String? {
        val code = stripComments(script)
        val selector = code.indexOf(OUTER_TASK_SELECTOR)
        if (selector < 0) return null
        val open = code.indexOf('{', selector + OUTER_TASK_SELECTOR.length)
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /**
     * [source] with line comments, block comments and KDoc removed, and string literals kept.
     *
     * String-aware on purpose: these build scripts hold `"https://..."` URLs, and a stripper that
     * cut at the first `//` would delete the rest of a line that merely quotes one.
     */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString = false
        var inLineComment = false
        var blockDepth = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLineComment -> {
                    if (c == '\n') {
                        inLineComment = false
                        out.append(c)
                    }
                    i++
                }

                blockDepth > 0 -> {
                    when {
                        c == '/' && next == '*' -> { blockDepth++; i += 2 }
                        c == '*' && next == '/' -> { blockDepth--; i += 2 }
                        else -> {
                            if (c == '\n') out.append(c)
                            i++
                        }
                    }
                }

                inString -> {
                    out.append(c)
                    if (c == '\\') {
                        next?.let { out.append(it) }
                        i += 2
                    } else {
                        if (c == '"') inString = false
                        i++
                    }
                }

                c == '/' && next == '/' -> { inLineComment = true; i += 2 }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                c == '"' -> { inString = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun String.withoutWhitespace(): String = filterNot(Char::isWhitespace)
}
