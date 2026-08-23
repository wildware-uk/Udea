package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.assets.AssetIndexFixtures
import dev.wildware.udea.compiler.testing.CheckerRun
import dev.wildware.udea.compiler.testing.TestSource
import dev.wildware.udea.compiler.testing.UdeaCompileTesting
import dev.wildware.udea.compiler.testing.source
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import dev.wildware.udea.diagnostics.assets.AssetCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `reference("...")` checker, driven through a real two-module compilation.
 *
 * ### Why two modules and not one file
 *
 * The thing issue #41 has to be true of is a *downstream* compilation: the `reference`
 * declaration, the asset kinds and the asset index all arrive from somewhere else, on the
 * classpath. A single-compilation fixture would prove the checker works on source symbols and
 * say nothing about the case that actually happens, and one of the two binding paths
 * ([UdeaAssetReferences]) is specifically about what survives into a class file. So the
 * fixture API is compiled once, with the plugin off, and every case below compiles against its
 * **output directory** plus a fixture index jar - which is exactly the shape a Gradle project
 * dependency has.
 */
class UdeaAssetReferenceCheckerTest {

    // ---- the upstream "module" -------------------------------------------------------------

    /**
     * The asset API, standing in for what `udea-assets` will declare.
     *
     * `AssetRef` keeps the default (runtime) retention rather than `common`'s
     * `AnnotationRetention.SOURCE`, because SOURCE is the reason the annotation alone cannot
     * carry this across a module boundary today - see [UdeaAssetReferences]. `spawn` exists to
     * exercise the annotation path on a function that is *not* called `reference`.
     */
    private val assetsApi: TestSource = source(
        "AssetsApi.kt",
        """
        package dev.wildware.udea.assets

        @Target(AnnotationTarget.VALUE_PARAMETER)
        annotation class AssetRef

        class Ref<T : Any>(val path: String)

        fun <T : Any> reference(@AssetRef path: String): Ref<T> = Ref(path)

        fun spawn(@AssetRef id: String): String = id

        fun unmarked(id: String): String = id
        """,
    )

    /** The asset kinds the index names, so the subtype question has an answer. */
    private val kinds: TestSource = source(
        "Kinds.kt",
        """
        package udea.fixtures

        open class CharacterAsset
        class BlueprintAsset
        """,
    )

    /** The upstream module's class output, used as a classpath root by every case below. */
    private val upstreamClasses: File by lazy {
        val run = UdeaCompileTesting.compile(listOf(assetsApi, kinds), applyPlugin = false)
        assertEquals(
            emptyList(),
            run.otherMessages,
            "the upstream fixture module must compile cleanly:\n" + run.describe(),
        )
        File(run.workDir, "out")
    }

    /** A jar carrying the index for `character/orc` and `blueprint/arrow`. */
    private val indexJar: File by lazy {
        AssetIndexFixtures.jarRoot(AssetIndexFixtures.encoded(AssetIndexFixtures.exampleCatalog()))
    }

    // ---- the cases ------------------------------------------------------------------------

    @Test
    fun `an unresolved id is an error at the literal, with a did-you-mean`() {
        val run = downstream(TYPO, indexJar)

        val diagnostic = run.assertOne(UdeaRules.UNRESOLVED_REFERENCE.id)
        val span = requireNotNull(diagnostic.span) { "no span:\n" + run.describe() }
        // The literal's own position, not the call's. `val orc = reference<CharacterAsset>(`
        // is 36 characters, so the opening quote is column 37, on line 6 of the fixture.
        assertEquals(6, span.startLine, run.describe())
        assertEquals(37, span.startColumn, "the squiggle must sit on the typo, not on the call")
        assertEquals(Severity.Error, diagnostic.severity)
        assertTrue(
            "Did you mean 'character/orc'?" in diagnostic.message,
            "spec section 5 makes the did-you-mean mandatory; message was: " + diagnostic.message,
        )
    }

