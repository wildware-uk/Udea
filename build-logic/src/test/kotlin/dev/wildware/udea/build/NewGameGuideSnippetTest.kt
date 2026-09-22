package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every generated asset accessor the new-game guide shows is one a compiler has actually been
 * given (issue #269).
 *
 * The guide used to show `GameAssets.models.fox` to a reader whose model sat at the top of an
 * asset root, where the build generates `GameAssets.root.fox`, and the first thing that reader
 * saw was "Unresolved reference: models". Nothing compiled that example, so nothing noticed.
 *
 * So an example that names a generated accessor is not written in the guide any more: it is
 * **quoted** from `templates/new-game`, which `scripts/outside-game-proof.sh` builds from outside
 * this repository against the published engine. The comment line in front of the fence says which
 * file it is quoted from, and this test holds the quote to the file:
 *
 * ```
 * <!-- quoted from templates/new-game/game/src/main/kotlin/com/example/newgame/NewGameScene.kt -->
 * ```
 *
 * Two rules, and the second is what stops the first being optional:
 *
 * - a quoted block is a contiguous run of lines of the file it names, compared line by line with
 *   the indentation taken off, because a guide shows a function body at the margin;
 * - a block that names `GameAssets.` and quotes nothing fails. An accessor example that nothing
 *   compiles is the defect this exists to prevent, so it cannot be written by leaving the comment
 *   off.
 */
class NewGameGuideSnippetTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    /** The documents a person copying the template reads. */
    private val guides: List<File> = listOf(
        File(repoRoot, "docs/new-game.md"),
        File(repoRoot, "templates/new-game/README.md"),
        File(repoRoot, "docs/wiki/Tutorial-Make-a-Game.md"),
    )

    @Test
    fun `every quoted block in the new-game guides is in the template file it names`() {
        val blocks = guides.flatMap { blocksIn(it) }
        val quoted = blocks.filter { it.source != null }
        assertTrue(quoted.isNotEmpty(), "no guide quotes the template at all, so this test would pass on nothing")
        val offenders = quoted.mapNotNull { block -> mismatch(block) }
        assertEquals(emptyList(), offenders, "a guide shows code the template does not contain")
    }

    @Test
    fun `every block that names a generated accessor is quoted from compiled code`() {
        val unquoted = guides.flatMap { blocksIn(it) }
            .filter { it.source == null && ACCESSOR.containsMatchIn(it.text) }
            .map { "${it.where}: names a `GameAssets.` accessor and quotes no file" }
        assertEquals(
            emptyList(), unquoted,
            "an example naming a generated accessor has to be quoted from a template file that is " +
                "compiled, or nothing checks the accessor exists (issue #269). Put " +
                "`<!-- quoted from templates/new-game/<path> -->` on the line before the fence.",
        )
    }

    @Test
    fun `the check finds a quote that is not in its file, and one that quotes nothing`() {
        // The control. Both tests above pass when they find nothing wrong, which is also what a
        // parser that found no blocks would do - so each is made to find its defect here, on the
        // exact shape the issue reported: an accessor for a folder the model is not in.
        val scratch = File.createTempFile("new-game-snippet", ".kt").apply {
            writeText("fun draw() {\n    val rover = GameAssets.root.rover\n}\n")
            deleteOnExit()
        }
        val guide = """
            |<!-- quoted from ${scratch.absolutePath} -->
            |```kotlin
            |val rover = GameAssets.models.rover
            |```
            |
            |```kotlin
            |val rover = GameAssets.root.rover
            |```
        """.trimMargin()
        val blocks = blocksIn(guide, "fixture.md", absolute = true)
        assertEquals(2, blocks.size, "the fixture holds two blocks")
        assertTrue(mismatch(blocks[0]) != null, "a quote the file does not contain was accepted")
        assertEquals(null, blocks[1].source, "the unmarked block was read as quoting something")

        // And the positive half: the line the file really holds is accepted.
        val right = blocksIn(
            "<!-- quoted from ${scratch.absolutePath} -->\n```kotlin\nval rover = GameAssets.root.rover\n```\n",
            "fixture.md",
            absolute = true,
        )
        assertEquals(null, mismatch(right.single()), "a line the file does hold was refused")
    }

    @Test
    fun `an unquoted accessor is caught, and a game's own object that merely ends in those letters is not`() {
        // The control for the second test, in both directions. Without the first half that test
        // passes on a parser that finds nothing; without the second it fails every guide that
        // mentions `NewGameAssets.registry`, which is hand-written Kotlin and not a generated
        // accessor at all.
        val caught = blocksIn("```kotlin
val rover = GameAssets.models.rover
```
", "fixture.md", absolute = true)
        assertTrue(ACCESSOR.containsMatchIn(caught.single().text), "an unquoted generated accessor was let through")

        val spared = blocksIn(
            "```kotlin
val assets = NewGameAssets.registry
```
",
            "fixture.md",
            absolute = true,
        )
        assertEquals(
            false, ACCESSOR.containsMatchIn(spared.single().text),
            "`NewGameAssets.registry` was read as a generated accessor",
        )
    }

    /** Why [block] does not match its source, or `null` when it does. */
    private fun mismatch(block: Block): String? {
        val file = checkNotNull(block.source)
        if (!file.isFile) return "${block.where}: quotes $file, which does not exist"
        val want = block.text.lines().map { it.trim() }
        val have = file.readLines().map { it.trim() }
        val found = (0..have.size - want.size).any { start -> want.indices.all { have[start + it] == want[it] } }
        return if (found) null else "${block.where}: is not a run of lines in ${file.relativeToOrSelf(repoRoot)}"
    }

    private fun blocksIn(file: File): List<Block> =
        blocksIn(file.readText(), file.relativeTo(repoRoot).invariantSeparatorsPath, absolute = false)

    /**
     * Every fenced block in [text], with the file the comment line before it quotes.
     *
     * A quote path is relative to the repository root; [absolute] is for the control above, whose
     * file is a temporary one.
     */
    private fun blocksIn(text: String, name: String, absolute: Boolean): List<Block> {
        val lines = text.lines()
        val blocks = ArrayList<Block>()
        var at = 0
        while (at < lines.size) {
            if (!lines[at].trimStart().startsWith(FENCE)) {
                at++
                continue
            }
            val open = at
            val close = (open + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith(FENCE) }
                ?: error("$name:${open + 1}: a fence that never closes")
            val marker = lines.subList(0, open).lastOrNull { it.isNotBlank() }
                ?.let { QUOTE.matchEntire(it.trim()) }
                // Only the line directly before the fence counts, so a comment cannot quote a block
                // two paragraphs further down.
                ?.takeIf { lines[open - 1].isNotBlank() }
            val source = marker?.groupValues?.get(1)?.let { if (absolute) File(it) else File(repoRoot, it) }
            blocks += Block("$name:${open + 1}", lines.subList(open + 1, close).joinToString("\n"), source)
            at = close + 1
        }
        return blocks
    }

    private data class Block(val where: String, val text: String, val source: File?)

    private companion object {
        const val FENCE = "```"
        /**
         * A generated accessor: `GameAssets.` where it starts an identifier.
         *
         * The lookbehind is not decoration. A game's own `NewGameAssets.registry` ends in those
         * same eleven characters, and it is hand-written Kotlin - the rule below is about what the
         * asset compiler generates, which is what a guide can get wrong without anything noticing.
         */
        val ACCESSOR = Regex("""(?<![A-Za-z0-9_])GameAssets\.""")
        val QUOTE = Regex("""<!--\s*quoted from\s+(\S+)\s*-->""")
    }
}
