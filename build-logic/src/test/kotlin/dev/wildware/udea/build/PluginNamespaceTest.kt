package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A convention plugin a game outside this repository applies has to be named after a Maven
 * namespace this project owns.
 *
 * Gradle publishes a **plugin marker** for every plugin: a POM whose group is the plugin id and
 * whose artifact is `<id>.gradle.plugin`. Resolving `id("x") version "1"` is resolving
 * `x:x.gradle.plugin:1`, so a plugin id is a Maven group, and Sonatype authorises a publisher
 * per namespace. Ours is `dev.wildware`, verified by a DNS TXT record on wildware.dev; a PUT to
 * any other namespace comes back 403.
 *
 * A local Maven repository has no such rule, which is why `publishToMavenLocal`,
 * `scripts/outside-game-proof.sh` and a green `build` all said nothing while the first real run
 * of `.github/workflows/release.yml` failed on it - the engine's modules published and the
 * convention plugins did not (snapshot run 35523838813, issue #265). This is the source-level
 * half of the fence; the artefact-level half is the marker scan in the outside-game proof, which
 * reads coordinates out of a repository the publish actually wrote.
 *
 * Two kinds of convention plugin, told apart by their id alone:
 *
 *  - `dev.wildware.udea.*` is published, and a game outside may apply it.
 *  - `udea.*` is internal to this build; one precompiled script plugin applying another gets it
 *    off the jar's own classpath, with no marker involved, so nothing outside can apply it.
 *
 * So the rule these tests enforce is: **anything an outside game is told to apply must be named
 * in the published namespace, and must be a plugin that exists.** Making an internal convention
 * public is a rename, not a mention.
 */
class PluginNamespaceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private val buildLogicScript: File = File(repoRoot, "build-logic/build.gradle.kts")

    private val conventionScriptDir: File = File(repoRoot, "build-logic/src/main/kotlin")

    /**
     * Every convention plugin this build declares, as **bare name -> declared id**.
     *
     * The bare name (`game-gates`, `kotlin-base`) is the part that survives a rename, so it is
     * what a reference in a document is resolved through: that is how a stale `udea.game-gates`
     * in a guide is recognised as a reference to a plugin rather than dismissed as an unknown
     * word. Two sources, because this build has two kinds of plugin: the precompiled scripts in
     * `src/main/kotlin`, whose id is their filename, and the two hand-registered in
     * `build-logic/build.gradle.kts`.
     */
    private val declaredIdByBareName: Map<String, String> by lazy {
        val fromScripts = conventionScriptDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(GRADLE_SCRIPT_SUFFIX) }
            .map { it.name.removeSuffix(GRADLE_SCRIPT_SUFFIX) }
        val fromRegistrations = REGISTERED_ID.findAll(buildLogicScript.readText())
            .map { it.groupValues[1] }
            .toList()
        (fromScripts + fromRegistrations).associateBy { bareName(it) }
    }

    /**
     * The files that tell somebody outside this repository which plugin to apply.
     *
     * The template is a working game of exactly the shape `docs/new-game.md` describes, and the
     * wiki tutorial walks a reader through building one. Every plugin id in them is an
     * instruction to a build that resolves from a repository, which is the only place the
     * namespace rule exists.
     */
    private val outsideSources: List<File> = buildList {
        File(repoRoot, "templates/new-game").walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isFile && it.name.endsWith(GRADLE_SCRIPT_SUFFIX) }
            .forEach { add(it) }
        add(File(repoRoot, "docs/new-game.md"))
        add(File(repoRoot, "docs/wiki/Tutorial-Make-a-Game.md"))
    }

    /**
     * Where a convention plugin id is a live string inside this repository: every build script,
     * and `build-logic`'s own sources, which name ids to `pluginManager.withPlugin` and to the
     * TestKit fixtures.
     */
    private val insideSources: List<File> by lazy {
        buildList {
            repoRoot.walkTopDown()
                .onEnter { it.name !in SKIPPED_DIRECTORIES }
                .filter { it.isFile && it.name.endsWith(GRADLE_SCRIPT_SUFFIX) }
                .filterNot { it.startsWith(File(repoRoot, "templates")) }
                .forEach { add(it) }
            conventionScriptDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { add(it) }
        }
    }

    @Test
    fun `the namespace this test enforces is the one build-logic publishes under`() {
        val declared = assertNotNull(
            PUBLISHED_NAMESPACE_LITERAL.find(buildLogicScript.readText()),
            "build-logic/build.gradle.kts no longer declares `val publishedNamespace = \"...\"`. " +
                "That literal is the rule these tests enforce and the one the publish tasks are " +
                "filtered by; if it moves, move this test with it.",
        )
        assertEquals(PUBLISHED_NAMESPACE, declared.groupValues[1])
    }

    @Test
    fun `every convention plugin a game outside this repository applies is published`() {
        val offenders = outsideSources.flatMap { source ->
            references(source).filterNot { it.id.startsWith("$PUBLISHED_NAMESPACE.") }
                .map { "${source.relativeTo(repoRoot).invariantSeparatorsPath}:${it.line} ${it.id}" }
        }
        assertEquals(
            emptyList(), offenders,
            "Central authorises this account for `$PUBLISHED_NAMESPACE` and refuses every other " +
                "namespace with a 403, and a plugin marker's group is the plugin id. Each line " +
                "below tells a game outside this repository to apply a plugin whose marker " +
                "cannot be published, so the game cannot resolve it. Rename the convention into " +
                "`$PUBLISHED_NAMESPACE.udea.*`, or stop naming it outside.",
        )
    }

    @Test
    fun `every convention plugin a game outside this repository applies still exists`() {
        assertEquals(
            emptyList(), misspelt(outsideSources),
            "a guide or the template names a convention plugin by an id this build no longer " +
                "declares. A rename that stops halfway leaves an outside game resolving nothing.",
        )
    }

    @Test
    fun `every convention plugin this repository's own build names is one that exists`() {
        assertEquals(
            emptyList(), misspelt(insideSources),
            "a build script or a piece of build logic names a convention plugin by an id this " +
                "build no longer declares. This is the half of a rename that a compile does not " +
                "catch: `pluginManager.withPlugin(\"...\")` on a plugin that no longer has that " +
                "id simply never fires.",
        )
    }

    /**
     * Every reference in [sources] whose spelling is not the id this build declares for it.
     *
     * The two tests above ask the same question of two different sets of files, and the answer is
     * worth different things in each: outside this repository a misspelt id is a game that cannot
     * resolve anything, and inside it is a `withPlugin` that silently never fires. The question
     * is one question, so it is asked once.
     */
    private fun misspelt(sources: List<File>): List<String> =
        sources.flatMap { source ->
            references(source).filter { it.id != declaredIdByBareName[bareName(it.id)] }
                .map {
                    "${source.relativeTo(repoRoot).invariantSeparatorsPath}:${it.line} ${it.id} " +
                        "(this build declares ${declaredIdByBareName[bareName(it.id)]})"
                }
        }

    @Test
    fun `every convention plugin is named either as internal or as published`() {
        val strays = declaredIdByBareName.values
            .filterNot { it.startsWith("udea.") || it.startsWith("$PUBLISHED_NAMESPACE.udea.") }
        assertEquals(
            emptyList(), strays,
            "a convention plugin's id says whether it is published. `udea.*` is internal to this " +
                "build and `$PUBLISHED_NAMESPACE.udea.*` is published; an id in neither shape is " +
                "one nobody can tell from the other.",
        )
    }

    @Test
    fun `the scan reports an unpublishable id and ignores what is not a plugin`() {
        // The control. Every test above asserts that a list came back empty, which is what a scan
        // that finds nothing at all also does - so the scan is only worth something once it has
        // been seen to find the thing it looks for.
        //
        // The text below is the pre-fix tree in miniature, plus the shapes that must NOT be
        // mistaken for a plugin id. Every one of them is copied from a real line in this
        // repository, and the last two are the ones a looser scan actually got wrong while this
        // class was being written: `udea.agent` out of a Gradle property, and
        // `dev.wildware.udea.agent` out of a test-class glob. Both name a plugin that exists,
        // which is what made them pass unnoticed as "references".
        val fixture = """
            plugins {
                id("udea.game-gates")
                id("com.google.devtools.ksp")
            }
            import dev.wildware.udea.build.udeaModule
            dependencies { implementation("dev.wildware.udea:udea-core:0.1.0") }
            val release = providers.gradleProperty("udea.release")
            // -Pudea.agent.port=7820 boots a real headless game
            val assetToolTests = "dev.wildware.udea.agent.assets.*"
        """.trimIndent()

        assertEquals(
            listOf("udea.game-gates"),
            referencesIn(fixture, declaredIdByBareName.keys).map { it.id },
        )
    }

    @Test
    fun `the scan reports a published id it can no longer resolve`() {
        // The other control: the two "still exists" tests compare against a declared id, and a
        // bare name that resolves to nothing must be reported rather than skipped, or a rename
        // that misspells its target passes.
        val fixture = """plugins { id("dev.wildware.udea.kotlin-libary") }"""
        val bareNames = declaredIdByBareName.keys + "kotlin-libary"
        val found = referencesIn(fixture, bareNames).single()
        assertEquals("dev.wildware.udea.kotlin-libary", found.id)
        assertEquals(null, declaredIdByBareName[bareName(found.id)])
    }

    private fun references(source: File): List<Reference> =
        referencesIn(source.readText(), declaredIdByBareName.keys)

    private data class Reference(val id: String, val line: Int)

    private companion object {
        /** The namespace Sonatype has verified for this account, and the only one it will take. */
        const val PUBLISHED_NAMESPACE = "dev.wildware"

        const val GRADLE_SCRIPT_SUFFIX = ".gradle.kts"

        val SKIPPED_DIRECTORIES = setOf("build", ".git", ".gradle", ".claude", "node_modules")

        /** `val publishedNamespace = "dev.wildware"` in `build-logic/build.gradle.kts`. */
        val PUBLISHED_NAMESPACE_LITERAL = Regex("""val\s+publishedNamespace\s*=\s*"([^"]+)"""")

        /** `id = "dev.wildware.udea.agent"` inside `build-logic`'s `gradlePlugin` block. */
        val REGISTERED_ID = Regex("""\bid\s*=\s*"([^"]+)"""")

        /**
         * A convention plugin id as it is written anywhere: in a `plugins { }` block, in a string
         * handed to `pluginManager.withPlugin`, or in a sentence in a guide.
         *
         * Both guards are load-bearing, and each was written against a real line in this tree.
         *
         * The **leading** lookbehind keeps `dev.wildware.udea.kotlin-library` from also matching
         * as the bare `udea.kotlin-library` inside itself, and keeps a Maven coordinate
         * (`dev.wildware.udea:udea-core`) out.
         *
         * The **trailing** one is the interesting half: an id has to end where it is written, so
         * a longer dotted name is not one. Without it `-Pudea.agent.port=7820`
         * (`udea-agent-host/build.gradle.kts`) reads as the plugin `udea.agent`, and
         * `"dev.wildware.udea.agent.assets.*"` (`udea-agent/build.gradle.kts`) reads as
         * `dev.wildware.udea.agent` - a Gradle property and a test-class glob reported as two
         * plugins that are named nothing of the kind. It also has to refuse the backtracked
         * prefixes (`udea.agen`, `udea.age`, ...), which is why it excludes word characters as
         * well as `.` and `-`.
         */
        val PLUGIN_ID = Regex("""(?<![.\w-])(?:dev\.wildware\.)?udea\.[a-z][a-z0-9-]*(?![\w.-])""")

        fun bareName(id: String): String =
            id.removePrefix("$PUBLISHED_NAMESPACE.").removePrefix("udea.")

        /**
         * Every reference to one of [bareNames] in [text], with its line number.
         *
         * Resolved through the bare name rather than through the full id on purpose: a reference
         * spelled with the *wrong* prefix is exactly what these tests exist to find, so it has to
         * be recognised before it is judged.
         */
        fun referencesIn(text: String, bareNames: Set<String>): List<Reference> =
            text.lineSequence().withIndex().flatMap { (index, line) ->
                PLUGIN_ID.findAll(line)
                    .map { Reference(it.value, index + 1) }
                    .filter { bareName(it.id) in bareNames }
            }.toList()
    }
}