    @Test
    fun `a correctly spelled reference of the right kind compiles clean`() {
        val run = downstream(
            source(
                "Good.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                val orc = reference<CharacterAsset>("character/orc")
                """,
            ),
            indexJar,
        )

        run.assertNoUdeaDiagnostics()
    }

    @Test
    fun `an id of the wrong kind is UDEA0013, naming both kinds`() {
        val run = downstream(
            source(
                "WrongKind.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.BlueprintAsset

                val orc = reference<BlueprintAsset>("character/orc")
                """,
            ),
            indexJar,
        )

        val diagnostic = run.assertOne(UdeaRules.REFERENCE_KIND_MISMATCH.id)
        assertTrue(AssetIndexFixtures.CHARACTER_KIND in diagnostic.message, diagnostic.message)
        assertTrue("BlueprintAsset" in diagnostic.message, diagnostic.message)
    }

    /**
     * A subtype of the indexed kind is not a mismatch in the *other* direction either: the
     * index says `character/orc` is a `CharacterAsset`, and asking for a `CharacterAsset` is
     * satisfied by it. This is the assertion that stops the check being written backwards.
     */
    @Test
    fun `the check is a subtype question, not an equality one`() {
        val run = downstream(
            source(
                "Supertype.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference

                val any = reference<Any>("character/orc")
                """,
            ),
            indexJar,
        )

        run.assertNoUdeaDiagnostics()
    }

    @Test
    fun `a non-constant argument is silent`() {
        val run = downstream(
            source(
                "Dynamic.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                fun pick(name: String) = reference<CharacterAsset>(name)

                val fromCall = reference<CharacterAsset>(compute())

                fun compute(): String = "charater/orc"
                """,
            ),
            indexJar,
        )

        run.assertNoUdeaDiagnostics()
    }

    /**
     * The other half of the constant story: a template built only out of constants *is*
     * folded, so a typo cannot be hidden behind a `+`.
     */
    @Test
    fun `a concatenation of constants is resolved like a literal`() {
        val run = downstream(
            source(
                "Concatenated.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                const val FOLDER = "charater"

                val orc = reference<CharacterAsset>(FOLDER + "/orc")
                """,
            ),
            indexJar,
        )

        val diagnostic = run.assertOne(UdeaRules.UNRESOLVED_REFERENCE.id)
        assertTrue("charater/orc" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `an empty index is silent`() {
        val run = downstream(TYPO, AssetIndexFixtures.emptyRoot())

        run.assertNoUdeaDiagnostics()
    }

    @Test
    fun `no index anywhere on the classpath is silent`() {
        val run = downstream(TYPO, extraRoot = null)

        run.assertNoUdeaDiagnostics()
    }

    @Test
    fun `Suppress by rule id silences the diagnostic`() {
        for (fixture in SUPPRESSED) {
            val run = downstream(fixture, indexJar)
            run.assertNoUdeaDiagnostics()
        }
    }

    @Test
    fun `an unrelated rule id in Suppress does not silence it`() {
        val run = downstream(
            source(
                "WrongSuppress.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                @Suppress("UDEA0001")
                val orc = reference<CharacterAsset>("charater/orc")
                """,
            ),
            indexJar,
        )

        run.assertOne(UdeaRules.UNRESOLVED_REFERENCE.id)
    }

