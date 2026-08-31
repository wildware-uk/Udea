package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #132's fourth acceptance criterion, from the build's side.
 *
 * > `udeaValidateAssets` passes on the whole item tree, including a negative test that a recipe
 * > referencing a nonexistent component is a build error with file, line and did-you-mean.
 *
 * Both halves are here: a tree of items that validates clean, and the same tree with one
 * component id mistyped. The negative half is [UnresolvedReferenceValidator]'s `UDEA0004` and
 * not a rule of the item kind's own, and that is the point of declaring `Item.components` as
 * typed references at all - a recipe gets the engine's existing "file, line and did-you-mean"
 * for free, where a `List<String>` would have got nothing.
 *
 * Every fixture below is a real `.udea.kts` on disk, compiled by the real pass 2 and validated
 * by the real pass 3. See [ValidationFixture] for why a hand-built graph would not do: the whole
 * check turns on the `expecting<Item>()` stamp that only the DSL signature applies.
 */
class ItemRecipeValidatorTest {

    /** A tree that prices correctly: 350 + 150 of parts, sold at 750. */
    private fun healthy() = ValidationFixture.context(
        "items-healthy",
        "item/shop.udea.kts" to """
            item(name = "blade", cost = 350, stats = mapOf("strength" to 8F))
            item(name = "whetstone", cost = 150, stats = mapOf("strength" to 3F))
            item(
                name = "greatsword",
                cost = 750,
                stats = mapOf("strength" to 18F),
                components = listOf(reference("item/blade"), reference("item/whetstone")),
                unique = "unique/sharpened",
            )
            item(name = "totem", cost = 0, trinket = true)
        """,
    )

    /**
     * The whole item tree validates clean.
     *
     * The **control** for every negative below. A fence that fires on a healthy tree is as wrong
     * as one that stays quiet on a broken one, and without this the three tests underneath would
     * pass just as happily against a validator that reported everything.
     */
    @Test
    fun `a well priced item tree produces no diagnostics at all`() {
        val report = ValidationFixture.report(healthy())
        assertEquals(
            emptyList(),
            report.diagnostics.map { "${it.ruleId} ${it.message}" },
            "a healthy item tree must validate clean through the whole pipeline",
        )
    }

    /**
     * A recipe naming an id nothing declares is `UDEA0004`, with a file, a line and a suggestion.
     *
     * The criterion, verbatim. Asserted through the *pipeline* rather than by calling
     * `UnresolvedReferenceValidator` directly, because what the criterion promises is what
     * `udeaValidateAssets` does - and the pipeline is what that task runs.
     */
    @Test
    fun `a recipe naming a component that does not exist is a located build error`() {
        val context = ValidationFixture.context(
            "items-missing-component",
            "item/shop.udea.kts" to """
                item(name = "blade", cost = 350)
                item(name = "whetstone", cost = 150)
                item(
                    name = "greatsword",
                    cost = 750,
                    components = listOf(reference("item/blade"), reference("item/whetstoen")),
                )
            """,
        )
        val report = ValidationFixture.report(context)

        val diagnostic = assertNotNull(
            report.diagnostics.singleOrNull { it.ruleId == UdeaRules.UNRESOLVED_REFERENCE.id },
            "exactly one component id in this tree is unresolved: ${report.diagnostics}",
        )
        assertEquals(Severity.Error, diagnostic.severity, "a bad recipe must fail the build")
        assertEquals("item/greatsword", diagnostic.assetId)
        assertEquals("item/whetstoen", diagnostic.causedBy)

        // The did-you-mean. Mandatory by spec section 5, and the half that lets an agent correct
        // the file in the turn it is told rather than spending one listing the asset tree.
        assertTrue(
            "did you mean `item/whetstone`?" in diagnostic.message,
            diagnostic.message,
        )

        // The file and the line, repo-relative.
        val span = assertNotNull(diagnostic.span, "a located error means a span")
        assertTrue(span.path.endsWith("item/shop.udea.kts"), span.path)
        assertTrue(span.path.startsWith("udea-assets-compiler/"), "spans are repo-relative: $span")
        assertTrue(span.startLine > 0, "a line number of $span points at no line")
    }

