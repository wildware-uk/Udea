package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every repository file a `build-logic` test names is an input Gradle knows this task reads.
 *
 * A `Test` task is up-to-date checked and cacheable against its declared inputs. A test that
 * reads a repository file Gradle has not been told about therefore comes back `FROM-CACHE` or
 * `UP-TO-DATE` after that file changes — which is the one moment the test exists for. The gate
 * has not weakened; it is *absent*, and the build is green while it is absent.
 *
 * That is not hypothetical. On issue #174 a test asserting `AGENTS.md` names a new freeze gate
 * did not bite, because `AGENTS.md` was undeclared. Issue #180 found five more, two of them
 * `determinism-allowlist.txt` and `determinism-audit.md` — the enforcement behind spec section
 * 6's "the allowlist is a reviewed artefact, not a dumping ground". Each was fixed by one line
 * in `outerBuildInputs`, and nothing stopped a sixth from being read undeclared tomorrow. This
 * is what stands in the way of the sixth — as far as it can see, which is the next section.
 *
 * ## What it can see, and what it cannot
 *
 * The scan is a deliberate over-approximation of the source text. It collects, from every test
 * source but its own:
 *
 * - every plain string literal, comments included, so a path named on a commented-out line still
 *   has to be declared;
 * - every SHOUTING_CASE identifier, resolved against the string constants `build-logic`'s own
 *   main sources declare — because `repoRoot.resolve(UdeaVerifyDeterminismTask.AUDIT_FILE)` is
 *   how two of the five undeclared files were read, and a literal-only scan would have missed
 *   them.
 *
 * It then keeps whatever names a file or directory that really exists in the repository. Junk —
 * `"a.kt"`, `"udea.compilerPlugin.enabled"`, `FAILED` — falls out at that step without anybody
 * curating a list, and a path that is *not* read but happens to name a real file costs a line in
 * [NOT_READ_FROM_THE_REPOSITORY] rather than a false failure.
 *
 * **It cannot see a path assembled at runtime.** `"udea-codegen/" + UdeaProtocolLock.FILE_NAME`
 * and the interpolated `gradle-plugins/$PLUGIN_ID.properties` are both read by tests here and
 * neither appears in the scan; both happen to be declared already, by `net-protocol.lock` and by the
 * `udea-gradle` source tree. So this class narrows the hole rather than closing it, and a
 * concatenated repository path is still something a reviewer has to catch by reading. Said
 * plainly here for the same reason `determinism-audit.md` says what its own scanner cannot see.
 *
 * The declared side is not scanned at all: `build-logic/build.gradle.kts` hands over the
 * collection Gradle resolved, as repository-relative paths, in the `udea.declaredTestInputs`
 * system property. A regex over that script would be a second parser between the assertion and
 * its subject, which is this defect's own shape one level up.
 */
class OuterBuildInputsTest {

    /** The repository root. `build-logic` is an included build, so tests run one level down. */
    private val repoRoot: File = File("..").canonicalFile

    /**
     * The paths `build-logic/build.gradle.kts` declared, as this task was actually configured.
     *
     * Files only — [isDeclared] answers for a directory by looking for a declared file under it,
     * which is what `fileTree(rootDir.resolve("../docs/contracts"))` means.
     */
    private val declared: Set<String> =
        (System.getProperty(DECLARED_TEST_INPUTS).orEmpty())
            .lineSequence()
            .filter { it.isNotBlank() }
            .toSet()