    /**
     * The annotation binding path, across a module boundary: `spawn` is not called `reference`,
     * so the only thing that can select its parameter is `@AssetRef` read back off the compiled
     * upstream class file.
     */
    @Test
    fun `any parameter marked AssetRef is checked, whatever the function is called`() {
        val run = downstream(
            source(
                "Spawned.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.spawn
                import dev.wildware.udea.assets.unmarked

                val spawned = spawn("charater/orc")
                val ignored = unmarked("charater/orc")
                """,
            ),
            indexJar,
        )

        val diagnostic = run.assertOne(UdeaRules.UNRESOLVED_REFERENCE.id)
        assertEquals(6, requireNotNull(diagnostic.span).startLine, "an unmarked parameter is not checked")
    }

    /**
     * An index this build cannot read is the one loud case, and it is loud **once**: spec
     * section 5 forbids one diagnostic per referrer for a single root cause.
     */
    @Test
    fun `a bumped index format version produces exactly one diagnostic naming both versions`() {
        val run = downstream(
            source(
                "TwoRefs.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                val a = reference<CharacterAsset>("charater/orc")
                val b = reference<CharacterAsset>("blueprint/arow")
                """,
            ),
            AssetIndexFixtures.versionedRoot(AssetCatalog.FORMAT_VERSION + 1),
        )

        val diagnostic = run.assertOne(UdeaRules.ASSET_INDEX_FORMAT.id)
        assertTrue("${AssetCatalog.FORMAT_VERSION + 1}" in diagnostic.message, diagnostic.message)
        assertTrue("${AssetCatalog.FORMAT_VERSION}" in diagnostic.message, diagnostic.message)
        assertEquals(
            emptyList(),
            run.diagnostics.filter { it.ruleId == UdeaRules.UNRESOLVED_REFERENCE.id },
            "an unreadable index must not also be reported as every id being missing",
        )
    }

    /**
     * Spec 7's degrade path. With no `-Xplugin` argument nothing here runs, so the same typo
     * that is an error above compiles - which is what "the plugin must never become
     * load-bearing" means for this checker specifically.
     */
    @Test
    fun `with the plugin not applied the same typo compiles`() {
        val run = UdeaCompileTesting.compile(
            listOf(TYPO),
            applyPlugin = false,
            extraClasspath = listOf(upstreamClasses, indexJar),
        )

        assertEquals(emptyList(), run.diagnostics, run.describe())
        assertEquals(emptyList(), run.otherMessages, run.describe())
    }

    // ---- plumbing -------------------------------------------------------------------------

    private fun downstream(fixture: TestSource, extraRoot: File?): CheckerRun =
        UdeaCompileTesting.compile(
            listOf(fixture),
            extraClasspath = listOfNotNull(upstreamClasses, extraRoot),
        )

    private fun CheckerRun.assertOne(ruleId: String) = run {
        assertEquals(
            emptyList(),
            otherMessages,
            "the fixture itself must compile, or the checker's silence proves nothing:\n" + describe(),
        )
        val matching = diagnostics.filter { it.ruleId == ruleId }
        assertEquals(1, matching.size, "expected exactly one $ruleId:\n" + describe())
        matching.single()
    }

    private fun CheckerRun.assertNoUdeaDiagnostics() {
        assertEquals(emptyList(), diagnostics, describe())
        assertEquals(
            emptyList(),
            otherMessages,
            "the fixture itself must compile, or a clean run proves nothing:\n" + describe(),
        )
    }

    private companion object {
        /**
         * The Phase 2 demo fixture. Line 5, column 37 is the opening quote of the typo, and
         * both numbers are asserted rather than described.
         */
        val TYPO: TestSource = source(
            "Typo.kt",
            """
            package udea.game

            import dev.wildware.udea.assets.reference
            import udea.fixtures.CharacterAsset

            val orc = reference<CharacterAsset>("charater/orc")
            """,
        )

        /** `@Suppress` at the property, at the function and at the file. */
        val SUPPRESSED: List<TestSource> = listOf(
            source(
                "SuppressedProperty.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                @Suppress("UDEA0004")
                val orc = reference<CharacterAsset>("charater/orc")
                """,
            ),
            source(
                "SuppressedFunction.kt",
                """
                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                @Suppress("UDEA0004")
                fun orc() = reference<CharacterAsset>("charater/orc")
                """,
            ),
            source(
                "SuppressedFile.kt",
                """
                @file:Suppress("UDEA0004")

                package udea.game

                import dev.wildware.udea.assets.reference
                import udea.fixtures.CharacterAsset

                val orc = reference<CharacterAsset>("charater/orc")
                """,
            ),
        )
    }
}
