package dev.wildware.udea.gradle.ci

/**
 * One Kotlin source file, read the way a fence has to read one: with comments removed first, and
 * string literals removed as well wherever the question is what the code *does*.
 *
 * ## Why the stripping is the load-bearing part
 *
 * Every source-reading gate in this repository has the same two ways to be wrong, and both of them
 * look like a pass. `NoWallClockInTransportTest` plants a violation *and* checks a KDoc mention is
 * not one, because `System.nanoTime` appears in the prose of half the files that forbid it.
 * `DeterminismScannerTest` holds Java fixtures containing `System.nanoTime()` as text, and
 * `NonLiteralIdTest` holds `System.currentTimeMillis()` inside an asset script it compiles. None
 * of the three is a measurement, and a scan that reads raw lines calls all three offenders.
 *
 * So the stripper runs before anything else looks at the text, and
 * `WallClockBudgetCensusTest`'s comment-and-string control runs the known negative rather than
 * assuming it: a clock read only in a comment, and one read only in a string, must both come back
 * empty.
 *
 * [withoutComments] keeps string literals, because the one question that is *about* a literal is
 * which task path a budget declares. [code] removes them, because every other question here is
 * about what runs.
 *
 * Nested block comments are not handled, because Kotlin allows them and this repository does not
 * use them; a nested one would end the outer comment early, which over-reports rather than
 * under-reports and so cannot turn a red fence green.
 */
internal class KotlinSource(val text: String) {

    /** [text] with `//` tails and block comments removed, string literals kept. */
    val withoutComments: String = strip(text, stripStrings = false)

    /** [text] with comments, raw strings, strings and char literals all removed. */
    val code: String = strip(text, stripStrings = true)

    /** Which wall-clock readings [code] makes, deduplicated; empty means it takes none. */
    val readings: List<String> = CLOCK_READINGS.filter { it in code }.sorted()

    /**
     * The names in [code] that hold an *elapsed* wall-clock time, transitively.
     *
     * An instant is not an elapsed time: `Random(System.nanoTime())` seeds a pilot and
     * `val deadline = System.nanoTime() + timeout` bounds a wait, and neither is a duration
     * anybody asserts a budget against. What makes a value a duration is subtracting one reading
     * from another, or asking a time mark how long ago it was - so those are what seed the set,
     * and anything assigned from a member of the set joins it.
     */
    private val elapsedNames: Set<String> = elapsedNames(code)

    /**
     * Assertion lines that mention an [elapsedNames] value: the shape of a wall-clock latency
     * budget, wherever it is written.
     */
    fun assertedElapsed(): List<String> = code.lines()
        .withIndex()
        .filter { (_, line) -> "assert" in line }
        .filter { (_, line) -> elapsedNames.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(line) } }
        .map { (index, line) -> "line ${index + 1}: ${line.trim()}" }

    private companion object {

        /**
         * Every way this repository's tests read a wall clock.
         *
         * Deliberately generous. A token that over-reports costs one census row with a reason on
         * it; a token that under-reports costs a budget nobody notices is inside `build`, which is
         * issue #175 and issue #182 both.
         */
        val CLOCK_READINGS = listOf(
            "System.nanoTime",
            "System.currentTimeMillis",
            "TimeSource.",
            "elapsedNow",
            "measureTime",
            "measureNanoTime",
            "measureTimeMillis",
            "Instant.now",
            "LocalDateTime.now",
            "LocalTime.now",
            "Clock.system",
        )

        /** What turns two readings, or a time mark, into a duration. */
        val ELAPSED_SOURCES = listOf(
            "- System.nanoTime()",
            "System.nanoTime() -",
            "- System.currentTimeMillis()",
            "System.currentTimeMillis() -",
            ".elapsedNow()",
            "measureTime",
            "measureNanoTime",
            "measureTimeMillis",
        )

        /**
         * `val x = `, `var x = `, `x = `, `x += ` and `x[i] = `, with the right-hand side.
         *
         * The lookaround is what keeps `==`, `<=`, `>=` and `!=` out: a comparison is not an
         * assignment, and treating `if (median == samples)` as one would mark `median` as a
         * duration on the strength of a test that never ran a clock.
         */
        val ASSIGNMENT = Regex(
            "(?:(?:val|var)\\s+)?([A-Za-z_][A-Za-z0-9_]*)(?:\\s*:[^=\\n]+?)?(?:\\[[^\\]]*])?" +
                "\\s*(?<![=!<>])(?:\\+=|=)(?!=)\\s*(.+)",
        )

        fun elapsedNames(code: String): Set<String> {
            val assignments = code.lines().mapNotNull { line ->
                ASSIGNMENT.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
            }
            val names = mutableSetOf<String>()
            var changed = true
            while (changed) {
                changed = false
                for ((name, rhs) in assignments) {
                    if (name in names) continue
                    val fromClock = ELAPSED_SOURCES.any { it in rhs }
                    val fromElapsed = names.any {
                        Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(rhs)
                    }
                    if (fromClock || fromElapsed) {
                        names += name
                        changed = true
                    }
                }
            }
            return names
        }

        fun strip(source: String, stripStrings: Boolean): String {
            val out = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("//", i) -> {
                        while (i < source.length && source[i] != '\n') i++
                    }

                    source.startsWith("/*", i) -> {
                        val end = source.indexOf("*/", i + 2)
                        val stop = if (end < 0) source.length else end + 2
                        // Newlines are kept so reported line numbers are the file's own.
                        for (n in i until stop) if (source[n] == '\n') out.append('\n')
                        i = stop
                    }

                    !stripStrings -> out.append(source[i++])

                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3)
                        val stop = if (end < 0) source.length else end + 3
                        for (n in i until stop) if (source[n] == '\n') out.append('\n')
                        i = stop
                    }

                    source[i] == '"' || source[i] == '\'' -> {
                        val quote = source[i]
                        i++
                        while (i < source.length && source[i] != quote) {
                            if (source[i] == '\\') i++
                            i++
                        }
                        i++
                    }

                    else -> out.append(source[i++])
                }
            }
            return out.toString()
        }
    }
}
