package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.Fixtures
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.diagnostics.UdeaRules
import dev.wildware.udea.assets.pack.BundleReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The packer refuses to write a reference whose target is not the kind the field declares, and
 * says so with the shared rule id.
 *
 * ## Why this test names the *front ends'* corpus
 *
 * `resources/assets` is the five-script corpus issues #86 and #87 landed. Three of its
 * references point at `character(...)` declarations - `config` at `character/orc`, and
 * `level/test_level` at four of them. `character` is `AssetKind.Unpublishable`: the provisional
 * DSL declares it and `udea-assets` has no `Character` type, so the packer has nothing to put
 * in a `Ref<Blueprint>`.
 *
 * That is a real gap in the DSL, not a bug in the packer, and it is asserted here rather than
 * worked around so that it cannot quietly stop being reported. When #84's remaining half lands
 * and `character` yields a `Blueprint`, this test fails and is deleted - which is the correct
 * way for a documented gap to close.
 *
 * `docs/contracts/asset-index.md` item 5 is what this satisfies: *"Report UDEA0004 / UDEA0013
 * for `.udea.kts` defects, from the same `UdeaRules` constants the checker uses"*.
 */
class KindMismatchTest {

    private fun packSharedCorpus(): GraphPacker.Result =
        GraphPacker.pack(PackFixture.compile(TestPaths.repoRoot, Fixtures.assetRoot, "kind-mismatch"))

    @Test
    fun `a reference to a kind with no runtime type is reported as UDEA0013`() {
        val result = packSharedCorpus()

        val mismatches = result.diagnostics.filter { it.ruleId == UdeaRules.REFERENCE_KIND_MISMATCH.id }
        assertTrue(mismatches.isNotEmpty(), "the shared corpus does reference untyped kinds")
        assertEquals(
            listOf("config", "level/test_level", "level/test_level", "level/test_level", "level/test_level"),
            mismatches.map { it.message.substringAfter('\'').substringBefore('\'') }.sorted(),
            "the reported sources are not the ones that reference character(...)",
        )
        assertTrue(
            mismatches.all { "no runtime type" in it.message },
            "the message should say why the target cannot fill the field",
        )
    }

    /**
     * The bundle it produces is still readable.
     *
     * That is the point of dropping the field rather than writing an unresolved index: the build
     * fails on the diagnostic, but an engineer inspecting the artifact gets a file that opens.
     * A bundle whose `Ref` could not be bound would throw at load, and the load-time message
     * would say nothing about which script was wrong.
     */
    @Test
    fun `the mismatched field is dropped, not written as an unresolvable index`() {
        val result = packSharedCorpus()

        val bytes = BundleWriter.write(BundleContent(assets = result.assets))

        BundleReader.open(bytes).use { bundle ->
            assertEquals(Fixtures.EXPECTED_IDS.size, bundle.registry.size)
            val config = bundle.registry.find(dev.wildware.udea.assets.AssetId("config"))
            assertTrue(
                (config as dev.wildware.udea.assets.GameConfig).defaultCharacter == null,
                "the mismatched reference should have been dropped",
            )
        }
    }

    /** The kind-correct corpus reports nothing. Otherwise the test above proves only noise. */
    @Test
    fun `the pack corpus reports no kind mismatch at all`() {
        val result = GraphPacker.pack(
            PackFixture.compile(TestPaths.repoRoot, PackFixture.assetRoot, "kind-clean"),
        )

        assertEquals(
            emptyList(),
            result.diagnostics.map { "${it.ruleId} ${it.message}" },
            "the pack corpus is meant to be kind-correct end to end",
        )
    }
}
