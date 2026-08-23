package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass 3 end to end (issue #88).
 *
 * The corpus is one `.udea.kts` carrying seven distinct defects, compiled by the real pass 2.
 * The point of a single run over a single file is the acceptance criterion it proves: the
 * pipeline never aborts on the first defect, so an author with seven problems is told about
 * seven and not about one, seven builds in a row.
 */
class ValidatorPipelineTest {

    /**
     * Seven defects, one per rule, in one file.
     *
     * Every one of them is a real shape from the tree this replaces:
     * - `character/orc_idel` is a typo in a `reference`;
     * - `orc_bp`'s parent points at a sprite sheet, the `ClassCastException`-at-read shape;
     * - `orc_walk` declares 7 columns for an 800px strip, the unchecked `TextureRegion.split`;
     * - `orc_ghost` names a file that is not there, the leading-slash loader mismatch;
     * - `orc_attack` fires a notify on frame 9 of a 6-frame sheet, the notify that never fires;
     * - `loop_a`/`loop_b` are each other's parent, the chain the old runtime walked per spawn;
     * - `orc_idle` is declared twice, which the old `Asset.equals` could not even represent.
     */
    private val defective = """
        spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/Orc-Idle.png", columns = 6, scale = 0.02f)
        spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/Orc-Walk.png", columns = 7, scale = 0.02f)
        spriteSheet(name = "orc_ghost", spritePath = "/sprites/orc/Orc-Ghost.png", columns = 6, scale = 0.02f)

        spriteAnimation(name = "orc_attack", sheet = reference("character/orc_idle"), notifies = mapOf("hit" to 9))
        spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idel"))

        blueprint(name = "orc_bp", parent = reference("character/orc_idle"))
        blueprint(name = "loop_a", parent = reference("character/loop_b"))
        blueprint(name = "loop_b", parent = reference("character/loop_a"))

        spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/Orc-Idle.png", columns = 6, scale = 0.02f)
    """

    private fun context() = ValidationFixture.withArt("pipeline", "character/orc.udea.kts" to defective)

    @Test
    fun `seven distinct defects are all reported in one run`() {
        val report = ValidationFixture.report(context())

        assertEquals(
            listOf(
                UdeaRules.UNRESOLVED_REFERENCE.id,
                UdeaRules.REFERENCE_KIND_MISMATCH.id,
                AssetValidationRules.DUPLICATE_ID.id,
                AssetValidationRules.BLUEPRINT_CYCLE.id,
                AssetValidationRules.MISSING_FILE.id,
                AssetValidationRules.SHEET_GEOMETRY.id,
                AssetValidationRules.NOTIFY_RANGE.id,
            ).sorted(),
            report.diagnostics.map { it.ruleId }.distinct().sorted(),
            "the pipeline must not abort on the first defect: $report",
        )
        assertEquals(7, report.diagnostics.size, "one diagnostic per defect, no more: $report")
        assertTrue(report.hasErrors)
    }

    /** Every diagnostic carries a repo-relative span, which is what "with a file and a line" means. */
    @Test
    fun `every diagnostic is located in the file that declared the defect`() {
        for (diagnostic in ValidationFixture.report(context()).diagnostics) {
            val span = assertNotNull(diagnostic.span, "unlocated: $diagnostic")
            assertTrue(
                span.path.endsWith("character/orc.udea.kts"),
                "span points outside the fixture: $span",
            )
            assertTrue(span.startLine >= 1, "line 0 means the location was lost: $diagnostic")
        }
    }

    /**
     * Spec section 5's cap is the sink's, and the pipeline hands it the cap rather than
     * truncating itself.
     *
     * Asserted by lowering the cap, because a corpus of twenty-six real defects would be a
     * fixture that tested arithmetic. What matters is that the count is not reimplemented here.
     */
    @Test
    fun `the cap is enforced and the remainder is counted, not dropped silently`() {
        val context = context()
        val full = AssetValidatorPipeline().validate(context)
        val capped = AssetValidatorPipeline(cap = 3).validate(context)

        assertEquals(3, capped.diagnostics.size)
        assertEquals(
            full.diagnostics.size - 3 + full.suppressedCount,
            capped.suppressedCount,
            "everything the cap hid must be counted",
        )
        assertEquals(full.diagnostics.take(3), capped.diagnostics, "the cap truncates the ranking")
    }

    /**
     * One missing id referenced from five places is one diagnostic (spec section 5).
     *
     * The validator reports all five honestly — it has no basis for choosing one — and tags each
     * with `causedBy`, and the shared `DiagnosticSink` collapses them. Asserting the count here
     * is asserting that this producer sets `causedBy`, which is the only part of the mechanism
     * that lives in this module.
     */
    @Test
    fun `one unresolved id referenced five times is reported once`() {
        // Five files rather than five lines of one file, on purpose. Pass 2's fallback span
        // index answers "where is `character/ghost` referenced *in this file*" with the first
        // site, so five references in one file collapse at the sink's *dedupe* step - which
        // would prove nothing about root-cause collapse. Five files give five distinct spans
        // and therefore five accepted diagnostics, and only `causedBy` can reduce them to one.
        val context = ValidationFixture.context(
            "fan-in",
            *("abcde".map { name ->
                "character/$name.udea.kts" to
                    """blueprint(name = "$name", parent = reference("character/ghost"))"""
            }).toTypedArray(),
        )

        val raw = UnresolvedReferenceValidator.validate(context)
        assertEquals(5, raw.size, "the validator reports every site; the sink is what collapses")
        assertTrue(raw.all { it.causedBy == "character/ghost" })

        val report = ValidationFixture.report(context)
        val unresolved = report.diagnostics.filter { it.ruleId == UdeaRules.UNRESOLVED_REFERENCE.id }
        assertEquals(1, unresolved.size, "five referrers, one diagnostic: $report")
        assertEquals(4, report.suppressedCount)
    }

    /**
     * A validator that throws is reported, and does not cost the other seven their findings.
     *
     * The pipeline's whole contract is "never abort", and a bug in a validator is the one way
     * that contract could quietly fail. `VALIDATOR_FAILED` is an error naming the validator, so
     * it cannot be mistaken for a defect in the assets.
     */
    @Test
    fun `a validator that throws is reported without taking the run down`() {
        val exploding = object : AssetValidator {
            override val name: String = "ExplodingValidator"
            override val rules = listOf(AssetValidationRules.VALIDATOR_FAILED)
            override fun validate(context: ValidationContext) = error("deliberate")
        }
        val pipeline = AssetValidatorPipeline(
            validators = listOf(exploding) + AssetValidatorPipeline.DEFAULT,
        )
        val report = pipeline.validate(context())

        val failure = assertNotNull(
            report.diagnostics.firstOrNull { it.ruleId == AssetValidationRules.VALIDATOR_FAILED.id },
        )
        assertEquals(Severity.Error, failure.severity)
        assertTrue("ExplodingValidator" in failure.message, failure.message)
        assertTrue(
            report.diagnostics.map { it.ruleId }.contains(AssetValidationRules.NOTIFY_RANGE.id),
            "the other validators still ran: $report",
        )
    }

    /**
     * Every rule any registered validator can raise is in a registry.
     *
     * Issue #88's last acceptance criterion, and the reason it is one: an id minted at a call
     * site is not an id. `UdeaRules` owns the two reference rules — the same two the K2 checker
     * in `udea-compiler-plugin` raises — and `AssetValidationRules` owns the rest in its
     * reserved band until they can be moved.
     */
    @Test
    fun `every validator's rule id is registered`() {
        val registered = (UdeaRules.all + AssetValidationRules.all).associateBy { it.id }
        for (validator in AssetValidatorPipeline.DEFAULT) {
            assertTrue(validator.rules.isNotEmpty(), "${validator.name} declares no rules")
            for (rule in validator.rules) {
                assertEquals(
                    rule,
                    registered[rule.id],
                    "${validator.name} raises ${rule.id}, which no registry declares",
                )
            }
        }
        // And the registry has no rule nothing can raise, which is how a registry rots.
        val raised = AssetValidatorPipeline.DEFAULT.flatMap { it.rules }.map { it.id }.toSet()
        assertEquals(
            setOf(AssetValidationRules.VALIDATOR_FAILED.id),
            AssetValidationRules.all.map { it.id }.toSet() - raised,
            "VALIDATOR_FAILED is raised by the pipeline itself; anything else here is dead",
        )
    }
}
