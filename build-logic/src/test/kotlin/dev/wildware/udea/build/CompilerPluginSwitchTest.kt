package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That `-Pudea.compilerPlugin.enabled` still switches something off.
 *
 * Spec 7 makes this flag the mitigation for D8: a Kotlin release that breaks the K2 plugin
 * must degrade to checkers-off rather than blocking every phase. For the whole of Phase 0 the
 * flag was read by `udea.kotlin-library`, stored in `extraProperties` and consumed by nobody,
 * so the `plugin-disabled` CI leg compiled byte for byte what the `build` job compiled. This
 * class was then a tripwire on that documented absence, written to fail the day wiring landed.
 *
 * The wiring landed (issue #164), so the tripwire is inverted: it now fails if the wiring
 * *goes away* — if nothing implements a `KotlinCompilerPluginSupportPlugin`, if the convention
 * every module is on stops applying it, if the flag stops gating it, or if `ci.yml` and
 * `docs/compiler-plugin.md` drift back to describing a switch that does nothing.
 *
 * What it deliberately does not do is assert that a compilation really loads the plugin. No
 * source scan can know that. `udeaVerifyCompilerPlugin` answers it on every module of every
 * build by reading the resolved plugin classpath, and the `checkers-fire` CI leg answers the
 * question one level further out, by compiling a broken component and reading the rule id back.
 */
class CompilerPluginSwitchTest {

    private val repoRoot = File("..").canonicalFile

    private val convention =
        repoRoot.resolve("build-logic/src/main/kotlin/udea.kotlin-library.gradle.kts")

    private val ci = repoRoot.resolve(".github/workflows/ci.yml")

    private val docs = repoRoot.resolve("docs/compiler-plugin.md")

    private val buildLogicScript = repoRoot.resolve("build-logic/build.gradle.kts")

    /** Every Kotlin source and build script `build-logic` could hold the wiring in. */
    private fun buildLogicSources(): List<File> =
        repoRoot.resolve("build-logic/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile }
            .filter { it.name.endsWith(".kt") || it.name.endsWith(".gradle.kts") }
            .toList()

    @Test
    fun `something implements a KotlinCompilerPluginSupportPlugin`() {
        val sources = buildLogicSources()
        assertTrue(
            sources.any { it.name == "udea.kotlin-library.gradle.kts" },
            "the scan found no convention plugins under $repoRoot, so it is checking nothing",
        )
        val wiring = sources.filter { "KotlinCompilerPluginSupportPlugin" in it.readText() }
        assertTrue(
            wiring.isNotEmpty(),
            "no build-logic source implements a KotlinCompilerPluginSupportPlugin any more. " +
                "Without one, nothing puts udea-compiler-plugin on a kotlinCompilerPluginClasspath, " +
                "no -Xplugin argument is produced, and -P${UdeaBuildFlags.COMPILER_PLUGIN_ENABLED} " +
                "is back to being a flag that reads and discards a value.",
        )
    }

    @Test
    fun `the convention every module is on applies it, so a new module cannot forget`() {
        // The same reasoning the root build script gives for applying the legacy and
        // module-graph gates centrally: a gate a module opts into is a gate a new module
        // forgets, and the person who owns the module is the wrong person to be able to
        // switch off the rule.
        assertTrue(convention.isFile, "not found: $convention")
        assertTrue(
            "apply<UdeaCompilerPluginSupport>()" in convention.readText(),
            "udea.kotlin-library no longer applies UdeaCompilerPluginSupport, so whichever " +
                "modules still compile with the K2 plugin do so by accident",
        )
    }

    @Test
    fun `the wiring is gated by the flag rather than unconditional`() {
        val support = repoRoot.resolve(
            "build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaCompilerPluginSupport.kt",
        )
        assertTrue(support.isFile, "not found: $support")
        val text = support.readText()
        assertTrue(
            "udeaCompilerPluginEnabled()" in text && "isApplicable" in text,
            "UdeaCompilerPluginSupport.isApplicable no longer consults the " +
                "${UdeaBuildFlags.COMPILER_PLUGIN_ENABLED} flag, so the degrade procedure in " +
                "docs/compiler-plugin.md would leave the plugin applied",
        )
    }

    @Test
    fun `the check that the classpath matches the promise is wired into check`() {
        // `udeaVerifyCompilerPlugin` is the only thing that notices the wiring silently
        // stopping. It has to be on `check`, or it is a task nobody runs.
        val text = convention.readText()
        assertTrue("udeaVerifyCompilerPlugin" in text, "the classpath gate is gone from $convention")
        assertTrue(
            Regex("""tasks\.named\("check"\)\s*\{\s*\n\s*dependsOn\(udeaVerifyCompilerPlugin\)""")
                .containsMatchIn(text),
            "udeaVerifyCompilerPlugin is registered but no longer wired into `check`",
        )
    }

    @Test
    fun `ci and the docs describe a switch that works, not an inert one`() {
        // The two places a reader meets the claim. While the switch was inert both said so and
        // this test asserted they did; now that it is not, the same test asserts they have
        // stopped, so the repository cannot disagree with itself in either direction.
        assertTrue(ci.isFile, "ci.yml not found at $ci")
        assertTrue(docs.isFile, "docs/compiler-plugin.md not found at $docs")
        assertFalse(
            "inert" in ci.readText(),
            "ci.yml still describes the plugin-disabled leg's switch as inert, but the wiring " +
                "is in place and udeaVerifyCompilerPlugin proves it",
        )
        assertFalse(
            "inert" in docs.readText(),
            "docs/compiler-plugin.md still describes the outer switch as inert, but the wiring " +
                "is in place",
        )
    }

    // --- where the checkers-fire probe may live (issue #173) ---------------------------

    /**
     * `module=` and `probe=` as the `checkers-fire` step sets them.
     *
     * The step declares the module once and derives its Gradle task from it, so the two
     * cannot drift; these two tests read the same two variables rather than a path spelled
     * out a third time here.
     */
    private fun probeDeclaration(): Pair<String, String> {
        val text = ci.readText()
        val absent = "ci.yml no longer declares it in the checkers-fire step, so these tests " +
            "are reading nothing. If the step was rewritten, rewrite them with it."
        val module = assertNotNull(
            Regex("""^\s*module=(\S+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1),
            "no `module=` line: $absent",
        )
        val probe = assertNotNull(
            Regex("""^\s*probe=(\S+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1),
            "no `probe=` line: $absent",
        )
        return module to probe
    }

    /**
     * Source trees of the *outer* build that `build-logic` compiles a second time.
     *
     * `build-logic` is an included build, so anything it srcDirs is compiled by
     * `:build-logic:compileKotlin` — with `build-logic`'s classpath and the Kotlin the Gradle
     * distribution embeds, not with the module's own classpath and not with the K2 plugin.
     * That compilation runs before any task of the main build, so a file placed in such a
     * tree never reaches the compilation a reader assumes it reaches.
     */
    private fun doublyCompiledSourceTrees(): List<String> {
        val text = buildLogicScript.readText()
        val resolved = Regex("""val\s+(\w+)\s*:\s*File\s*=\s*rootDir\.resolve\("\.\./([^"]+)"\)""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val dirs = Regex("""kotlin\.srcDir\(([^)]+)\)""")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .mapNotNull { resolved[it] }
            .toList()
        // The control. A parser that has quietly stopped matching returns an empty list and
        // makes the test below pass on anything, which is the exact defect issue #173 is
        // about, one level up. "No foreign source dirs at all" is a legitimate answer; "the
        // script still srcDirs something and this could not say what" is not.
        assertTrue(
            dirs.isNotEmpty() || "kotlin.srcDir(" !in text,
            "$buildLogicScript still calls kotlin.srcDir(...), but this test could not work " +
                "out which tree, so it is about to pass without checking anything",
        )
        return dirs
    }

    @Test
    fun `the checkers-fire probe is not written into a tree build-logic compiles a second time`() {
        // Issue #173. The probe was written into `udea-gradle/src/main/kotlin`, which
        // `build-logic/build.gradle.kts` adds to its own main source set - the only such tree
        // in the repository. `:build-logic:compileKotlin` therefore compiled the probe first,
        // with no `udea-annotations` anywhere near it, and answered with six unresolved
        // references instead of with a rule id. The job had never once reached a checker.
        //
        // The claim that made it hard to see was in `ci.yml` itself: it named
        // `udea-gradle`'s compile classpath as the reason for the choice, and that classpath
        // is genuinely fine - `implementation(project(":udea-assets-compiler"))` does carry
        // `udea-annotations` through two `api` edges. Nothing about the dependency graph was
        // ever wrong, so reading the graph could not find the fault.
        val (_, probe) = probeDeclaration()
        doublyCompiledSourceTrees().forEach { tree ->
            assertFalse(
                probe == tree || probe.startsWith("$tree/"),
                "the checkers-fire probe is written to $probe, inside $tree, which " +
                    "$buildLogicScript adds to build-logic's own main source set. It will be " +
                    "compiled by :build-logic:compileKotlin - an included build with neither " +
                    "udea-annotations nor the K2 plugin on it - before the main build starts, " +
                    "so the job reports unresolved references and never reaches a checker.",
            )
        }
    }

    @Test
    fun `the checkers-fire probe is in a module the K2 plugin is actually applied to`() {
        // The other way the job can fail without any checker being involved: a probe written
        // into `:udea-annotations` or `:udea-diagnostics` compiles perfectly cleanly, because
        // `UdeaCompilerPluginWiring.EXCLUSIONS` keeps the plugin off both of them.
        val (module, probe) = probeDeclaration()
        assertTrue(
            probe.startsWith("$module/"),
            "the checkers-fire step declares module=$module but writes its probe to $probe, " +
                "so the file it compiles and the file it writes are in different modules",
        )
        assertTrue(
            UdeaCompilerPluginWiring.appliesTo(":$module", enabled = true),
            "the checkers-fire probe is in :$module, which the K2 plugin is not applied to (" +
                (UdeaCompilerPluginWiring.skipReason(":$module", true) ?: "no reason recorded") +
                "). The probe would compile clean and the job would fail claiming the plugin " +
                "is unwired.",
        )
    }

    @Test
    fun `the plugin-disabled CI leg runs the gate that can notice the plugin`() {
        // Without this the leg is back to compiling the same bytes the `build` job compiled.
        // `udeaVerifyCompilerPlugin` is on `check` and therefore on `build`, but naming it
        // here means the leg still asserts the absence even if `check` is narrowed later.
        val text = ci.readText()
        assertTrue(
            "udeaVerifyCompilerPlugin" in text,
            "ci.yml no longer names udeaVerifyCompilerPlugin, so nothing in the workflow " +
                "asserts that -P${UdeaBuildFlags.COMPILER_PLUGIN_ENABLED}=false removes anything",
        )
        assertTrue(
            "checkers-fire" in text,
            "ci.yml no longer has the leg that compiles a broken component in a real module " +
                "and reads the rule id back; without it, a checker could stop firing entirely " +
                "and every other gate would stay green",
        )
    }
}
