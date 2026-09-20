package dev.wildware.udea.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/wiki/` points only at things that exist.
 *
 * Each rule is shown two ways: a page that breaks it fails naming the page and line, and a page
 * that looks similar but does not break it passes. The second half matters as much as the first,
 * because a wiki gate that fires on prose is a gate somebody deletes.
 *
 * The repository here is a temporary directory built per test, not the real tree, so a test
 * cannot pass because some file happens to exist today. The last test reads the real
 * `docs/wiki/` against the real tree.
 */
class WikiCheckTest {

    private val settings = """
        include("udea-core")
        include("moba:game")
        include("moba:desktop")
    """.trimIndent()

    private fun repo(root: File): RepoFiles {
        File(root, "udea-core/src/commonMain/kotlin/dev/wildware/udea").mkdirs()
        File(root, "udea-core/src/commonMain/kotlin/dev/wildware/udea/Tick.kt").writeText("")
        File(root, "moba/game/levels").mkdirs()
        File(root, "moba/game/levels/test_level.udealevel").writeText("")
        File(root, "docs/contracts").mkdirs()
        File(root, "docs/contracts/replicator.md").writeText("")
        return RepoFiles(root)
    }

    private fun check(
        root: File,
        pages: Map<String, String>,
        pending: String = "",
    ): List<GateFinding> = WikiCheck.findings(
        pages = pages,
        pending = WikiCheck.pendingPages(pending),
        repo = repo(root),
        projects = WikiCheck.projects(settings),
    )

    // (a) links between pages

    @Test
    fun `a double-bracket link to a page that does not exist fails`(@TempDir root: File) {
        val findings = check(root, mapOf("Home.md" to "Intro\n\nSee [[Missing Page]].\n"))

        val finding = findings.single()
        assertEquals(WikiCheck.BROKEN_LINK, finding.rule)
        assertEquals("docs/wiki/Home.md", finding.path)
        assertEquals(3, finding.line)
        assertTrue("Missing-Page" in finding.message, finding.message)
    }

    @Test
    fun `a markdown link to a page that does not exist fails`(@TempDir root: File) {
        val findings = check(root, mapOf("Home.md" to "Read [the ECS page](ECS-and-Componets).\n"))

        assertEquals(WikiCheck.BROKEN_LINK, findings.single().rule)
        assertTrue("ECS-and-Componets" in findings.single().message)
    }

    @Test
    fun `links to pages that exist pass, in every spelling`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf(
                "Home.md" to """
                    [[Tick Model]], [[the tick|Tick Model]], [tick](Tick-Model),
                    [tick](Tick-Model.md), [tick](Tick-Model#simclock), [top](#home),
                    [site](https://github.com/wildware/udea), [mail](mailto:a@b.c)
                """.trimIndent(),
                "Tick-Model.md" to "# Tick\n",
            ),
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun `a relative link into the repository must resolve`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf(
                "Home.md" to "[ok](../contracts/replicator.md) and [bad](../contracts/nope.md)\n",
            ),
        )

        val finding = findings.single()
        assertEquals(WikiCheck.BROKEN_LINK, finding.rule)
        assertTrue("../contracts/nope.md" in finding.message, finding.message)
    }

    @Test
    fun `a link written inside code is not a link`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf(
                "Home.md" to "Write `[[Page Name]]` to link.\n\n```\n[x](Nowhere)\n```\n",
            ),
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun `a page listed as pending may be linked before it exists`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf("Home.md" to "[[Getting Started]] and [x](Input)\n"),
            pending = "# written on another branch\nGetting-Started.md\nInput.md\n",
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun `a pending entry for a page that now exists fails, so the list gets deleted`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf("Home.md" to "[[Input]]\n", "Input.md" to "# Input\n"),
            pending = "Input.md\n",
        )

        val finding = findings.single()
        assertEquals(WikiCheck.STALE_PENDING, finding.rule)
        assertEquals("docs/wiki/.pending", finding.path)
        assertTrue("Input.md" in finding.message, finding.message)
    }

    // (b) backticked repository paths

    @Test
    fun `a backticked repository path that does not exist fails`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf("Home.md" to "Line one\nThe clock is in `udea-core/src/commonMain/kotlin/dev/wildware/udea/Tik.kt`.\n"),
        )

        val finding = findings.single()
        assertEquals(WikiCheck.MISSING_PATH, finding.rule)
        assertEquals(2, finding.line)
        assertTrue("Tik.kt" in finding.message, finding.message)
    }

    @Test
    fun `a path whose first directory is a typo is still checked, by its file name`(@TempDir root: File) {
        val findings = check(root, mapOf("Home.md" to "`udea-cor/src/Tick.kt`\n"))

        assertEquals(WikiCheck.MISSING_PATH, findings.single().rule)
    }

    @Test
    fun `backticked paths that exist pass, including directories and elided middles`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf(
                "Home.md" to """
                    `udea-core/src/commonMain/kotlin/dev/wildware/udea/Tick.kt`,
                    `moba/game/levels/test_level.udealevel`, `docs/contracts/`,
                    `udea-core/src/.../Tick.kt`, `udea-core/.../udea/Tick.kt`
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun `an elided path whose file is not under the prefix fails`(@TempDir root: File) {
        val findings = check(root, mapOf("Home.md" to "`moba/.../Tick.kt`\n"))

        assertEquals(WikiCheck.MISSING_PATH, findings.single().rule)
    }

    @Test
    fun `slashed prose, endpoints, globs and build outputs are not repository paths`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf(
                "Home.md" to """
                    `jvm/android`, `@Net/@Sim`, `/health`, `.udeapak/.udearep`,
                    `moba/desktop/build/reports/udea/match/*.png`, `https://x.y/z.md`,
                    `moba/game/build/generated/Foo.kt`, `input.*`
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList(), findings)
    }

    /**
     * `build-logic`'s own package is `dev.wildware.udea.build`, so a real source path can have a
     * folder called `build` in it. Only a Gradle output folder - a `build` that comes before any
     * `src` - is passed over.
     */
    @Test
    fun `a source path through a package folder named build is still checked`(@TempDir root: File) {
        repo(root)
        File(root, "build-logic/build.gradle.kts").also { it.parentFile.mkdirs() }.writeText("")
        File(root, "build-logic/src/main/kotlin/dev/build").mkdirs()
        File(root, "build-logic/src/main/kotlin/dev/build/WikiCheck.kt").writeText("")
        File(root, "build-logic/build/classes").mkdirs()
        File(root, "build-logic/build/classes/WikiCheck.kt").writeText("")

        val typo = check(root, mapOf("Home.md" to "`build-logic/src/main/kotlin/dev/build/WikiChek.kt`\n"))
        val real = check(
            root,
            mapOf(
                "Home.md" to "`build-logic/src/main/kotlin/dev/build/WikiCheck.kt` and " +
                    "`build-logic/src/.../build/WikiCheck.kt`\n",
            ),
        )
        // The elision must find the source file, not the copy in the output folder.
        val outputOnly = check(root, mapOf("Home.md" to "`build-logic/.../classes/WikiCheck.kt`\n"))

        assertEquals(WikiCheck.MISSING_PATH, typo.single().rule)
        assertEquals(emptyList(), real)
        assertEquals(WikiCheck.MISSING_PATH, outputOnly.single().rule)
    }

    // (c) Gradle task paths

    @Test
    fun `a task path naming a project that does not exist fails`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf("Home.md" to "Run it:\n\n```\nsh gradlew :moba:desktp:runEditor\n```\n"),
        )

        val finding = findings.single()
        assertEquals(WikiCheck.UNKNOWN_PROJECT, finding.rule)
        assertEquals(4, finding.line)
        assertTrue(":moba:desktp" in finding.message, finding.message)
    }

    @Test
    fun `a task path in prose is checked too`(@TempDir root: File) {
        val findings = check(root, mapOf("Home.md" to "Try `:udea-cor:test`.\n"))

        assertEquals(WikiCheck.UNKNOWN_PROJECT, findings.single().rule)
    }

    @Test
    fun `task paths on real projects pass, and colons that are not task paths are ignored`(@TempDir root: File) {
        val findings = check(
            root,
            mapOf(
                "Home.md" to """
                    `:moba:desktop:runEditor`, `:udea-core:jvmTest`, `:moba:game`, `:help`,
                    http://127.0.0.1:8010/api, `Foo::class`, `val x: Int`, moba:game, 12:30
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun `projects include the parents Gradle creates implicitly`() {
        assertEquals(setOf(":", ":udea-core", ":moba", ":moba:game", ":moba:desktop"), WikiCheck.projects(settings))
    }

    @Test
    fun `the failure report names every finding and says how to fix it`(@TempDir root: File) {
        val findings = check(root, mapOf("Home.md" to "[[Nope]]\n"))

        val report = WikiCheck.report("udeaVerifyWiki", findings)

        assertTrue(report != null && "UDEA-DOC-004" in report && "docs/wiki/Home.md:1" in report, "$report")
        assertTrue(".pending" in report, report)
    }

    // The real thing.

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    @Test
    fun `the committed wiki points only at things that exist`() {
        val wiki = File(repoRoot, WikiCheck.WIKI_DIRECTORY)
        val pages = wiki.listFiles { f -> f.isFile && f.name.endsWith(".md") }!!.associate { it.name to it.readText() }
        val pending = File(wiki, WikiCheck.PENDING_FILE).takeIf { it.isFile }?.readText().orEmpty()

        assertTrue("Home.md" in pages && "_Sidebar.md" in pages, pages.keys.toString())
        assertEquals(
            emptyList(),
            WikiCheck.findings(
                pages = pages,
                pending = WikiCheck.pendingPages(pending),
                repo = RepoFiles(repoRoot),
                projects = WikiCheck.projects(File(repoRoot, "settings.gradle.kts").readText()),
            ),
        )
    }
}
