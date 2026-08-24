package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.Fixtures
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.validate.ReferenceTypeValidator
import dev.wildware.udea.assets.compiler.validate.ValidationContext
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.reference
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The packer refuses to write a reference whose target is not the kind the field declares, and
 * says so with the shared rule id.
 *
 * ## What this test used to assert, and why it says something else now
 *
 * It used to pack the front ends' corpus and assert **five** `UDEA0013`s, because
 * `AssetScope.character` was `AssetKind.Unpublishable` and `EntityDefinition.blueprint` was a
 * `Ref<Blueprint>`, so every `entity(blueprint = reference("character/..."))` was a mismatch and
 * every one of them was dropped from the bundle. Its own KDoc said the correct way for that gap
 * to close was for this test to fail and be rewritten, and this is that rewrite.
 *
 * Both halves are asserted here, over graphs built directly through the DSL rather than over a
 * corpus, because "the check still fires" and "the check no longer fires on a character" are two
 * claims and a corpus that exercised one would be silent about the other:
 *
 * - a level entity pointing at a `soundCue` is still `UDEA0013`, and the field is still dropped;
 * - a level entity pointing at a `character` is accepted, because a `Character` is a
 *   `SpawnRecipe`, and the reference survives into the bundle.
 *
 * `docs/contracts/asset-index.md` item 5 is what this satisfies: *"Report UDEA0004 / UDEA0013
 * for `.udea.kts` defects, from the same `UdeaRules` constants the checker uses"*.
 */
class KindMismatchTest {

    /** A graph declared through the DSL, the way a script would declare it. */
    private fun graphOf(declare: AssetScope.() -> Unit): AssetGraph {
        val scope = AssetScope(idPrefix = "", defaultName = "fixture")
        scope.declare()
        return AssetGraph.of(scope.assets)
    }

    /** One character with the art it needs, so a level may name it. */
    private fun AssetScope.orc() {
        spriteSheet(name = "orc_idle_sheet", spritePath = "sprites/orc/idle.png", columns = 4)
        spriteAnimation(name = "orc_idle", sheet = reference("orc_idle_sheet"))
        soundCue(name = "orc_hurt", sounds = listOf("sounds/orc/hurt.ogg"))
        character(
            name = "orc",
            animationMap = mapOf("idle" to reference("orc_idle")),
            sounds = mapOf("hit" to reference("orc_hurt")),
        )
    }

    @Test
    fun `a level entity pointing at a character is accepted, not a kind mismatch`() {
        val result = GraphPacker.pack(
            graphOf {
                orc()
                level(name = "arena", entities = { entity(name = "orc_0", blueprint = reference("orc")) })
            },
        )

        assertEquals(
            emptyList(),
            result.diagnostics.map { "${it.ruleId} ${it.message}" },
            "a `character(...)` is a `SpawnRecipe`, which is what an entity slot declares",
        )

        BundleReader.open(BundleWriter.write(BundleContent(assets = result.assets))).use { bundle ->
            val level = bundle.registry[reference<Level>("arena")]
            assertEquals(1, level.entities.size)
            assertEquals(
                AssetId("orc"),
                assertNotNull(level.entities.single().blueprint, "the entity kept its recipe").id,
            )
        }
    }

    @Test
    fun `a level entity pointing at a sound cue is still UDEA0013, and the field is dropped`() {
        val result = GraphPacker.pack(
            graphOf {
                orc()
                level(name = "arena", entities = { entity(name = "wrong", blueprint = reference("orc_hurt")) })
            },
        )

        val mismatches = result.diagnostics.filter { it.ruleId == UdeaRules.REFERENCE_KIND_MISMATCH.id }
        assertEquals(1, mismatches.size, "exactly the one wrong reference: ${result.diagnostics}")
        val message = mismatches.single().message
        assertTrue("'arena' references 'orc_hurt'" in message, message)
        assertTrue("SoundCue" in message && "SpawnRecipe" in message, message)

        // Dropped rather than written as a sentinel index: the build fails on the diagnostic, and
        // an engineer inspecting the artifact still gets a file that opens.
        BundleReader.open(BundleWriter.write(BundleContent(assets = result.assets))).use { bundle ->
            val level = bundle.registry[reference<Level>("arena")]
            assertNull(level.entities.single().blueprint, "the mismatched reference should be gone")
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

    /**
     * And so does the front ends' corpus, which reported five of them until `Character` existed.
     *
     * **Pass 3 as well as pass 4.** Those are two independent kind checks over one graph -
     * `ReferenceTypeValidator` reads `Ref.expected`, which `AssetScope` stamps at each DSL
     * signature, and `GraphPacker` reads the `KClass` its schema names - and packing alone was not
     * enough. That was a live defect during this change: `level(entities = listOf(...))`, the
     * bare-reference overload, kept stamping `Blueprint` after the named-entity overload had been
     * widened to `SpawnRecipe`, so a corpus written in the list form validated red and packed
     * green with no test in front of either.
     */
    @Test
    fun `the front ends corpus reports no kind mismatch in either pass`() {
        val root = Fixtures.assetRoot
        val graph = PackFixture.compile(TestPaths.repoRoot, root, "kind-mismatch")

        val packed = GraphPacker.pack(graph)
        assertEquals(
            emptyList(),
            packed.diagnostics.filter { it.ruleId == UdeaRules.REFERENCE_KIND_MISMATCH.id }
                .map { it.message },
            "pass 4",
        )

        val scan = UdeaDeclarationScanner(TestPaths.repoRoot, root).use { it.scanTree() }
        val validated = ReferenceTypeValidator.validate(
            ValidationContext(
                declared = graph.assets.values.toList(),
                repoRoot = TestPaths.repoRoot,
                assetRoot = root,
                declarations = scan.declarations,
                sources = AssetCompiler.scriptsUnder(root),
            ),
        )
        assertEquals(emptyList(), validated.map { it.message }, "pass 3")
    }
}
