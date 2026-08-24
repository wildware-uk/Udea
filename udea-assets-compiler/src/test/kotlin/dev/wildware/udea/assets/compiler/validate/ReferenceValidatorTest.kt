package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two reference rules: the id exists, and it is the right kind.
 *
 * Together they are spec section 1's second claim — "a bad `reference()` is a compile error
 * with a file, a line and a did-you-mean, not a crash forty seconds into a match".
 */
class ReferenceValidatorTest {

    private fun corpus() = ValidationFixture.context(
        "references",
        "character/orc.udea.kts" to """
            spriteSheet(name = "orc_idle", spritePath = "sprites/orc/Orc-Idle.png", columns = 6)
            spriteAnimation(name = "idle_anim", sheet = reference("character/orc_idle"))
            spriteAnimation(name = "typo_anim", sheet = reference("character/orc_idel"))
            blueprint(name = "wrong_kind", parent = reference("character/orc_idle"))
            blueprint(name = "right_kind", parent = reference("character/wrong_kind"))
        """,
    )

    /**
     * The did-you-mean is mandatory (spec section 5), and it is what lets an agent self-correct
     * in the turn it is told rather than spending one listing the asset tree.
     */
    @Test
    fun `an unresolved reference names the file, the line and a suggestion`() {
        val diagnostic = assertNotNull(
            UnresolvedReferenceValidator.validate(corpus()).singleOrNull(),
            "exactly one id in the corpus is unresolved",
        )

        assertEquals(UdeaRules.UNRESOLVED_REFERENCE.id, diagnostic.ruleId)
        assertEquals("character/typo_anim", diagnostic.assetId)
        assertEquals("character/orc_idel", diagnostic.causedBy)
        assertTrue("did you mean `character/orc_idle`?" in diagnostic.message, diagnostic.message)

        val span = assertNotNull(diagnostic.span)
        assertTrue(span.path.endsWith("character/orc.udea.kts"), span.path)
        assertTrue(span.path.startsWith("udea-assets-compiler/"), "spans are repo-relative: $span")
    }

    /**
     * The `Fix` replaces the string literal, quotes included, and nothing else.
     *
     * Pass 1's reference span covers the whole literal, so the replacement is exact. Asserted by
     * *applying* it to the source line rather than by comparing the replacement text: a fix that
     * an agent cannot apply to produce valid Kotlin is not a fix, and only applying it says so.
     */
    @Test
    fun `the fix rewrites exactly the id literal`() {
        val context = corpus()
        val diagnostic = assertNotNull(UnresolvedReferenceValidator.validate(context).singleOrNull())
        val fix = assertNotNull(diagnostic.fix, "spec section 5 makes the repair mandatory here")
        val replacement = fix.replacements.single()

        val file = context.repoRoot.resolve(replacement.span.path)
        val line = file.toFile().readLines()[replacement.span.startLine - 1]
        assertEquals(
            """"character/orc_idel"""",
            line.substring(replacement.span.startColumn - 1, replacement.span.endColumn - 1),
            "the span must cover the literal and its quotes",
        )

        val fixed = line.replaceRange(
            replacement.span.startColumn - 1,
            replacement.span.endColumn - 1,
            replacement.newText,
        )
        assertEquals(
            """spriteAnimation(name = "typo_anim", sheet = reference("character/orc_idle"))""",
            fixed.trim(),
        )
    }

    /**
     * A reference to a real asset of the wrong kind is an error, not a cast failure at runtime.
     *
     * `blueprint(parent = ...)` requires a `SpawnRecipe`; `character/orc_idle` is a `SpriteSheet`.
     * The expectation comes from the DSL *parameter*, not from the call, because an author
     * writes `reference("id")` with no type argument.
     */
    @Test
    fun `a reference of the wrong kind names both kinds`() {
        val diagnostic = assertNotNull(
            ReferenceTypeValidator.validate(corpus()).singleOrNull(),
            "only `wrong_kind` points a SpawnRecipe slot at a SpriteSheet",
        )

        assertEquals(UdeaRules.REFERENCE_KIND_MISMATCH.id, diagnostic.ruleId)
        assertEquals("character/wrong_kind", diagnostic.assetId)
        assertTrue("must be a SpawnRecipe" in diagnostic.message, diagnostic.message)
        assertTrue("is a SpriteSheet" in diagnostic.message, diagnostic.message)
        assertNotNull(diagnostic.span)
    }

    /**
     * The expectation is stamped by the signature, not by a table.
     *
     * This is the property the whole design rests on: `sheet` requires a `SpriteSheet` because
     * `AssetScope.spriteAnimation` says so at the parameter. If someone adds a kind and forgets
     * to stamp it, `Ref.expected` is null and the type check silently stops - so it is asserted
     * directly, on the value pass 2 produced.
     */
    @Test
    fun `the expected kind is carried on the reference pass 2 produced`() {
        val context = corpus()
        val sheetRef = assertNotNull(
            context.refSites.firstOrNull { it.owner.id == "character/idle_anim" && it.field == "sheet" },
        )
        assertEquals(SpriteSheet::class.qualifiedName, sheetRef.ref.expected)

        // ...and a slot with no runtime kind behind it carries none, rather than a guess.
        val unconstrained = context.refSites.none { it.ref.expected == "dev.wildware.udea.assets.Character" }
        assertTrue(unconstrained, "AssetKind.Unpublishable must not be invented into an FQN")
    }

    /** An unresolved reference is not also reported as a kind mismatch. One defect, one id. */
    @Test
    fun `an unresolved reference produces no kind mismatch`() {
        assertNull(
            ReferenceTypeValidator.validate(corpus()).firstOrNull { it.assetId == "character/typo_anim" },
        )
    }
}