    /**
     * A recipe pointing at something that is not an item is `UDEA0013`.
     *
     * The other half of what the typed stamp buys, and the reason `item(components = ...)` takes
     * `expecting<Item>()` rather than leaving the slot unconstrained: an unconstrained slot is a
     * case `ReferenceTypeValidator` is required to stay *silent* on, so this would be a
     * `ClassCastException` in the shop instead.
     */
    @Test
    fun `a recipe pointing at something that is not an item is a kind mismatch`() {
        val context = ValidationFixture.context(
            "items-wrong-kind",
            "item/shop.udea.kts" to """
                item(name = "blade", cost = 350)
                gameplayEffect(name = "sharpen", effectDuration = instant())
                item(
                    name = "greatsword",
                    cost = 750,
                    components = listOf(reference("item/blade"), reference("item/sharpen")),
                )
            """,
        )
        val diagnostic = assertNotNull(
            ReferenceTypeValidator.validate(context).singleOrNull(),
            "exactly one component points at a non-item",
        )
        assertEquals(UdeaRules.REFERENCE_KIND_MISMATCH.id, diagnostic.ruleId)
        assertEquals("item/greatsword", diagnostic.assetId)
        assertTrue("Item" in diagnostic.message && "GameplayEffect" in diagnostic.message, diagnostic.message)
    }

    /**
     * An item costing less than its parts is `UDEA0037`, and the message names the arithmetic.
     *
     * Not a taste rule. The shop derives the counter price by subtracting what the owned
     * components are worth from the shelf price, so a shelf price below the parts is a purchase
     * that pays gold *out* - and nothing downstream would report it, because a negative price is
     * an ordinary integer.
     */
    @Test
    fun `an item that costs less than its components fails the build`() {
        val context = ValidationFixture.context(
            "items-underpriced",
            "item/shop.udea.kts" to """
                item(name = "blade", cost = 350)
                item(name = "whetstone", cost = 150)
                item(
                    name = "greatsword",
                    cost = 400,
                    components = listOf(reference("item/blade"), reference("item/whetstone")),
                )
            """,
        )
        val diagnostic = assertNotNull(
            ItemRecipeValidator.validate(context).singleOrNull(),
            "exactly one item in this tree is underpriced",
        )
        assertEquals(AssetValidationRules.ITEM_RECIPE.id, diagnostic.ruleId)
        assertEquals(Severity.Error, diagnostic.severity)
        assertEquals("item/greatsword", diagnostic.assetId)
        assertTrue("costs 400 gold" in diagnostic.message, diagnostic.message)
        assertTrue("worth 500" in diagnostic.message, diagnostic.message)
        assertNotNull(diagnostic.span, "an underpriced recipe must name the line it is on")
    }

    /**
     * An item listing itself is `UDEA0037` too, under the other arm.
     *
     * A purchase that consumes the copy it is producing. Reported as its own message rather than
     * folded into the cost arm, because the repair is different: one is a number to raise, the
     * other is a line to delete.
     */
    @Test
    fun `an item that is its own component fails the build`() {
        val context = ValidationFixture.context(
            "items-self-component",
            "item/shop.udea.kts" to """
                item(name = "blade", cost = 350)
                item(name = "greatsword", cost = 750, components = listOf(reference("item/greatsword")))
            """,
        )
        val diagnostic = assertNotNull(
            ItemRecipeValidator.validate(context).singleOrNull(),
            "exactly one item in this tree names itself",
        )
        assertEquals(AssetValidationRules.ITEM_RECIPE.id, diagnostic.ruleId)
        assertEquals("item/greatsword", diagnostic.assetId)
        assertTrue("lists itself" in diagnostic.message, diagnostic.message)
    }

    /**
     * The recipe rule stays silent when a component does not resolve.
     *
     * One defect, one diagnostic. Summing a cost this pass cannot know would report the same
     * mistyped id twice under two rule ids, and `DiagnosticSink`'s root-cause ranking would then
     * be choosing between two descriptions of one typo.
     */
    @Test
    fun `an unresolved component is not also reported as a pricing failure`() {
        val context = ValidationFixture.context(
            "items-missing-component-silence",
            "item/shop.udea.kts" to """
                item(name = "blade", cost = 350)
                item(name = "greatsword", cost = 10, components = listOf(reference("item/nope")))
            """,
        )
        assertEquals(
            emptyList(),
            ItemRecipeValidator.validate(context),
            "UDEA0037 must not fire on a recipe whose component UDEA0004 has already reported",
        )
    }
}
