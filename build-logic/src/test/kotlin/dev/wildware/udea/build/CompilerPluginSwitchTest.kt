package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tripwire under `-Pudea.compilerPlugin.enabled=false`.
 *
 * Spec 7 makes this flag the mitigation for D8: a Kotlin release that breaks the K2 plugin
 * must degrade to checkers-off rather than blocking every phase. `udea.kotlin-library` reads
 * the flag and publishes it, and **nothing consumes it** — no module applies a
 * `KotlinCompilerPluginSupportPlugin`, nothing puts `udea-compiler-plugin` on a
 * `kotlinCompilerPluginClasspath`, and no `-Xplugin` argument naming it is produced outside
 * the plugin's own kctfork harness. So the `plugin-disabled` CI job compiles exactly what the
 * `build` job compiled, and its green tick proves the flag is *accepted*, not that flipping
 * it changes anything.
 *
 * That is written down in `ci.yml` and in `docs/compiler-plugin.md`, and a written-down claim
 * about the absence of something rots the moment somebody adds it. This is the test that
 * stops it rotting: it fails on the day the wiring lands, which is the day the flag has to
 * become real and both documents have to stop saying "inert".
 *
 * The alternative — deleting the CI leg — was rejected because the leg does prove one thing
 * (`UdeaBuildFlags` rejects a mistyped value on every module), and because a missing gate is
 * not a gate someone remembers to add back.
 */
class CompilerPluginSwitchTest {

    private val repoRoot = File("..").canonicalFile

    /**
     * Everything a real wiring would have to say somewhere. Names, not behaviour: this is a
     * tripwire on a documented absence, and it only has to notice.
     */
    private val wiringMarkers = listOf(
        "KotlinCompilerPluginSupportPlugin",
        "kotlinCompilerPluginClasspath",
        "SubpluginOption",
        "-Xplugin",
    )

    /**
     * Where wiring could plausibly live. `udea-compiler-plugin`'s own sources are excluded:
     * the plugin declares its own CLI contract and its test harness passes `-Xplugin` to
     * kctfork, and neither of those applies the plugin to a `udea-*` module's compilation.
     *
     * `udea-gradle/src` is scanned explicitly, and that is the point of the list rather than
     * a `maxDepth(2)` walk. `udea-gradle` is the module `docs/compiler-plugin.md` names as
     * the wiring's future home, and its sources sit seven directories down: a real
     * `KotlinCompilerPluginSupportPlugin` implemented there and applied through
     * `id("dev.wildware.udea")` carries none of the markers in any *build script*, so a
     * tripwire that read only build scripts would stay green through exactly the change it
     * exists to notice. `moba/src` is here for the same reason — it is the one game module.
     */
    private fun buildScripts(): List<File> =
        (
            repoRoot.resolve("build-logic/src/main/kotlin").walkTopDown() +
                repoRoot.resolve("udea-gradle/src").walkTopDown() +
                repoRoot.resolve("moba/src").walkTopDown() +
                repoRoot.walkTopDown().maxDepth(2)
            )
            .filter { it.isFile }
            .filter { it.name.endsWith(".gradle.kts") || it.name.endsWith(".kt") }
            .filterNot { it.invariantSeparatorsPath.contains("/build/") }
            .filterNot { it.invariantSeparatorsPath.contains("/udea-compiler-plugin/") }
            .distinct()
            .toList()

    @Test
    fun `the outer switch is still inert, and the moment it is not this test says so`() {
        val scripts = buildScripts()
        assertTrue(
            scripts.any { it.name == "udea.kotlin-library.gradle.kts" },
            "the scan found no convention plugins under $repoRoot, so it is checking nothing",
        )
        // And that it reaches the module the docs name as the wiring's home. Without this the
        // scan could silently stop covering udea-gradle - which is what it did, when the walk
        // was depth-limited to build scripts and udea-gradle's sources sit seven levels down.
        assertTrue(
            scripts.any { it.invariantSeparatorsPath.contains("/udea-gradle/src/") },
            "the scan reached no udea-gradle source; a KotlinCompilerPluginSupportPlugin " +
                "implemented there and applied via id(\"dev.wildware.udea\") would carry none " +
                "of the markers in any build script, so this tripwire must read that module",
        )
        val wired = scripts.filter { file ->
            val text = file.readText()
            wiringMarkers.any { it in text }
        }
        assertEquals(
            emptyList(),
            wired.map { it.relativeTo(repoRoot).invariantSeparatorsPath },
            "the K2 plugin is now applied to a real compilation, so " +
                "-P${UdeaBuildFlags.COMPILER_PLUGIN_ENABLED} must actually toggle it: make " +
                "UdeaBuildFlags.compilerPluginEnabled gate that wiring, then delete the " +
                "\"the switch is inert\" paragraphs from .github/workflows/ci.yml and from " +
                "docs/compiler-plugin.md. Until all three are done, the plugin-disabled CI " +
                "leg is a green tick for something nobody has checked.",
        )
    }

    @Test
    fun `ci and the docs both describe the switch as inert while it is`() {
        // The two places a reader meets the claim. If one is updated and the other is not,
        // the repository disagrees with itself about whether a CI tick means anything.
        val ci = repoRoot.resolve(".github/workflows/ci.yml")
        val docs = repoRoot.resolve("docs/compiler-plugin.md")
        assertTrue(ci.isFile, "ci.yml not found at $ci")
        assertTrue(docs.isFile, "docs/compiler-plugin.md not found at $docs")
        assertTrue(
            "inert" in ci.readText(),
            "ci.yml no longer says the plugin-disabled leg's switch is inert, but " +
                "CompilerPluginSwitchTest still finds no wiring - one of the two is wrong",
        )
        assertTrue(
            "inert" in docs.readText(),
            "docs/compiler-plugin.md no longer says the outer switch is inert, but " +
                "CompilerPluginSwitchTest still finds no wiring - one of the two is wrong",
        )
    }
}
