package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Duplicate ids and parent cycles.
 */
class GraphValidatorTest {

    /** One id claimed by a `spriteSheet` on line 1 and a `blueprint` on line 2. */
    private fun duplicateCorpus() = ValidationFixture.context(
        "duplicate",
        "character/a.udea.kts" to """
            spriteSheet(name = "twice", spritePath = "sprites/one.png", columns = 1)
            blueprint(name = "twice")
        """,
    )

    /**
     * Two declarations of one id are reported with both locations.
     *
     * The graph cannot represent this — `AssetGraph.of` keeps the last writer — which is why
     * [ValidationContext] is built from the ordered declaration list, and why the spans come
     * from pass 1, which saw both declarations.
     */
    @Test
    fun `a duplicate id names both declarations`() {
        val context = duplicateCorpus()

        val diagnostic = assertNotNull(DuplicateIdValidator.validate(context).singleOrNull())
        assertEquals(AssetValidationRules.DUPLICATE_ID.id, diagnostic.ruleId)
        assertEquals("character/twice", diagnostic.assetId)

        val here = assertNotNull(diagnostic.span)
        assertTrue("blueprint" in diagnostic.message, diagnostic.message)
        assertTrue("spriteSheet" in diagnostic.message, diagnostic.message)
        // Both spans: the diagnostic is anchored at the later declaration (the line an author
        // edits) and the message carries the earlier one.
        assertTrue("character/a.udea.kts:1" in diagnostic.message, diagnostic.message)
        assertEquals(2, here.startLine, "anchored at the duplicate, not at the original")
    }

    /**
     * The same corpus, seen only as a graph, has no duplicate left to find.
     *
     * Written down as an assertion rather than left in a KDoc, because it is a *silent* limit:
     * a caller that builds a context from `graph.assets.values` instead of from
     * `AssetCompileResult.declared` loses this check and nothing tells it so.
     */
    @Test
    fun `a context built from a collapsed graph cannot see a duplicate`() {
        val context = duplicateCorpus()
        assertEquals(1, DuplicateIdValidator.validate(context).size)

        val collapsed = ValidationContext(
            declared = context.graph.assets.values.toList(),
            repoRoot = context.repoRoot,
            assetRoot = context.assetRoot,
            sources = emptyList(),
        )
        assertEquals(emptyList(), DuplicateIdValidator.validate(collapsed))
    }

    /** A three-node cycle prints the whole path, once. */
    @Test
    fun `a three node cycle reports the full path exactly once`() {
        val context = ValidationFixture.context(
            "cycle3",
            "bp/loop.udea.kts" to """
                blueprint(name = "a", parent = reference("bp/b"))
                blueprint(name = "b", parent = reference("bp/c"))
                blueprint(name = "c", parent = reference("bp/a"))
            """,
        )

        val diagnostic = assertNotNull(
            BlueprintCycleValidator.validate(context).singleOrNull(),
            "one cycle is one defect, not three",
        )
        assertEquals(AssetValidationRules.BLUEPRINT_CYCLE.id, diagnostic.ruleId)
        assertTrue("`bp/a` -> `bp/b` -> `bp/c` -> `bp/a`" in diagnostic.message, diagnostic.message)
        assertNotNull(diagnostic.span)
    }

    /** A blueprint that is its own parent says so, and does not print a one-element path. */
    @Test
    fun `a self cycle reports the node twice and nothing else`() {
        val context = ValidationFixture.context(
            "cycle1",
            "bp/self.udea.kts" to """blueprint(name = "me", parent = reference("bp/me"))""",
        )

        val diagnostic = assertNotNull(BlueprintCycleValidator.validate(context).singleOrNull())
        assertTrue("is its own parent" in diagnostic.message, diagnostic.message)
        assertTrue("`bp/me` -> `bp/me`" in diagnostic.message, diagnostic.message)
    }

    /**
     * A chain far longer than a JVM stack is a linear walk, not a `StackOverflowError`.
     *
     * The point of the check is a cycle that the old runtime would have recursed to death on;
     * a validator that itself recursed would only move the crash from the game to the build.
     * Ten thousand links, built directly rather than through ten thousand compiled scripts.
     */
    @Test
    fun `a ten thousand link chain ending in a cycle does not overflow the stack`() {
        val length = 10_000
        val declared = (0 until length).map { index ->
            DeclaredAsset(
                kind = "blueprint",
                kindFqn = dev.wildware.udea.assets.Blueprint::class.qualifiedName,
                id = "bp/n$index",
                // The last link points back at the first: a cycle at the far end of the chain,
                // which is the shape that defeats a naive visited-set walk as well as recursion.
                fields = linkedMapOf<String, Any?>(
                    "parent" to Ref("bp/n${(index + 1) % length}"),
                ),
            )
        }
        val context = ValidationContext(
            declared = declared,
            repoRoot = TestPaths.repoRoot,
            assetRoot = TestPaths.repoRoot,
            sources = emptyList(),
        )

        val diagnostics = BlueprintCycleValidator.validate(context)
        assertEquals(1, diagnostics.size, "one cycle, one diagnostic")

        val message = diagnostics.single().message
        assertTrue("a cycle of $length asset(s)" in message, message)
        // The path is elided rather than spelled out: a hundred-kilobyte message is not a
        // diagnostic. Both ends survive, which is what identifies the cycle.
        assertTrue("`bp/n0`" in message && "(${length + 1 - (BlueprintCycleValidator.MAX_RENDERED - 1)} more)" in message, message)
        assertTrue(message.length < 1_000, "the message stayed readable: ${message.length} chars")
    }

    /** An edge to an id nothing declares is `UDEA0004`, not a cycle that cannot be walked. */
    @Test
    fun `an unresolved parent is not a cycle`() {
        val context = ValidationFixture.context(
            "dangling-parent",
            "bp/dangle.udea.kts" to """blueprint(name = "a", parent = reference("bp/nowhere"))""",
        )
        assertEquals(emptyList(), BlueprintCycleValidator.validate(context))
        assertEquals(1, UnresolvedReferenceValidator.validate(context).size)
    }
}