    /** Every test source but this one. See [NOT_READ_FROM_THE_REPOSITORY] for why it is excluded. */
    private val testSources: List<File> =
        File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "OuterBuildInputsTest.kt" }
            .sortedBy { it.path }
            .toList()

    /** Repository path -> the test sources that name it. */
    private val named: Map<String, List<String>> by lazy {
        val constants = stringConstantsOfBuildLogic()
        val found = sortedMapOf<String, MutableList<String>>()
        testSources.forEach { source ->
            val text = source.readText()
            val candidates = LITERAL.findAll(text).map { it.groupValues[1] } +
                SHOUTING_CASE.findAll(text).flatMap { constants[it.value].orEmpty() }
            candidates.map { it.removePrefix("../") }
                .filter { it.namesSomethingInTheRepository() }
                .forEach { found.getOrPut(it) { mutableListOf() }.add(source.name) }
        }
        found.mapValues { (_, sources) -> sources.distinct().sorted() }
    }

    @Test
    fun `the declared inputs reached the test JVM`() {
        // The control on everything below. With no manifest every path is "undeclared" and the
        // coverage test fails loudly; with an empty-but-present one it would pass on anything,
        // so the shape of the manifest is asserted rather than merely its existence.
        assertTrue(
            declared.isNotEmpty(),
            "no $DECLARED_TEST_INPUTS system property reached this JVM, so nothing below knows " +
                "what build-logic/build.gradle.kts declared. Check the CommandLineArgumentProvider " +
                "on tasks.test there, and that it spells the property the same way.",
        )
        listOf("settings.gradle.kts", "AGENTS.md", "docs/contracts.lock").forEach {
            assertTrue(it in declared, "the manifest arrived but does not contain $it: $declared")
        }
    }

    @Test
    fun `the scan still finds the repository paths it is known to find`() {
        // Without this, a regex that quietly stopped matching would make the coverage test below
        // pass on an empty set - the exact defect this class is about, one level up. Named files
        // rather than a count: a count goes stale the day somebody adds a test.
        mapOf(
            "AGENTS.md" to "a plain literal",
            "determinism-allowlist.txt" to "a plain literal in AllowlistParserTest",
            "determinism-audit.md" to "UdeaVerifyDeterminismTask.AUDIT_FILE, a resolved constant",
            "docs/contracts" to "ContractFreeze.DIRECTORY, a resolved constant naming a directory",
        ).forEach { (path, how) ->
            assertTrue(
                path in named,
                "the scan no longer finds $path ($how), so it is no longer checking that kind of " +
                    "read at all. Fix the scan rather than this list.",
            )
        }
    }

    @Test
    fun `every repository file a build-logic test names is a declared input of this task`() {
        val undeclared = named.filterKeys { it !in NOT_READ_FROM_THE_REPOSITORY }
            .filterKeys { !isDeclared(it) }
        assertEquals(
            emptyMap(),
            undeclared,
            "these repository paths are named by build-logic tests but are not inputs of " +
                ":build-logic:test, so an edit to one leaves the test that reads it UP-TO-DATE " +
                "and the gate it enforces silently absent. Add each to outerBuildInputs in " +
                "build-logic/build.gradle.kts, with a comment saying which test reads it - or, " +
                "if it is a fixture path that merely happens to name a real file, to " +
                "NOT_READ_FROM_THE_REPOSITORY with the reason.",
        )
    }

    @Test
    fun `no exemption has gone stale`() {
        val unmatched = NOT_READ_FROM_THE_REPOSITORY.keys.filterNot { it in named }
        assertEquals(
            emptyList(),
            unmatched,
            "these exemptions are no longer produced by the scan, so they excuse nothing and " +
                "should be deleted. An exemption list nobody prunes is how the next undeclared " +
                "file gets waved through.",
        )
    }

    /** A path is declared if it is a declared file, or a directory some declared file sits under. */
    private fun isDeclared(path: String): Boolean =
        path in declared || declared.any { it.startsWith("$path/") }

    private fun String.namesSomethingInTheRepository(): Boolean =
        !startsWith("/") && !startsWith(".") && !contains("..") &&
            // A trailing slash means the literal is one half of a concatenation, not something
            // read: `repoRoot.resolve("udea-codegen/" + UdeaProtocolLock.FILE_NAME)`. Counting
            // the directory as read would say `udea-codegen` is covered because two files under
            // it are, which is not the same claim.
            !endsWith("/") &&
            File(repoRoot, this).exists() &&
            // Build outputs are not sources, and a fixture that writes one would otherwise be
            // reported the first time somebody runs the build and not the second.
            !split("/").contains("build")

    /**
     * `NAME` -> every value, for the `String` constants `build-logic`'s main sources declare.
     *
     * Keyed on the simple constant name, because the reference site is `Type.NAME` and matching
     * `Type` too would mean resolving imports. Constants of the same name in different types
     * therefore collide — `UdeaNetComponents.FILE_NAME` and `UdeaProtocolLock.FILE_NAME`, for
     * instance — so every value is kept rather than the last one winning. Keeping all of them is
     * the direction that cannot hide a read, and a value still has to name a real repository path
     * before anything is asserted about it.
     */
    private fun stringConstantsOfBuildLogic(): Map<String, List<String>> =
        File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { STRING_CONSTANT.findAll(it.readText()) }
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] })

    private companion object {

        /**
         * Kept in step with `build-logic/build.gradle.kts` by
         * `the declared inputs reached the test JVM`.
         */
        const val DECLARED_TEST_INPUTS = "udea.declaredTestInputs"

        /** A string literal with no interpolation, escape or whitespace in it. */
        val LITERAL = Regex("\"([A-Za-z0-9_@./-]+)\"")

        val SHOUTING_CASE = Regex("\\b[A-Z][A-Z0-9_]{2,}\\b")

        val STRING_CONSTANT = Regex(
            "\\bval\\s+([A-Z][A-Z0-9_]*)\\s*(?::\\s*String\\s*)?=\\s*\"([^\"\$\\\\]*)\"",
        )

        /**
         * Paths the scan finds that no test reads out of the repository.
         *
         * Each is a fixture path that happens to collide with a real file: the test writes it
         * into a temporary directory, or quotes it in an assertion message. Declaring them as
         * inputs would be a lie about why they are there, so they are excused instead — and
         * `no exemption has gone stale` deletes an excuse the moment it stops applying.
         *
         * `OuterBuildInputsTest.kt` itself is excluded from the scan rather than listed here,
         * because every key below is a string literal in this file: scanning itself would make
         * every exemption self-justifying and that staleness check vacuous.
         */
        val NOT_READ_FROM_THE_REPOSITORY: Map<String, String> = mapOf(
            "gradle.properties" to
                "GradleFixture writes one into its own TestKit root; the repository's is never read",
            "docs/migration/ledger.md" to
                "MigrationVerifyTest and MigrationLedgerTest use it as the ledger path inside a " +
                "fixture tree, and assert on it as a finding's `path`. The real ledger is read by " +
                "udeaLegacyReport, which is a task with its own declared inputs",
            "common/src/main/kotlin/dev/wildware/udea/ecs/system/TransformSystem.kt" to
                "MigrationVerifyTest's `legacyPath`: a plausible legacy path written into a " +
                "fixture, which happens to name a file the old tree really still has",
        )
    }
}
