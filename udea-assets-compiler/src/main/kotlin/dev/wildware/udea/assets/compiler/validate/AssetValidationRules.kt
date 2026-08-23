package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRule
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * The rule ids pass 3 raises that the shared registry does not already own.
 *
 * ### Why these are not in `UdeaRules`
 *
 * They should be, and `UdeaRules` is right that "a producer-local id is not an id". They are
 * here for the same reason [dev.wildware.udea.assets.compiler.AssetCompilerRules] exists: this
 * wave is scoped to `udea-assets-compiler`, `udea-diagnostics` is a frozen leaf another wave
 * owns, and minting an id in a file outside the module would be an edit outside the module.
 *
 * The compromise is made safe the same way, and `ModuleContractTest` holds it:
 *
 * - the ids sit in the reserved **UDEA003x band**, disjoint from both `UdeaRules`' sequential
 *   numbering and `AssetCompilerRules`' UDEA002x band, so no concurrent producer can collide;
 * - every id already matches [UdeaRules.ID_FORMAT], so the eventual move into `UdeaRules` is a
 *   cut and paste that keeps every id;
 * - **nothing here re-declares a defect `UdeaRules` already names.** An unresolved
 *   `reference("...")` is [UdeaRules.UNRESOLVED_REFERENCE] and a wrong-kinded one is
 *   [UdeaRules.REFERENCE_KIND_MISMATCH]; both are raised from the shared registry by
 *   [UnresolvedReferenceValidator] and [ReferenceTypeValidator]. That is the whole point of
 *   the shared registry — the K2 checker in `udea-compiler-plugin` raises the same two ids for
 *   the same two defects, and a developer who has seen `UDEA0004` in an editor must not learn
 *   a second number for it from a build.
 */
public object AssetValidationRules {

    /** Two declarations claim one asset id, so one of them is unreachable. */
    public val DUPLICATE_ID: UdeaRule = UdeaRule(
        id = "UDEA0030",
        defaultSeverity = Severity.Error,
        description = "two declarations claim the same asset id, so which one wins depends on " +
            "evaluation order",
    )

    /**
     * A blueprint's parent chain comes back to itself.
     *
     * An error and not a warning because `Blueprint` flattens parents at build time
     * (`udea-assets`' `Blueprint` KDoc: "the runtime does zero parent walking"), so a cycle has
     * no fixed point to flatten to — the old runtime walked the chain per spawn and would
     * simply have recursed until the stack ran out, mid-match.
     */
    public val BLUEPRINT_CYCLE: UdeaRule = UdeaRule(
        id = "UDEA0031",
        defaultSeverity = Severity.Error,
        description = "an asset's parent chain contains a cycle, so flattening it does not " +
            "terminate",
    )

    /**
     * A declaration names a file that is not in the asset root.
     *
     * This is the rule that catches the leading-slash mismatch between a script's
     * `spritePath = "/sprites/..."` and a loader's stripped `sprites/...` key: both spellings
     * normalise to one [dev.wildware.udea.assets.compiler.ResFile], and then either the file is
     * there or the build says so with a line number.
     */
    public val MISSING_FILE: UdeaRule = UdeaRule(
        id = "UDEA0032",
        defaultSeverity = Severity.Error,
        description = "a declaration names a resource file that does not exist in the asset root",
    )

    /**
     * A sprite sheet's declared grid does not fit the image it names.
     *
     * Raised as an [Severity.Error] when the grid does not divide the image at all — the case
     * that makes `TextureRegion.split` hand back frames sliced across two drawings — and as a
     * [Severity.Warning] when it divides but yields non-square frames, which every sheet in
     * `docs/art-assets.md` says is wrong ("a horizontal strip of 100x100 frames") without it
     * being wrong in principle for art that has not been written yet.
     */
    public val SHEET_GEOMETRY: UdeaRule = UdeaRule(
        id = "UDEA0033",
        defaultSeverity = Severity.Error,
        description = "a sprite sheet's declared rows and columns do not divide the actual " +
            "image dimensions",
    )

    /**
     * An animation notify names a frame the animation does not have.
     *
     * The defect this replaces was silent: the old lookup in `animationSets.kt` simply found
     * nothing, so a mistyped frame index meant a sword swing that never connected and no error
     * anywhere.
     */
    public val NOTIFY_RANGE: UdeaRule = UdeaRule(
        id = "UDEA0034",
        defaultSeverity = Severity.Error,
        description = "an animation notify is on a frame index outside its sheet's frame count",
    )

    /**
     * A `.udea.kts` reads a clock or an unseeded random.
     *
     * A pack has to be reproducible: spec 3.6 makes the asset pack a deterministic function of
     * its sources, and the whole rewind/replay story downstream assumes two builds of one tree
     * produce one pack. `Random.nextFloat()` at *declaration* time makes the pack itself differ
     * between builds, which is a different and worse thing than randomness at spawn time.
     */
    public val NONDETERMINISTIC_ASSET: UdeaRule = UdeaRule(
        id = "UDEA0035",
        defaultSeverity = Severity.Error,
        description = "a .udea.kts reads a clock or an unseeded random, so the asset pack it " +
            "produces differs between two builds of the same sources",
    )

    /**
     * A validator itself threw.
     *
     * Reported rather than propagated, so one broken validator cannot hide the findings of the
     * other seven — the pipeline's whole contract is that it never aborts on the first defect.
     * It is an [Severity.Error] and it names the validator, because it is a bug in the build
     * tool and must not read as an asset problem the author can fix.
     */
    public val VALIDATOR_FAILED: UdeaRule = UdeaRule(
        id = "UDEA0036",
        defaultSeverity = Severity.Error,
        description = "an asset validator threw; this is a defect in the build tool, not in the " +
            "assets it was checking",
    )

    /** Every rule pass 3 mints locally, in id order. */
    public val all: List<UdeaRule> = listOf(
        DUPLICATE_ID,
        BLUEPRINT_CYCLE,
        MISSING_FILE,
        SHEET_GEOMETRY,
        NOTIFY_RANGE,
        NONDETERMINISTIC_ASSET,
        VALIDATOR_FAILED,
    ).sortedBy { it.id }

    /** The reserved band, asserted by `ModuleContractTest`. */
    public val BAND: IntRange = 30..39
}
